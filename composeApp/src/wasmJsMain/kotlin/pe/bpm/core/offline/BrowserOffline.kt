@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.core.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.core.storage.BrowserDraftStorage

/**
 * Web, mejor esfuerzo: la misma API sobre IndexedDB del origen, `navigator.onLine` y los eventos
 * online/offline. No hay trabajo en segundo plano con la pestaña cerrada ni archivos durables.
 */
object BrowserOffline {
    fun platform() = OfflinePlatform(
        database = DocumentOfflineDatabase(BrowserDraftStorage()),
        connectivity = BrowserConnectivityMonitor(),
        files = InMemoryPrivateFileStore(durable = false),
        // Los videos sin conexión están deshabilitados en el navegador: sin archivos durables no hay descargas.
        videos = InMemoryPrivateFileStore(durable = false),
    )
}

class BrowserConnectivityMonitor : ConnectivityMonitor {
    private val state = MutableStateFlow(navigatorOnLine())
    override val online: StateFlow<Boolean> = state.asStateFlow()
    init { listenConnectivity { state.value = it } }
}

@JsFun("() => navigator.onLine !== false")
private external fun navigatorOnLine(): Boolean

@JsFun("""(callback) => {
    window.addEventListener('online', () => callback(true));
    window.addEventListener('offline', () => callback(false));
}""")
private external fun listenConnectivity(callback: (Boolean) -> Unit)
