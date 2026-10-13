package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
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
import pe.bpm.features.workspace.presentation.WorkspaceViewModel
import pe.bpm.features.workspace.presentation.WorkspaceScreen

private class GraphOwner : ViewModel() {
    val graph = AppGraph(platformAppConfig())
    init { addCloseable(graph) }
}

@Composable
fun App() {
    val owner = viewModel { GraphOwner() }
    val connectionViewModel = viewModel<ConnectionViewModel> {
        owner.graph.connectionViewModel()
    }
    val state by connectionViewModel.state.collectAsStateWithLifecycle()
    val identityViewModel = viewModel<IdentityViewModel> {
        owner.graph.identityViewModel()
    }
    val identityState by identityViewModel.state.collectAsStateWithLifecycle()
    val workspaceViewModel = viewModel<WorkspaceViewModel> { owner.graph.workspaceViewModel() }
    val workspaceState by workspaceViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(identityState.profile?.id) {
        workspaceViewModel.reset()
        if (identityState.profile != null) workspaceViewModel.load()
    }
    var showConnection by remember { mutableStateOf(false) }
    BpmTheme {
        if (showConnection) Column {
            TextButton(onClick = { showConnection = false }) { Text("Volver al inicio de sesión") }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                ConnectionScreen(state, connectionViewModel::retry)
            }
        } else if (identityState.profile != null) WorkspaceScreen(identityState.profile!!, workspaceState,
            workspaceViewModel::select, workspaceViewModel::load, workspaceViewModel::organization,
            workspaceViewModel::location, workspaceViewModel::member, workspaceViewModel::clearErrors,
            onLogout = { workspaceViewModel.reset(); identityViewModel.logout() })
        else IdentityScreen(identityState, identityViewModel::login, identityViewModel::logout,
            identityViewModel::verifySession, onConnection = { showConnection = true; connectionViewModel.retry() },
            googleSupported = identityViewModel.googleSupported, onGoogle = identityViewModel::loginWithGoogle,
            onMode = identityViewModel::changeMode, onRegister = identityViewModel::register,
            onRecover = identityViewModel::recover, onResend = identityViewModel::resend)
    }
}
