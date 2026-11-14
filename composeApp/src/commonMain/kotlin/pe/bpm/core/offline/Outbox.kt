package pe.bpm.core.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cola de operaciones pendientes de la cuenta activa. Las features la usan para registrar y mostrar;
 * solo [SyncEngine] cambia los estados de envío.
 */
interface Outbox {
    /** Todas las operaciones de la cuenta activa, en orden de creación. */
    val operations: Flow<List<OutboxOperation>>
    /** Operaciones sin enviar (PENDING, SENDING). */
    val pendingCount: Flow<Int>
    /** Conflictos, rechazos y reintentos agotados que esperan una decisión del usuario. */
    val attentionCount: Flow<Int>

    /**
     * Registra la operación y devuelve la guardada. Es idempotente por [OutboxDraft.idempotencyKey]:
     * repetir el registro tras un reinicio no crea otra fila.
     */
    suspend fun enqueue(draft: OutboxDraft): OutboxOperation
    suspend fun snapshot(): List<OutboxOperation>
    /** true si hay operaciones sin enviar sobre la misma entidad (el orden debe conservarse). */
    suspend fun hasUnsentForEntity(entityId: String): Boolean
    /** Devuelve a PENDING una operación en conflicto, rechazada o pausada. */
    suspend fun retry(id: String)
    /** Descarta la operación y las que dependen de ella; devuelve cuántas se eliminaron. */
    suspend fun discard(id: String): Int
}

/** Acceso de transición de estados reservado al motor de sincronización. */
interface OutboxQueue : Outbox {
    suspend fun recoverInterrupted()
    suspend fun markSending(id: String): OutboxOperation?
    suspend fun complete(id: String, outcome: SendOutcome, now: Long): OutboxOperation?
    suspend fun purgeSent()
}

