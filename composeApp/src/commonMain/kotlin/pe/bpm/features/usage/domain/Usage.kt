package pe.bpm.features.usage.domain

import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tipos de evento del contrato de registro de uso (RF-27). */
enum class UsageEventType { SESSION_START, SESSION_END, MODULE_VISIT, ALERT_VIEWED, ALERT_ATTENDED }

/** Módulos de la app; los nombres coinciden con el contrato. */
enum class UsageModule { APP, HOME, CHECKLISTS, OPERATIONS, INCIDENTS, ALERTS, TRAINING, REPORTS, SETTINGS }

/**
 * Evento mínimo: sin metadatos, sin datos sanitarios y sin identificadores de personas (el servidor
 * fija usuario y empresa). [occurredAt] es un instante ISO-8601 UTC.
 */
data class UsageEvent(
    val id: String,
    val sessionId: String,
    val type: UsageEventType,
    val module: UsageModule?,
    val occurredAt: String,
    val durationSeconds: Int?,
)

/** Evento pendiente con el contexto con que debe enviarse; una persona nunca envía eventos de otra. */
data class QueuedUsageEvent(val userId: String, val organizationId: String, val event: UsageEvent)

sealed interface UsageSendResult {
    /** El servidor aceptó (o ya tenía) todo el lote. */
    data object Accepted : UsageSendResult
    /** Fallo temporal (red, 5xx, sesión por renovar): el lote se conserva y se reintenta después. */
    data object RetryLater : UsageSendResult
    /** El servidor rechazó el lote de forma definitiva (4xx): reintentar no ayudaría. */
    data object Rejected : UsageSendResult
}

/** Destino de los lotes. La implementación remota está en data; las pruebas usan un doble. */
interface UsageEventSink {
    suspend fun send(organizationId: String, events: List<UsageEvent>): UsageSendResult
}

/**
 * Cola de eventos pendientes. La versión en memoria es la actual; la persistencia offline-first
 * posterior solo debe implementar esta interfaz sin tocar el lote ni el rastreador.
 */
interface UsageEventQueue {
    suspend fun enqueue(item: QueuedUsageEvent)
    /** Hasta [max] eventos más antiguos de la misma persona y empresa que el primero que coincida con [userId]. */
    suspend fun peek(userId: String, max: Int): List<QueuedUsageEvent>
    suspend fun remove(ids: Set<String>)
    suspend fun size(): Int
}

/** Cola acotada en memoria: al llenarse descarta lo más antiguo (el uso es información de investigación, no crítica). */
class InMemoryUsageEventQueue(private val capacity: Int = 500) : UsageEventQueue {
    private val lock = Mutex()
    private val items = ArrayDeque<QueuedUsageEvent>()

    override suspend fun enqueue(item: QueuedUsageEvent) = lock.withLock {
        items.addLast(item)
        while (items.size > capacity) items.removeFirst()
    }

    override suspend fun peek(userId: String, max: Int): List<QueuedUsageEvent> = lock.withLock {
        val first = items.firstOrNull { it.userId == userId } ?: return@withLock emptyList()
        items.filter { it.userId == userId && it.organizationId == first.organizationId }.take(max)
    }

    override suspend fun remove(ids: Set<String>) = lock.withLock { items.removeAll { it.event.id in ids }; Unit }
    override suspend fun size(): Int = lock.withLock { items.size }
}

/** Resultado de un intento de envío. */
data class FlushOutcome(val sent: Int, val dropped: Int, val pending: Int, val retryLater: Boolean)

/**
 * Agrupa eventos en lotes de hasta [maxBatch] (límite del contrato) y los envía. Un fallo temporal
 * conserva los eventos; un rechazo definitivo y los eventos fuera de la ventana de 7 días del
 * servidor se descartan para no reintentar indefinidamente.
 */
