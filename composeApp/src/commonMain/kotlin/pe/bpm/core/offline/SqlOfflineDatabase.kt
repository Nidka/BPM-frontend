package pe.bpm.core.offline

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pe.bpm.core.offline.sql.OfflineDb

/**
 * [OfflineDatabase] sobre SQLDelight. El controlador lo aporta cada plataforma (Android: SQLite del
 * sistema; iOS: SQLite nativo; JVM: JDBC para pruebas) y su archivo vive en almacenamiento privado.
 */
class SqlOfflineDatabase(driver: SqlDriver) : OfflineDatabase {
    private val queries = OfflineDb(driver).offlineQueries
    private val lock = Mutex()

    override suspend fun getRecord(scope: String, collection: String, key: String) = lock.withLock {
        queries.getRecord(scope, collection, key).executeAsOneOrNull()?.let { StoredRecord(key, it.json, it.updated_at) }
    }

    override suspend fun listRecords(scope: String, collection: String) = lock.withLock {
        queries.listRecords(scope, collection).executeAsList().map { StoredRecord(it.key, it.json, it.updated_at) }
    }

    override suspend fun putRecord(scope: String, collection: String, record: StoredRecord) = lock.withLock {
        queries.putRecord(scope, collection, record.key, record.json, record.updatedAt); Unit
    }

    override suspend fun deleteRecord(scope: String, collection: String, key: String) = lock.withLock {
        queries.deleteRecord(scope, collection, key); Unit
    }

    override suspend fun deleteCollection(scope: String, collection: String) = lock.withLock {
        queries.deleteCollection(scope, collection); Unit
    }

    override suspend fun insertOperation(scope: String, operation: OutboxOperation): Boolean = lock.withLock {
        val duplicate = queries.listOperations(scope).executeAsList().any { it.idempotency_key == operation.idempotencyKey || it.id == operation.id }
        if (duplicate) false else {
            queries.insertOperation(
                operation.id, scope, operation.kind, operation.organizationId, operation.entityId, operation.idempotencyKey,
                operation.clientCreatedAt, operation.payload, operation.dependsOn.joinToString(","), operation.status.name,
                operation.attempts.toLong(), operation.nextAttemptAt, operation.lastError, operation.lastHttpStatus.toLong(), operation.updatedAt,
            )
            true
        }
    }

    override suspend fun updateOperation(scope: String, operation: OutboxOperation) = lock.withLock {
        queries.updateOperation(
            operation.payload, operation.dependsOn.joinToString(","), operation.status.name, operation.attempts.toLong(),
            operation.nextAttemptAt, operation.lastError, operation.lastHttpStatus.toLong(), operation.updatedAt, scope, operation.id,
        ); Unit
    }

    override suspend fun operations(scope: String) = lock.withLock {
        queries.listOperations(scope).executeAsList().map {
            OutboxOperation(
                id = it.id, kind = it.kind, organizationId = it.organization_id, entityId = it.entity_id,
                idempotencyKey = it.idempotency_key, clientCreatedAt = it.client_created_at, payload = it.payload,
                dependsOn = it.depends_on.split(',').filter(String::isNotEmpty),
                status = runCatching { OutboxStatus.valueOf(it.status) }.getOrDefault(OutboxStatus.REJECTED),
                attempts = it.attempts.toInt(), nextAttemptAt = it.next_attempt_at, lastError = it.last_error,
                lastHttpStatus = it.last_http_status.toInt(), updatedAt = it.updated_at, seq = it.seq,
            )
        }
    }

    override suspend fun deleteOperation(scope: String, id: String) = lock.withLock { queries.deleteOperation(scope, id); Unit }

    override suspend fun clear(scope: String) = lock.withLock {
        queries.clearScopeRecords(scope)
        queries.clearScopeOutbox(scope); Unit
    }

    override suspend fun clearAll() = lock.withLock {
        queries.clearAllRecords()
        queries.clearAllOutbox(); Unit
    }

    override suspend fun scopes() = lock.withLock { (queries.recordScopes().executeAsList() + queries.outboxScopes().executeAsList()).distinct() }
}
