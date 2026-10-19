package pe.bpm.app

import androidx.lifecycle.ViewModel
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.di.AppGraph
import pe.bpm.features.identity.data.SessionStore
import pe.bpm.features.identity.data.GoogleAuthProvider

/** Conserva la infraestructura durante el ciclo de vida de la app, no por recomposición. */
internal class GraphOwner(sessionStore: SessionStore, googleAuthProvider: GoogleAuthProvider?) : ViewModel() {
    val graph = AppGraph(platformAppConfig(), sessionStore = sessionStore, googleAuthProvider = googleAuthProvider)
    init { addCloseable(graph) }
}
