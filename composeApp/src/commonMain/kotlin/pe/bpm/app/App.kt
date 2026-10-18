package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.trackUserActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.*
import pe.bpm.core.designsystem.BpmTheme
import pe.bpm.features.connection.presentation.ConnectionScreen
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.data.EphemeralSessionStore
import pe.bpm.features.identity.data.SessionStore
import pe.bpm.features.identity.presentation.IdentityScreen
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.workspace.presentation.*

@Composable
fun App(
    sessionStore: SessionStore = remember { EphemeralSessionStore() },
    onNavHostReady: suspend (NavController) -> Unit = {},
) {
    val owner = viewModel { GraphOwner(sessionStore) }
    val identity = viewModel<IdentityViewModel> { owner.graph.identityViewModel() }
    val connection = viewModel<ConnectionViewModel> { owner.graph.connectionViewModel() }
    val workspace = viewModel<WorkspaceViewModel> { owner.graph.workspaceViewModel() }
    val identityState by identity.state.collectAsStateWithLifecycle()
    val connectionState by connection.state.collectAsStateWithLifecycle()
    val workspaceState by workspace.state.collectAsStateWithLifecycle()
    val profile = identityState.profile
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    var pendingRoute by rememberSaveable { mutableStateOf(WorkspaceDestination.Home.route) }

    fun resetTo(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    LaunchedEffect(profile?.id) {
        workspace.reset()
        if (profile != null) {
            workspace.load()
            if (nav.currentDestination?.route == LOGIN) resetTo(pendingRoute)
        }
    }
    LaunchedEffect(profile?.organizations, profile?.platformRole) {
        if (profile != null && workspaceState.workspace != null) workspace.load()
    }
    LaunchedEffect(nav) { onNavHostReady(nav) }

    BpmTheme {
        Column(Modifier.fillMaxSize().trackUserActivity(identity::userActivity)) {
        if (profile != null && identityState.notice != null) Text(identityState.notice!!, Modifier.padding(12.dp))
        NavHost(navController = nav, startDestination = LOGIN, modifier = Modifier.weight(1f)) {
            composable(LOGIN) {
                if (profile == null) {
                    if (identityState.restoring) RestoringSession()
                    else Column {
                        if (identityState.canRetryRestore) TextButton(onClick = identity::restore) { Text("Reintentar sesión guardada") }
                        Box(Modifier.weight(1f)) { IdentityScreen(identityState, identity::login, identity::logout,
                        identity::verifySession,
                        onConnection = { connection.retry(); nav.navigate(CONNECTION) { launchSingleTop = true } },
                        googleSupported = identity.googleSupported, onGoogle = identity::loginWithGoogle,
                        onMode = identity::changeMode, onRegister = identity::register,
                        onRecover = identity::recover, onResend = identity::resend) }
                    }
                } else LaunchedEffect(profile.id) { resetTo(pendingRoute) }
            }
            composable(CONNECTION) {
                Column {
                    TextButton(onClick = { if (!nav.popBackStack()) resetTo(if (profile == null) LOGIN else WorkspaceDestination.Home.route) }) {
                        Text("Volver")
                    }
                    Box(Modifier.weight(1f)) { ConnectionScreen(connectionState, connection::retry) }
                }
            }
            WorkspaceDestination.entries.forEach { destination ->
                composable(destination.route) {
                    if (profile == null) {
                        // Direct URLs and history never bypass authentication, including after logout.
                        if (identityState.restoring) RestoringSession()
                        else LaunchedEffect(destination) {
                            if (entry?.destination?.route == destination.route) {
                                pendingRoute = destination.route
                                resetTo(LOGIN)
                            }
                        }
                    } else {
                        val available = workspaceDestinations(workspaceState.workspace?.role, profile.platformRole == "SUPER_ADMIN")
                        val waiting = workspaceState.loading && workspaceState.workspace == null
                        if (destination !in available && waiting) {
                            RestoringSession()
                        } else if (destination !in available) {
                            LaunchedEffect(destination, available) {
                                if (entry?.destination?.route == destination.route) resetTo(WorkspaceDestination.Home.route)
                            }
                        } else key(profile.id, workspaceState.selected) {
                            WorkspaceScreen(
                                viewer = WorkspaceViewer(profile.id, profile.displayName, profile.email, profile.platformRole == "SUPER_ADMIN"),
                                state = workspaceState,
                                section = destination,
                                onNavigate = { next -> nav.navigate(next.route) { launchSingleTop = true } },
                                onBack = { nav.popBackStack() },
                                canGoBack = nav.previousBackStackEntry != null,
                                onVerifySession = identity::verifySession,
                                sessionBusy = identityState.busy,
                                sessionMessage = identityState.errors.firstOrNull()?.message,
                                onSelect = { id ->
                                    if (!workspaceState.saving) {
                                        workspace.select(id)
                                        resetTo(WorkspaceDestination.Home.route)
                                    }
                                },
                                onRefresh = workspace::load,
                                onOrganization = workspace::organization,
                                onLocation = workspace::location,
                                onMember = workspace::member,
                                onUserActivity = identity::userActivity,
                                onClear = workspace::clearErrors,
                                onLogout = {
                                    pendingRoute = WorkspaceDestination.Home.route
                                    workspace.reset()
                                    identity.logout()
                                    resetTo(LOGIN)
                                },
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun RestoringSession() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Verificando acceso…", Modifier.padding(top = 16.dp))
    }
}

private const val LOGIN = "acceso"
private const val CONNECTION = "conexion"
