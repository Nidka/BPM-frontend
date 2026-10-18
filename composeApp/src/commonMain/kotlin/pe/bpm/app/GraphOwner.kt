package pe.bpm.app

import androidx.lifecycle.ViewModel
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.di.AppGraph
import pe.bpm.features.identity.data.SessionStore

/** Owns infrastructure for one application lifecycle, never for a recomposition. */
internal class GraphOwner(sessionStore: SessionStore) : ViewModel() {
    val graph = AppGraph(platformAppConfig(), sessionStore = sessionStore)
    init { addCloseable(graph) }
}