class UsageBatcher(
    private val queue: UsageEventQueue,
    private val sink: UsageEventSink,
    private val nowMillis: () -> Long,
    private val maxBatch: Int = MAX_BATCH,
) {
    private val flushLock = Mutex()

    suspend fun record(userId: String, organizationId: String, event: UsageEvent) {
        queue.enqueue(QueuedUsageEvent(userId, organizationId, event))
    }

    suspend fun flush(userId: String): FlushOutcome = flushLock.withLock {
        var sent = 0
        var dropped = 0
        var retry = false
        while (true) {
            val batch = queue.peek(userId, maxBatch)
            if (batch.isEmpty()) break
            val stale = batch.filter { isStale(it.event) }
            if (stale.isNotEmpty()) {
                queue.remove(stale.map { it.event.id }.toSet()); dropped += stale.size
                continue
            }
            when (sink.send(batch.first().organizationId, batch.map { it.event })) {
                UsageSendResult.Accepted -> { queue.remove(batch.map { it.event.id }.toSet()); sent += batch.size }
                UsageSendResult.Rejected -> { queue.remove(batch.map { it.event.id }.toSet()); dropped += batch.size }
                UsageSendResult.RetryLater -> { retry = true; break }
            }
        }
        FlushOutcome(sent, dropped, queue.size(), retry)
    }

    /** El servidor acepta eventos de los últimos 7 días; se deja un margen de una hora. */
    private fun isStale(event: UsageEvent): Boolean {
        val at = try { Instant.parse(event.occurredAt).toEpochMilliseconds() } catch (_: Exception) { return true }
        return nowMillis() - at > MAX_AGE_MILLIS
    }

    companion object {
        const val MAX_BATCH = 50
        private const val MAX_AGE_MILLIS = 7L * 24 * 3_600_000 - 3_600_000
    }
}

/**
 * Registra la sesión y la navegación de una persona con membresía. Mantiene un solo contexto
 * (persona + empresa); al cambiarlo cierra la sesión anterior. Todos los métodos son seguros ante
 * llamadas repetidas; los fallos de red nunca se propagan (el lote queda en la cola).
 */
class UsageTracker(
    private val batcher: UsageBatcher,
    private val newId: () -> String,
    private val nowMillis: () -> Long,
) {
    private class Context(val userId: String, val organizationId: String, val sessionId: String) {
        var module: UsageModule? = null
        var enteredAt: Long = 0
    }

    private val lock = Mutex()
    private var context: Context? = null

    private fun iso(millis: Long) = Instant.fromEpochSeconds(millis / 1000).toString()

    private suspend fun add(c: Context, type: UsageEventType, module: UsageModule?, at: Long, duration: Int? = null) {
        batcher.record(c.userId, c.organizationId, UsageEvent(newId(), c.sessionId, type, module, iso(at), duration))
    }

    private suspend fun closeModule(c: Context, now: Long) {
        val module = c.module ?: return
        add(c, UsageEventType.MODULE_VISIT, module, c.enteredAt, ((now - c.enteredAt) / 1000).toInt().coerceIn(0, 86_400))
        c.module = null
    }

    private suspend fun finish(c: Context, now: Long, startedAt: Long) {
        closeModule(c, now)
        add(c, UsageEventType.SESSION_END, UsageModule.APP, now, ((now - startedAt) / 1000).toInt().coerceIn(0, 86_400))
    }

    private var startedAt: Long = 0

    /** Inicia la sesión de [userId] en [organizationId]; si ya es la vigente no hace nada. */
    suspend fun startSession(userId: String, organizationId: String) = lock.withLock {
        val current = context
        if (current != null && current.userId == userId && current.organizationId == organizationId) return@withLock
        val now = nowMillis()
        if (current != null) finish(current, now, startedAt)
        val fresh = Context(userId, organizationId, newId())
        context = fresh
        startedAt = now
        add(fresh, UsageEventType.SESSION_START, UsageModule.APP, now)
    }

    /** Registra la visita al módulo anterior (con su duración) y comienza la del nuevo. */
    suspend fun enterModule(module: UsageModule) = lock.withLock {
        val c = context ?: return@withLock
        if (c.module == module) return@withLock
        val now = nowMillis()
        closeModule(c, now)
        c.module = module
        c.enteredAt = now
    }

    suspend fun alertViewed() = lock.withLock { context?.let { add(it, UsageEventType.ALERT_VIEWED, UsageModule.ALERTS, nowMillis()) } ; Unit }
    suspend fun alertAttended() = lock.withLock { context?.let { add(it, UsageEventType.ALERT_ATTENDED, UsageModule.ALERTS, nowMillis()) }; Unit }

    /** Cierra la sesión actual (si existe) y devuelve la persona para poder enviarla. */
    suspend fun endSession(): String? = lock.withLock {
        val c = context ?: return@withLock null
        finish(c, nowMillis(), startedAt)
        context = null
        c.userId
    }

    /** Envía los eventos pendientes de [userId]. */
    suspend fun flush(userId: String): FlushOutcome = batcher.flush(userId)

    suspend fun activeUserId(): String? = lock.withLock { context?.userId }
}
