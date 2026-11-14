package pe.bpm.core.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado de red del dispositivo. Cada plataforma aporta su observador (ver OfflinePlatform). */
interface ConnectivityMonitor {
    /** true cuando hay una red con salida; no garantiza que el servidor responda. */
    val online: StateFlow<Boolean>
}

/** Monitor manual: valor por defecto de hosts sin observador y doble de pruebas. */
class ManualConnectivityMonitor(initial: Boolean = true) : ConnectivityMonitor {
    private val state = MutableStateFlow(initial)
    override val online: StateFlow<Boolean> = state.asStateFlow()
    fun set(value: Boolean) { state.value = value }
}
