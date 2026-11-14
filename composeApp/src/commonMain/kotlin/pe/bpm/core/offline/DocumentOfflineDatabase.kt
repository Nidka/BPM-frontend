package pe.bpm.core.offline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import pe.bpm.core.storage.DraftStorage

/**
 * [OfflineDatabase] sobre el almacenamiento de documentos del host. Es la implementación web
 * (IndexedDB del origen) y el respaldo cuando no hay SQLite. Cada registro es un documento; un
 * índice pequeño por colección permite listar sin recorrer todo el almacenamiento. La cola es un
 * único documento por cuenta (acotado por el límite del almacenamiento).
 */
class DocumentOfflineDatabase(private val storage: DraftStorage) : OfflineDatabase {
    private val lock = Mutex()

    private fun recordKey(scope: String, collection: String, key: String) = "offline-r/${scope.length}:$scope/${collection.length}:$collection/$key"
    private fun indexKey(scope: String, collection: String) = "offline-i/${scope.length}:$scope/$collection"
    private fun collectionsKey(scope: String) = "offline-c/${scope.length}:$scope"
    private fun outboxKey(scope: String) = "offline-o/${scope.length}:$scope"
    private val scopesKey = "offline-scopes"

    @Serializable private data class RecordDoc(val json: String, val updatedAt: Long)

