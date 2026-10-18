package pe.bpm.app

import androidx.lifecycle.ViewModel
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.di.AppGraph

/** Owns infrastructure for one application lifecycle, never for a recomposition. */
internal class GraphOwner : ViewModel() {
    val graph = AppGraph(platformAppConfig())
    init { addCloseable(graph) }
}
