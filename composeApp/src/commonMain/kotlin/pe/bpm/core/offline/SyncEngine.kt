package pe.bpm.core.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Quien sabe enviar un tipo de operación; clasifica la respuesta en un [SendOutcome]. */
interface OutboxHandler {
    suspend fun send(operation: OutboxOperation): SendOutcome
    /** Se llama tras confirmar el envío; por ejemplo para invalidar cachés de lectura. */
    suspend fun onSent(operation: OutboxOperation) = Unit
}

data class SyncState(
    val running: Boolean = false,
    val lastRunAt: Long? = null,
    /** Última vez que una tanda terminó sin fallos de red. */
    val lastSuccessAt: Long? = null,
    val lastSent: Int = 0,
)

data class SyncReport(val sent: Int, val failed: Int, val skippedOffline: Boolean)

/** Envía la Outbox. Es seguro llamarlo desde varios disparadores: las tandas se serializan. */
interface SyncEngine {
    val state: StateFlow<SyncState>
    /** [ignoreBackoff] se usa al recuperar red o al pulsar «Sincronizar ahora». */
    suspend fun syncNow(ignoreBackoff: Boolean = false): SyncReport
}

class DefaultSyncEngine(
    private val outbox: OutboxQueue,
    private val handlers: Map<String, OutboxHandler>,
    private val connectivity: ConnectivityMonitor,
    private val clock: () -> Long,
) : SyncEngine {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SyncState())
    override val state: StateFlow<SyncState> = mutableState.asStateFlow()

    override suspend fun syncNow(ignoreBackoff: Boolean): SyncReport = mutex.withLock {
        if (!connectivity.online.value) return@withLock SyncReport(0, 0, skippedOffline = true)
        mutableState.value = mutableState.value.copy(running = true)
        var sent = 0
        var failed = 0
        var networkFailure = false
        try {
            outbox.recoverInterrupted()
            val tried = mutableSetOf<String>()
            while (tried.size < MAX_PER_RUN) {
                currentCoroutineContext().ensureActive()
                val all = outbox.snapshot()
                val now = clock()
                val next = all.firstOrNull { it.id !in tried && eligible(it, all, now, ignoreBackoff) } ?: break
                tried += next.id
                val claimed = outbox.markSending(next.id) ?: continue
                val outcome = try {
                    handlers[claimed.kind]?.send(claimed) ?: SendOutcome.Rejected("No hay un envío disponible para esta operación.", 0)
                } catch (cancelled: CancellationException) {
                    // El envío pudo haber salido: vuelve a PENDING y se repite con la misma clave.
                    withContext(NonCancellable) { outbox.recoverInterrupted() }
                    throw cancelled
                } catch (_: Exception) {
                    SendOutcome.Retry("No se pudo comunicar con el servidor.", network = true)
                }
                val saved = outbox.complete(claimed.id, outcome, clock())
                when (outcome) {
                    is SendOutcome.Sent -> { sent++; if (saved != null) handlers[claimed.kind]?.onSent(saved) }
                    is SendOutcome.Retry -> { failed++; if (outcome.network) { networkFailure = true; break } }
                    else -> failed++
                }
            }
            outbox.purgeSent()
        } finally {
            val now = clock()
            mutableState.value = SyncState(false, now, if (networkFailure) mutableState.value.lastSuccessAt else now, sent)
        }
        SyncReport(sent, failed, skippedOffline = false)
    }

    private fun eligible(op: OutboxOperation, all: List<OutboxOperation>, now: Long, ignoreBackoff: Boolean): Boolean {
        if (op.status != OutboxStatus.PENDING || op.attempts >= OutboxPolicy.MAX_ATTEMPTS) return false
        if (!ignoreBackoff && op.nextAttemptAt > now) return false
        // Una dependencia ausente se considera satisfecha (ya enviada y purgada).
        return op.dependsOn.all { dependency -> all.firstOrNull { it.id == dependency }?.resolved != false }
    }

    private companion object { const val MAX_PER_RUN = 500 }
}