    private suspend fun <T> readList(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        storage.read(key)?.let { OfflineJson.decodeFromString(serializer, it) }.orEmpty()

    private suspend fun <T> writeList(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>, value: List<T>) {
        if (value.isEmpty()) storage.remove(key) else storage.write(key, OfflineJson.encodeToString(serializer, value))
    }

    private suspend fun touchScope(scope: String) {
        val known = readList(scopesKey, ListSerializer(String.serializer()))
        if (scope !in known) writeList(scopesKey, ListSerializer(String.serializer()), known + scope)
    }

    override suspend fun getRecord(scope: String, collection: String, key: String) = lock.withLock {
        storage.read(recordKey(scope, collection, key))?.let {
            val doc = OfflineJson.decodeFromString(RecordDoc.serializer(), it)
            StoredRecord(key, doc.json, doc.updatedAt)
        }
    }

    override suspend fun listRecords(scope: String, collection: String) = lock.withLock {
        readList(indexKey(scope, collection), ListSerializer(String.serializer())).sorted().mapNotNull { key ->
            storage.read(recordKey(scope, collection, key))?.let {
                val doc = OfflineJson.decodeFromString(RecordDoc.serializer(), it)
                StoredRecord(key, doc.json, doc.updatedAt)
            }
        }
    }

    override suspend fun putRecord(scope: String, collection: String, record: StoredRecord) = lock.withLock {
        storage.write(recordKey(scope, collection, record.key), OfflineJson.encodeToString(RecordDoc.serializer(), RecordDoc(record.json, record.updatedAt)))
        val keys = readList(indexKey(scope, collection), ListSerializer(String.serializer()))
        if (record.key !in keys) writeList(indexKey(scope, collection), ListSerializer(String.serializer()), keys + record.key)
        val collections = readList(collectionsKey(scope), ListSerializer(String.serializer()))
        if (collection !in collections) writeList(collectionsKey(scope), ListSerializer(String.serializer()), collections + collection)
        touchScope(scope)
    }

    private suspend fun removeRecord(scope: String, collection: String, key: String) {
        storage.remove(recordKey(scope, collection, key))
        val keys = readList(indexKey(scope, collection), ListSerializer(String.serializer()))
        writeList(indexKey(scope, collection), ListSerializer(String.serializer()), keys - key)
    }

    override suspend fun deleteRecord(scope: String, collection: String, key: String) = lock.withLock { removeRecord(scope, collection, key) }

    override suspend fun deleteCollection(scope: String, collection: String) = lock.withLock {
        readList(indexKey(scope, collection), ListSerializer(String.serializer())).forEach { storage.remove(recordKey(scope, collection, it)) }
        storage.remove(indexKey(scope, collection))
    }

    override suspend fun insertOperation(scope: String, operation: OutboxOperation): Boolean = lock.withLock {
        val list = readList(outboxKey(scope), ListSerializer(OutboxOperationDoc.serializer())).map { it.domain() }
        if (list.any { it.idempotencyKey == operation.idempotencyKey || it.id == operation.id }) return@withLock false
        val seq = (list.maxOfOrNull { it.seq } ?: 0L) + 1
        writeList(outboxKey(scope), ListSerializer(OutboxOperationDoc.serializer()), (list + operation.copy(seq = seq)).map { OutboxOperationDoc.of(it) })
        touchScope(scope)
        true
    }

    override suspend fun updateOperation(scope: String, operation: OutboxOperation) = lock.withLock {
        val list = readList(outboxKey(scope), ListSerializer(OutboxOperationDoc.serializer())).map { it.domain() }
        writeList(outboxKey(scope), ListSerializer(OutboxOperationDoc.serializer()),
            list.map { if (it.id == operation.id) operation.copy(seq = it.seq) else it }.map { OutboxOperationDoc.of(it) })
    }

    override suspend fun operations(scope: String) = lock.withLock {
        readList(outboxKey(scope), ListSerializer(OutboxOperationDoc.serializer())).map { it.domain() }.sortedBy { it.seq }
    }

    override suspend fun deleteOperation(scope: String, id: String) = lock.withLock {
        val list = readList(outboxKey(scope), ListSerializer(OutboxOperationDoc.serializer()))
        writeList(outboxKey(scope), ListSerializer(OutboxOperationDoc.serializer()), list.filterNot { it.id == id })
    }

    private suspend fun clearScope(scope: String) {
        readList(collectionsKey(scope), ListSerializer(String.serializer())).forEach { collection ->
            readList(indexKey(scope, collection), ListSerializer(String.serializer())).forEach { storage.remove(recordKey(scope, collection, it)) }
            storage.remove(indexKey(scope, collection))
        }
        storage.remove(collectionsKey(scope))
        storage.remove(outboxKey(scope))
    }

    override suspend fun clear(scope: String) = lock.withLock {
        clearScope(scope)
        writeList(scopesKey, ListSerializer(String.serializer()), readList(scopesKey, ListSerializer(String.serializer())) - scope)
    }

    override suspend fun clearAll() = lock.withLock {
        readList(scopesKey, ListSerializer(String.serializer())).forEach { clearScope(it) }
        storage.remove(scopesKey)
    }

    override suspend fun scopes() = lock.withLock { readList(scopesKey, ListSerializer(String.serializer())) }
}

@Serializable
private data class OutboxOperationDoc(
    val id: String, val kind: String, val organizationId: String, val entityId: String, val idempotencyKey: String,
    val clientCreatedAt: Long, val payload: String, val dependsOn: List<String>, val status: String, val attempts: Int,
    val nextAttemptAt: Long, val lastError: String, val lastHttpStatus: Int, val updatedAt: Long, val seq: Long,
) {
    fun domain() = OutboxOperation(id, kind, organizationId, entityId, idempotencyKey, clientCreatedAt, payload, dependsOn,
        runCatching { OutboxStatus.valueOf(status) }.getOrDefault(OutboxStatus.REJECTED), attempts, nextAttemptAt, lastError, lastHttpStatus, updatedAt, seq)

    companion object {
        fun of(o: OutboxOperation) = OutboxOperationDoc(o.id, o.kind, o.organizationId, o.entityId, o.idempotencyKey, o.clientCreatedAt,
            o.payload, o.dependsOn, o.status.name, o.attempts, o.nextAttemptAt, o.lastError, o.lastHttpStatus, o.updatedAt, o.seq)
    }
}
