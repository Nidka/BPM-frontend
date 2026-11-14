package pe.bpm.features.usage.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import pe.bpm.core.offline.LocalStore
import pe.bpm.core.offline.OfflineJson
import pe.bpm.features.usage.domain.*

/**
 * Cola de uso persistente sobre el almacén local: sobrevive a reinicios y se borra con el cierre de
 * sesión. Implementa el mismo puerto que la cola en memoria; el lote y el rastreador no cambian.
 * Acotada: al superar [capacity] descarta lo más antiguo (el uso no es información crítica).
 */
class PersistentUsageEventQueue(private val store: LocalStore, private val capacity: Int = 500) : UsageEventQueue {
    private val lock = Mutex()

    @Serializable private data class Row(val userId: String, val organizationId: String, val id: String, val sessionId: String,
        val type: String, val module: String?, val occurredAt: String, val durationSeconds: Int?)

    private fun Row.item() = QueuedUsageEvent(userId, organizationId, UsageEvent(id, sessionId, UsageEventType.valueOf(type),
        module?.let { UsageModule.valueOf(it) }, occurredAt, durationSeconds))

    private suspend fun all(): List<QueuedUsageEvent> = store.list(COLLECTION).mapNotNull { record ->
        runCatching { OfflineJson.decodeFromString(Row.serializer(), record.json).item() }.getOrNull()
    }.sortedWith(compareBy({ it.event.occurredAt }, { it.event.id }))

    override suspend fun enqueue(item: QueuedUsageEvent) = lock.withLock {
        val e = item.event
        store.put(COLLECTION, e.id, OfflineJson.encodeToString(Row.serializer(),
            Row(item.userId, item.organizationId, e.id, e.sessionId, e.type.name, e.module?.name, e.occurredAt, e.durationSeconds)))
        all().dropLast(capacity).forEach { store.remove(COLLECTION, it.event.id) }
    }

    override suspend fun peek(userId: String, max: Int): List<QueuedUsageEvent> = lock.withLock {
        val items = all()
        val first = items.firstOrNull { it.userId == userId } ?: return@withLock emptyList()
        items.filter { it.userId == userId && it.organizationId == first.organizationId }.take(max)
    }

    override suspend fun remove(ids: Set<String>) = lock.withLock { ids.forEach { store.remove(COLLECTION, it) } }
    override suspend fun size(): Int = lock.withLock { store.list(COLLECTION).size }

    private companion object { const val COLLECTION = "usage-events" }
}
