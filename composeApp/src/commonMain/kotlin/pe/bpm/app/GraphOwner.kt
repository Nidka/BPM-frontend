package pe.bpm.app

import androidx.lifecycle.ViewModel
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.di.AppGraph
import pe.bpm.features.identity.data.SessionStore
import pe.bpm.features.identity.data.GoogleAuthProvider
import pe.bpm.core.storage.DraftStorage

/** Conserva la infraestructura durante el ciclo de vida de la app, no por recomposición. */
internal class GraphOwner(sessionStore: SessionStore, googleAuthProvider: GoogleAuthProvider?, draftStorage: DraftStorage) : ViewModel() {
    val graph = AppGraph(platformAppConfig(), sessionStore = sessionStore, googleAuthProvider = googleAuthProvider, draftStorage = draftStorage)
    init { addCloseable(graph) }
}
