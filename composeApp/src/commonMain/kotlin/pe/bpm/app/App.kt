package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pe.bpm.core.designsystem.BpmTheme
import pe.bpm.features.connection.presentation.ConnectionScreen
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.presentation.IdentityScreen
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.workspace.presentation.WorkspaceViewModel
import pe.bpm.features.workspace.presentation.WorkspaceScreen
import pe.bpm.features.workspace.presentation.WorkspaceViewer

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
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.Identity.name) }
    val destination = AppDestination.valueOf(destinationName)
    val profile = identityState.profile
    BpmTheme {
        if (destination == AppDestination.Connection && profile == null) Column {
            TextButton(onClick = { destinationName = AppDestination.Identity.name }) { Text("Volver al inicio de sesión") }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                ConnectionScreen(state, connectionViewModel::retry)
            }
        } else if (profile != null) key(profile.id) {
            WorkspaceScreen(
                viewer = WorkspaceViewer(profile.id, profile.displayName, profile.email,
                    canCreateOrganization = profile.platformRole == "SUPER_ADMIN"),
                state = workspaceState,
                onSelect = workspaceViewModel::select,
                onRefresh = workspaceViewModel::load,
                onOrganization = workspaceViewModel::organization,
                onLocation = workspaceViewModel::location,
                onMember = workspaceViewModel::member,
                onClear = workspaceViewModel::clearErrors,
                onLogout = {
                    workspaceViewModel.reset()
                    identityViewModel.logout()
                    destinationName = AppDestination.Identity.name
                },
            )
        }
        else IdentityScreen(identityState, identityViewModel::login, identityViewModel::logout,
            identityViewModel::verifySession, onConnection = { destinationName = AppDestination.Connection.name; connectionViewModel.retry() },
            googleSupported = identityViewModel.googleSupported, onGoogle = identityViewModel::loginWithGoogle,
            onMode = identityViewModel::changeMode, onRegister = identityViewModel::register,
            onRecover = identityViewModel::recover, onResend = identityViewModel::resend)
    }
}

private enum class AppDestination { Identity, Connection }
