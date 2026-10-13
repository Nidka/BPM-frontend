package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.designsystem.BpmTheme
import pe.bpm.core.di.AppGraph
import pe.bpm.features.connection.presentation.ConnectionScreen
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.presentation.IdentityScreen
import pe.bpm.features.identity.presentation.IdentityViewModel

@Composable
fun App() {
    val connectionViewModel = viewModel<ConnectionViewModel> {
        val graph = AppGraph(platformAppConfig())
        graph.connectionViewModel().apply { addCloseable(graph) }
    }
    val state by connectionViewModel.state.collectAsStateWithLifecycle()
    val identityViewModel = viewModel<IdentityViewModel> {
        val graph = AppGraph(platformAppConfig())
        graph.identityViewModel().apply { addCloseable(graph) }
    }
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    var showConnection by remember { mutableStateOf(false) }
    BpmTheme {
        if (showConnection) Column {
            TextButton(onClick = { showConnection = false }) { Text("Volver al inicio de sesión") }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                ConnectionScreen(state, connectionViewModel::retry)
            }
        } else IdentityScreen(identityState, identityViewModel::login, identityViewModel::logout,
            identityViewModel::verifySession, onConnection = { showConnection = true; connectionViewModel.retry() },
            googleSupported = identityViewModel.googleSupported, onGoogle = identityViewModel::loginWithGoogle)
    }
}