class DefaultOutbox(
    private val database: OfflineDatabase,
    private val scope: ActiveScope,
    private val changes: OfflineChanges,
    private val clock: () -> Long,
) : OutboxQueue {
    private val lock = Mutex()
    private fun activeId() = scope.current?.id

    @OptIn(ExperimentalCoroutinesApi::class)
    override val operations: Flow<List<OutboxOperation>> =
        combine(scope.flow, changes.flow) { _, _ -> }.mapLatest { snapshot() }.distinctUntilChanged()
    override val pendingCount: Flow<Int> =
        operations.map { list -> list.count { it.status == OutboxStatus.PENDING || it.status == OutboxStatus.SENDING } }.distinctUntilChanged()
    override val attentionCount: Flow<Int> = operations.map { list -> list.count { it.needsAttention } }.distinctUntilChanged()

    override suspend fun snapshot(): List<OutboxOperation> = activeId()?.let { database.operations(it) }.orEmpty()

    override suspend fun enqueue(draft: OutboxDraft): OutboxOperation = lock.withLock {
        val scopeId = requireNotNull(activeId()) { "No hay una cuenta activa" }
        require(draft.id.isNotBlank() && draft.idempotencyKey.length in 8..100 && draft.kind.isNotBlank())
        require(draft.payload.encodeToByteArray().size <= OutboxPolicy.MAX_PAYLOAD_BYTES)
        val existing = database.operations(scopeId)
        existing.firstOrNull { it.idempotencyKey == draft.idempotencyKey || it.id == draft.id }?.let { return@withLock it }
        val dependencies = if (draft.entityId.isBlank()) emptyList()
        else existing.filter { it.entityId == draft.entityId && !it.resolved }.map { it.id }
        val now = clock()
        val operation = OutboxOperation(
            id = draft.id, kind = draft.kind, organizationId = draft.organizationId, entityId = draft.entityId,
            idempotencyKey = draft.idempotencyKey, clientCreatedAt = draft.clientCreatedAt, payload = draft.payload,
            dependsOn = dependencies, updatedAt = now,
        )
        database.insertOperation(scopeId, operation)
        changes.notifyChanged()
        database.operations(scopeId).first { it.id == draft.id }
    }

    override suspend fun hasUnsentForEntity(entityId: String) =
        entityId.isNotBlank() && snapshot().any { it.entityId == entityId && !it.resolved }

    override suspend fun retry(id: String) = lock.withLock {
        val scopeId = activeId() ?: return@withLock
        val operation = database.operations(scopeId).firstOrNull { it.id == id } ?: return@withLock
        if (operation.status == OutboxStatus.SENT || operation.status == OutboxStatus.SENDING) return@withLock
        database.updateOperation(scopeId, operation.copy(status = OutboxStatus.PENDING, attempts = 0, nextAttemptAt = 0, lastError = "", lastHttpStatus = 0, updatedAt = clock()))
        changes.notifyChanged()
    }

    override suspend fun discard(id: String): Int = lock.withLock {
        val scopeId = activeId() ?: return@withLock 0
        val all = database.operations(scopeId)
        if (all.none { it.id == id }) return@withLock 0
        val doomed = mutableSetOf(id)
        var grew = true
        while (grew) {
            grew = all.filter { it.id !in doomed && it.dependsOn.any { dependency -> dependency in doomed } }
                .map { doomed.add(it.id) }.isNotEmpty()
        }
        doomed.forEach { database.deleteOperation(scopeId, it) }
        changes.notifyChanged()
        doomed.size
    }

    override suspend fun recoverInterrupted() = lock.withLock {
        val scopeId = activeId() ?: return@withLock
        val stuck = database.operations(scopeId).filter { it.status == OutboxStatus.SENDING }
        stuck.forEach { database.updateOperation(scopeId, it.copy(status = OutboxStatus.PENDING, updatedAt = clock())) }
        if (stuck.isNotEmpty()) changes.notifyChanged()
    }

    override suspend fun markSending(id: String): OutboxOperation? = lock.withLock {
        val scopeId = activeId() ?: return@withLock null
        val operation = database.operations(scopeId).firstOrNull { it.id == id && it.status == OutboxStatus.PENDING } ?: return@withLock null
        val updated = operation.copy(status = OutboxStatus.SENDING, updatedAt = clock())
        database.updateOperation(scopeId, updated)
        changes.notifyChanged()
        updated
    }

    override suspend fun complete(id: String, outcome: SendOutcome, now: Long): OutboxOperation? = lock.withLock {
        val scopeId = activeId() ?: return@withLock null
        val operation = database.operations(scopeId).firstOrNull { it.id == id } ?: return@withLock null
        val updated = when (outcome) {
            is SendOutcome.Sent -> operation.copy(status = OutboxStatus.SENT, lastError = "", lastHttpStatus = outcome.httpStatus)
            is SendOutcome.Conflict -> operation.copy(status = OutboxStatus.CONFLICT, lastError = outcome.message, lastHttpStatus = outcome.httpStatus)
            is SendOutcome.Rejected -> operation.copy(status = OutboxStatus.REJECTED, lastError = outcome.message, lastHttpStatus = outcome.httpStatus)
            is SendOutcome.Retry -> {
                val attempts = operation.attempts + if (outcome.countAttempt) 1 else 0
                val wait = maxOf(OutboxPolicy.backoff(maxOf(attempts, 1)), outcome.retryAfterMs ?: 0L).coerceAtMost(OutboxPolicy.MAX_DELAY_MS)
                operation.copy(status = OutboxStatus.PENDING, attempts = attempts, nextAttemptAt = now + if (outcome.countAttempt) wait else 0L,
                    lastError = outcome.message, lastHttpStatus = outcome.httpStatus)
            }
        }.copy(updatedAt = now)
        database.updateOperation(scopeId, updated)
        changes.notifyChanged()
        updated
    }

    override suspend fun purgeSent() = lock.withLock {
        val scopeId = activeId() ?: return@withLock
        val sent = database.operations(scopeId).filter { it.resolved }
        // Solo se retira lo ya enviado que ningún pendiente referencia como dependencia.
        val all = database.operations(scopeId)
        sent.dropLast(OutboxPolicy.KEEP_SENT).filter { old -> all.none { !it.resolved && old.id in it.dependsOn } }
            .forEach { database.deleteOperation(scopeId, it.id) }
    }
}
