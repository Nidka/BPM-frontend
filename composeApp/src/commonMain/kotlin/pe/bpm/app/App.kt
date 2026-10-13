package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.designsystem.BpmTheme
import pe.bpm.core.di.AppGraph
import pe.bpm.features.connection.presentation.ConnectionScreen
import pe.bpm.features.connection.presentation.ConnectionViewModel

@Composable
fun App() {
    val viewModel = viewModel<ConnectionViewModel> {
        val graph = AppGraph(platformAppConfig())
        graph.connectionViewModel().apply { addCloseable(graph) }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BpmTheme { ConnectionScreen(state, viewModel::retry) }
}
