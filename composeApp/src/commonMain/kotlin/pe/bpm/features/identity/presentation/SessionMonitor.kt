package pe.bpm.features.identity.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.features.identity.domain.*
import kotlin.time.Clock

enum class SessionExpiryReason { INACTIVITY, ABSOLUTE }
data class SessionWarning(val reason: SessionExpiryReason, val secondsRemaining: Long)
data class SessionStatus(val warning: SessionWarning? = null, val continuing: Boolean = false, val error: String? = null)

/** Coordina avisos y actividad real. Las fechas y la autorización siempre proceden del servidor. */
class SessionMonitor(
    private val observeWindow: ObserveSessionWindow,
    private val recordActivity: RecordSessionActivity,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {
    // Da tiempo para guardar trabajo y confirmar presencia dentro del plazo del servidor.
    private val warningSeconds = 5 * 60L
    private val mutable = MutableStateFlow(SessionStatus())
    val state = mutable.asStateFlow()
    private var window: SessionWindow? = null
    private var activity: Job? = null
    private lateinit var scope: CoroutineScope
    private var hasSession: () -> Boolean = { false }
    private var onExpired: (SessionExpiryReason) -> Unit = {}

    fun bind(scope: CoroutineScope, hasSession: () -> Boolean, onExpired: (SessionExpiryReason) -> Unit) {
        this.scope = scope
        this.hasSession = hasSession
        this.onExpired = onExpired
        scope.launch { observeWindow().collect { window = it; update() } }
        scope.launch { while (true) { delay(1_000); update() } }
    }

    fun reset() {
        activity?.cancel()
        activity = null
        mutable.value = SessionStatus()
    }

    /** Recalcula al recuperar el primer plano; no registra actividad ni renueva la sesión. */
    fun recheck() = update()

    private fun update() {
        val current = window
        if (!hasSession() || current == null) { mutable.value = SessionStatus(); return }
        val reason = if (current.absoluteExpiresAt <= current.idleExpiresAt) SessionExpiryReason.ABSOLUTE else SessionExpiryReason.INACTIVITY
        val remaining = current.expiresAt - nowSeconds()
        if (remaining <= 0) {
            // Nunca prolongar localmente un plazo vencido mientras una solicitud está pendiente.
            reset()
            onExpired(reason)
        } else mutable.value = mutable.value.copy(warning = if (remaining <= warningSeconds) SessionWarning(reason, remaining) else null,
            error = mutable.value.error.takeIf { remaining <= warningSeconds })
    }

    fun userActivity() {
        if (!hasSession() || activity?.isActive == true) return
        update()
        if (!hasSession() || mutable.value.warning?.reason == SessionExpiryReason.ABSOLUTE) return
        activity = scope.launch {
            mutable.value = mutable.value.copy(continuing = mutable.value.warning != null, error = null)
            val result = recordActivity()
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(continuing = false,
                error = (result as? IdentityResult.Failure)?.errors?.firstOrNull()?.message)
            update()
        }
    }

    fun continueSession() {
        // El mismo recorrido sirve para el botón y las entradas reales; nunca lo ejecuta el temporizador.
        if (mutable.value.warning?.reason == SessionExpiryReason.INACTIVITY) userActivity()
    }
}
