package pe.bpm.features.usage.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pe.bpm.features.usage.domain.UsageModule
import pe.bpm.features.usage.domain.UsageTracker

/**
 * Conecta el rastreador de uso con el ciclo de vida de la app. Nunca lanza errores hacia la UI: el
 * registro es secundario y un fallo de red solo deja los eventos en la cola para reintentarlos.
 */
class UsageViewModel(
    private val tracker: UsageTracker,
    private val flushIntervalMillis: Long = 60_000L,
) : ViewModel() {
    private var timer: Job? = null
    private var userId: String? = null
    private var organizationId: String? = null
    private val seenAlerts = HashMap<String, Pair<Boolean, Boolean>>()

    /**
     * Define el contexto activo. [eligible] es falso sin membresía (SUPER_ADMIN sin empresa) o sin
     * sesión: en ese caso se cierra cualquier sesión previa y no se registra nada.
     */
    fun configure(userId: String?, organizationId: String?, eligible: Boolean) {
        val target = if (eligible && userId != null && organizationId != null) userId to organizationId else null
        if (target?.first == this.userId && target?.second == this.organizationId) return
        val previous = this.userId
        this.userId = target?.first
        this.organizationId = target?.second
        seenAlerts.clear()
        viewModelScope.launch {
            // Primero las operaciones locales (rápidas y en orden); el envío por red va después.
            tracker.endSession()
            if (target != null) tracker.startSession(target.first, target.second)
            previous?.let { tracker.flush(it) }
            if (target != null) tracker.flush(target.first)
        }
        timer?.cancel()
        timer = if (target == null) null else viewModelScope.launch {
            while (true) {
                delay(flushIntervalMillis)
                tracker.flush(target.first)
            }
        }
    }

    /** Se llama al cambiar de pantalla: registra la visita anterior y envía lo pendiente. */
    fun moduleChanged(module: UsageModule?) {
        val user = userId ?: return
        viewModelScope.launch {
            if (module != null) tracker.enterModule(module)
            tracker.flush(user)
        }
    }

    /**
     * Observa una alerta abierta. La primera observación solo fija la línea base; un cambio posterior de
     * "vista" o "atendida" genera el evento. Así reabrir una alerta ya atendida no cuenta de nuevo.
     */
    fun alertObserved(alertId: String, viewed: Boolean, attended: Boolean) {
        if (userId == null) return
        val before = seenAlerts.put(alertId, viewed to attended) ?: return
        viewModelScope.launch {
            if (viewed && !before.first) tracker.alertViewed()
            if (attended && !before.second) tracker.alertAttended()
        }
    }

    /** Cierra la sesión y envía lo pendiente (cierre de sesión de la persona). */
    fun endSession() {
        val user = userId ?: return
        userId = null; organizationId = null
        timer?.cancel(); timer = null
        seenAlerts.clear()
        viewModelScope.launch {
            tracker.endSession()
            tracker.flush(user)
        }
    }

    /** Envía lo pendiente al volver la app al primer plano. */
    fun flushNow() {
        val user = userId ?: return
        viewModelScope.launch { tracker.flush(user) }
    }
}
