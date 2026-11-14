package pe.bpm.core.offline

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Puerto de persistencia de bajo nivel. Las features nunca lo usan: dependen de [LocalStore],
 * [Outbox] y [SyncEngine]. Implementaciones: SQLDelight (Android/iOS/JVM), documento sobre
 * IndexedDB (web) y memoria (pruebas).
 */
interface OfflineDatabase {
    suspend fun getRecord(scope: String, collection: String, key: String): StoredRecord?
    suspend fun listRecords(scope: String, collection: String): List<StoredRecord>
    suspend fun putRecord(scope: String, collection: String, record: StoredRecord)
    suspend fun deleteRecord(scope: String, collection: String, key: String)
    suspend fun deleteCollection(scope: String, collection: String)

    /** Devuelve false, sin modificar nada, si ya existe una operación con la misma clave de idempotencia. */
    suspend fun insertOperation(scope: String, operation: OutboxOperation): Boolean
    suspend fun updateOperation(scope: String, operation: OutboxOperation)
    suspend fun operations(scope: String): List<OutboxOperation>
    suspend fun deleteOperation(scope: String, id: String)

    /** Borra datos y operaciones de un ámbito. */
    suspend fun clear(scope: String)
    /** Borra todo el contenido de la base (cierre de sesión o cambio de usuario). */
    suspend fun clearAll()
    suspend fun scopes(): List<String>
}

/** Base en memoria: pruebas y valor por defecto cuando un host aún no entrega persistencia. */
class InMemoryOfflineDatabase : OfflineDatabase {
    private val lock = Mutex()
    private val records = LinkedHashMap<Triple<String, String, String>, StoredRecord>()
    private val ops = LinkedHashMap<Pair<String, String>, OutboxOperation>()
    private var nextSeq = 1L

    override suspend fun getRecord(scope: String, collection: String, key: String) =
        lock.withLock { records[Triple(scope, collection, key)] }

    override suspend fun listRecords(scope: String, collection: String) = lock.withLock {
        records.filter { it.key.first == scope && it.key.second == collection }.values.sortedBy { it.key }
    }

    override suspend fun putRecord(scope: String, collection: String, record: StoredRecord) = lock.withLock {
        records[Triple(scope, collection, record.key)] = record
        Unit
    }

    override suspend fun deleteRecord(scope: String, collection: String, key: String) = lock.withLock {
        records.remove(Triple(scope, collection, key))
        Unit
    }

    override suspend fun deleteCollection(scope: String, collection: String) = lock.withLock {
        records.keys.removeAll { it.first == scope && it.second == collection }
        Unit
    }

    override suspend fun insertOperation(scope: String, operation: OutboxOperation) = lock.withLock {
        val duplicate = ops.entries.any { it.key.first == scope && it.value.idempotencyKey == operation.idempotencyKey } ||
            ops.containsKey(scope to operation.id)
        if (duplicate) false else {
            ops[scope to operation.id] = operation.copy(seq = nextSeq++)
            true
        }
    }

    override suspend fun updateOperation(scope: String, operation: OutboxOperation) = lock.withLock {
        val previous = ops[scope to operation.id] ?: return@withLock
        ops[scope to operation.id] = operation.copy(seq = previous.seq)
    }

    override suspend fun operations(scope: String) = lock.withLock {
        ops.filter { it.key.first == scope }.values.sortedBy { it.seq }
    }

    override suspend fun deleteOperation(scope: String, id: String) = lock.withLock {
        ops.remove(scope to id)
        Unit
    }

    override suspend fun clear(scope: String) = lock.withLock {
        records.keys.removeAll { it.first == scope }
        ops.keys.removeAll { it.first == scope }
        Unit
    }

    override suspend fun clearAll() = lock.withLock {
        records.clear()
        ops.clear()
    }

    override suspend fun scopes() = lock.withLock {
        (records.keys.map { it.first } + ops.keys.map { it.first }).distinct()
    }
}
