package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import pe.bpm.core.designsystem.trackUserActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import pe.bpm.core.designsystem.BpmTheme
import pe.bpm.core.designsystem.BpmCard
import pe.bpm.core.designsystem.BpmIcon
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.checklists.presentation.ChecklistCatalogScreen
import pe.bpm.features.checklists.presentation.ChecklistCatalogState
import pe.bpm.features.checklists.presentation.ChecklistCatalogViewModel
import pe.bpm.features.connection.presentation.ConnectionScreen
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.data.EphemeralSessionStore
import pe.bpm.features.identity.data.SessionStore
import pe.bpm.features.identity.presentation.IdentityScreen
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.identity.presentation.SessionExpirationNotice
import pe.bpm.features.workspace.presentation.*

@Composable
fun App(
    sessionStore: SessionStore = remember { EphemeralSessionStore() },
    onNavHostReady: suspend (NavController) -> Unit = {},
    onBackNavigation: (() -> Unit)? = null,
) {
    val owner = viewModel { GraphOwner(sessionStore) }
    val identity = viewModel<IdentityViewModel> { owner.graph.identityViewModel() }
    val connection = viewModel<ConnectionViewModel> { owner.graph.connectionViewModel() }
    val workspace = viewModel<WorkspaceViewModel> { owner.graph.workspaceViewModel() }
    val checklists = viewModel<ChecklistCatalogViewModel> { owner.graph.checklistCatalogViewModel() }
    val identityState by identity.state.collectAsStateWithLifecycle()
    val sessionStatus by identity.sessionStatus.collectAsStateWithLifecycle()
    val connectionState by connection.state.collectAsStateWithLifecycle()
    val workspaceState by workspace.state.collectAsStateWithLifecycle()
    val checklistState by checklists.state.collectAsStateWithLifecycle()
    val profile = identityState.profile
    var accessNotice by remember(profile?.id, workspaceState.selected) { mutableStateOf<String?>(null) }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    var pendingRoute by rememberSaveable { mutableStateOf(WorkspaceDestination.Home.route) }

    fun resetTo(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun navigateBack(fallback: String) {
        if (nav.previousBackStackEntry == null) resetTo(fallback)
        else if (onBackNavigation != null) onBackNavigation()
        else nav.popBackStack()
    }

    var loadedIdentityId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile?.id, profile?.organizations, profile?.platformRole) {
        if (loadedIdentityId != profile?.id || profile == null) {
            checklists.reset()
            workspace.reset()
        }
        loadedIdentityId = profile?.id
        if (profile != null) {
            workspace.load()
            if (nav.currentDestination?.route == LOGIN) resetTo(pendingRoute)
        }
    }
    LaunchedEffect(nav) { onNavHostReady(nav) }
    val currentWorkspace = workspaceState.workspace
    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, currentWorkspace?.role, profile?.platformRole) {
        val canRead = WorkspaceDestination.Operations in workspaceDestinations(currentWorkspace?.role, profile?.platformRole == "SUPER_ADMIN")
        if (profile != null && currentWorkspace != null && canRead)
            checklists.open(profile.id, currentWorkspace.organization.id)
        else checklists.reset()
    }

    BpmTheme {
        Box(Modifier.fillMaxSize().safeDrawingPadding().trackUserActivity(identity::userActivity)) {
        NavHost(navController = nav, startDestination = LOGIN, modifier = Modifier.fillMaxSize()) {
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
                        onRecover = identity::recover, onResend = identity::resend,
                        onConfirmEmail = identity::confirmEmail) }
                    }
                } else LaunchedEffect(profile.id) { resetTo(pendingRoute) }
            }
            composable(CONNECTION) {
                Column {
                    TextButton(onClick = { navigateBack(if (profile == null) LOGIN else WorkspaceDestination.Home.route) }) {
                        Text("Volver")
                    }
                    Box(Modifier.weight(1f)) { ConnectionScreen(connectionState, connection::retry) }
                }
            }
            WorkspaceDestination.entries.flatMap { destination ->
                val routes = when (destination) {
                    WorkspaceDestination.Operations -> listOf(destination.route, CHECKLIST_DETAIL)
                    WorkspaceDestination.Home -> listOf(destination.route, ORGANIZATION_PICKER)
                    else -> listOf(destination.route)
                }
                (routes + WorkspaceFormKind.entries.filter { it.destination == destination }.map { it.route })
                    .map { destination to it }
            }.forEach { (destination, route) ->
                composable(route, arguments = if (route == CHECKLIST_DETAIL)
                    listOf(navArgument("templateId") { type = NavType.StringType }) else emptyList()) { routeEntry ->
                    val formKind = WorkspaceFormKind.entries.firstOrNull { it.route == route }
                    val formRevision = remember(routeEntry.id) { workspaceState.revision }
                    fun closeForm() {
                        navigateBack(destination.route)
                    }
                    LaunchedEffect(workspaceState.revision) {
                        if (formKind != null && workspaceState.revision > formRevision && entry?.id == routeEntry.id) closeForm()
                    }
                    val templateId = if (route == CHECKLIST_DETAIL)
                        routeEntry.arguments?.let { NavType.StringType[it, "templateId"] } else null
                    val requestedRoute = templateId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                        ?.let { "control/plantilla/$it" } ?: route
                    if (profile == null) {
                        // Las URL directas y el historial deben comprobar la autenticación, incluso después de cerrar sesión.
                        if (identityState.restoring) RestoringSession()
                        else LaunchedEffect(destination) {
                            if (entry?.id == routeEntry.id) {
                                pendingRoute = requestedRoute
                                resetTo(LOGIN)
                            }
                        }
                    } else {
                        val available = workspaceDestinations(workspaceState.workspace?.role, profile.platformRole == "SUPER_ADMIN")
                        val waiting = workspaceState.loading && workspaceState.workspace == null
                        if ((destination !in available || formKind != null) && waiting) {
                            RestoringSession()
                        } else if (destination !in available || formKind?.isAllowed(profile.platformRole == "SUPER_ADMIN", workspaceState.workspace?.canManage == true) == false) {
                            LaunchedEffect(destination, available) {
                                if (entry?.id == routeEntry.id) {
                                    if (workspaceState.errors.none { it.code == "ACCESS_DENIED" })
                                        accessNotice = if (formKind != null)
                                            "Tu cuenta no tiene permiso para crear este registro en la organización actual. Puedes continuar en las secciones disponibles o solicitar acceso al administrador."
                                        else "Tu cuenta no tiene acceso a ${destination.label} en esta organización. Puedes continuar en las secciones disponibles o solicitar acceso al administrador."
                                    resetTo(WorkspaceDestination.Home.route)
                                }
                            }
                        } else key(profile.id, workspaceState.selected) {
                            WorkspaceScreen(
                                viewer = WorkspaceViewer(profile.id, profile.displayName, profile.email, profile.platformRole == "SUPER_ADMIN"),
                                state = workspaceState,
                                section = destination,
                                form = formKind,
                                choosingOrganization = route == ORGANIZATION_PICKER,
                                onOrganizationSearch = workspace::organizationSearch, onOrganizationNext = workspace::organizationNext,
                                onOrganizationPrevious = workspace::organizationPrevious, onOrganizationRetry = workspace::organizationRetry,
                                onPageSearch = workspace::pageSearch, onPageNext = workspace::pageNext,
                                onPagePrevious = workspace::pagePrevious, onPageRetry = workspace::pageRetry,
                                onChooseOrganization = { nav.navigate(ORGANIZATION_PICKER) { launchSingleTop = true } },
                                onOpenForm = { kind -> nav.navigate(kind.route) { launchSingleTop = true } },
                                onCloseForm = ::closeForm,
                                onNavigate = { next -> nav.navigate(next.route) { launchSingleTop = true } },
                                onVerifySession = identity::verifySession,
                                sessionBusy = identityState.busy,
                                sessionMessage = identityState.errors.firstOrNull()?.message,
                                sessionConfirmation = identityState.verificationMessage,
                                onSelect = { id ->
                                    if (!workspaceState.saving) {
                                        checklists.reset()
                                        workspace.select(id)
                                        resetTo(WorkspaceDestination.Home.route)
                                    }
                                },
                                onRefresh = {
                                    workspace.load()
                                    if (destination == WorkspaceDestination.Operations) checklists.refresh()
                                },
                                onOrganization = workspace::organization,
                                onLocation = workspace::location,
                                onMember = workspace::member,
                                onUserActivity = identity::userActivity,
                                sessionNotice = { SessionExpirationNotice(sessionStatus, identity::continueSession, identity::logout) },
                                noticeVisible = sessionStatus.warning != null || accessNotice != null,
                                screenNotice = {
                                    SessionExpirationNotice(sessionStatus, identity::continueSession, identity::logout)
                                    accessNotice?.let { message -> BpmCard(Modifier.fillMaxWidth()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            BpmIcon(BpmSymbol.Alert)
                                            Text("Acceso restringido", style = MaterialTheme.typography.titleMedium)
                                        }
                                        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                        TextButton(onClick = { accessNotice = null }) { Text("Entendido") }
                                    } }
                                    identityState.notice?.let { Text(it,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                                },
                                checklistContent = {
                                    // El estado solo se muestra si coincide con el usuario y la empresa autorizados.
                                    val scoped = checklistState.takeIf {
                                        it.userId == profile.id && it.organizationId == workspaceState.workspace?.organization?.id
                                    } ?: ChecklistCatalogState(loading = true)
                                    LaunchedEffect(templateId, scoped.userId, scoped.organizationId) {
                                        if (scoped.organizationId != null) checklists.showTemplate(templateId)
                                    }
                                    ChecklistCatalogScreen(scoped, templateId, checklists::refresh, checklists::search,
                                        onSelect = { id -> nav.navigate("control/plantilla/$id") { launchSingleTop = true } },
                                        onCloseDetail = {
                                            if (nav.previousBackStackEntry?.destination?.route == WorkspaceDestination.Operations.route)
                                                navigateBack(WorkspaceDestination.Operations.route)
                                            else resetTo(WorkspaceDestination.Operations.route)
                                        }, onUserActivity = identity::userActivity, onNext = checklists::next, onPrevious = checklists::previous)
                                },
                                onClear = workspace::clearErrors,
                                onLogout = {
                                    pendingRoute = WorkspaceDestination.Home.route
                                    checklists.reset()
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
private const val CHECKLIST_DETAIL = "control/plantilla/{templateId}"
private const val ORGANIZATION_PICKER = "organizaciones"
