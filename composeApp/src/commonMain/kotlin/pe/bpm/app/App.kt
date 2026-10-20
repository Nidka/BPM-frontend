package pe.bpm.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import pe.bpm.core.designsystem.BpmSecondaryButton
import pe.bpm.core.designsystem.BpmTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import pe.bpm.core.storage.DraftStorage
import pe.bpm.core.storage.UnavailableDraftStorage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.presentation.OnAppForeground
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.*
import pe.bpm.core.designsystem.RaisedButton
import pe.bpm.core.designsystem.BpmCard
import pe.bpm.core.designsystem.BpmIcon
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.checklists.presentation.ChecklistTemplateEditorViewModel
import pe.bpm.features.checklists.presentation.ChecklistTemplateEditorScreen
import pe.bpm.features.checklists.presentation.TemplateEditorStep
import pe.bpm.features.checklists.presentation.ChecklistCatalogScreen
import pe.bpm.features.checklists.presentation.ChecklistCatalogState
import pe.bpm.features.checklists.presentation.ChecklistCatalogViewModel
import pe.bpm.features.checklists.presentation.ChecklistRunViewModel
import pe.bpm.features.checklists.domain.ChecklistFailure
import pe.bpm.features.connection.presentation.ConnectionScreen
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.data.EphemeralSessionStore
import pe.bpm.features.identity.data.SessionStore
import pe.bpm.features.identity.data.GoogleAuthProvider
import pe.bpm.features.identity.presentation.IdentityScreen
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.workspace.presentation.*
import pe.bpm.features.workspace.domain.Organization
import pe.bpm.features.workspace.domain.Location

@Composable
fun App(
    sessionStore: SessionStore = remember { EphemeralSessionStore() },
    onNavHostReady: suspend (NavController) -> Unit = {},
    onBackNavigation: (() -> Unit)? = null,
    googleAuthProvider: GoogleAuthProvider? = null,
    draftStorage: DraftStorage = remember { UnavailableDraftStorage() },
) {
    val owner = viewModel { GraphOwner(sessionStore, googleAuthProvider, draftStorage) }
    val identity = viewModel<IdentityViewModel> { owner.graph.identityViewModel() }
    val workspace = viewModel<WorkspaceViewModel> { owner.graph.workspaceViewModel() }
    val checklists = viewModel<ChecklistCatalogViewModel> { owner.graph.checklistCatalogViewModel() }
    val templateEditor = viewModel<ChecklistTemplateEditorViewModel> { owner.graph.checklistTemplateEditorViewModel() }
    val formDrafts = viewModel<WorkspaceFormViewModel> { owner.graph.workspaceFormViewModel() }
    val formDraftState by formDrafts.state.collectAsStateWithLifecycle()
    val runs = viewModel<ChecklistRunViewModel> { owner.graph.checklistRunViewModel() }
    val runState by runs.state.collectAsStateWithLifecycle()
    val templateEditorState by templateEditor.state.collectAsStateWithLifecycle()
    val identityState by identity.state.collectAsStateWithLifecycle()
    val sessionStatus by identity.sessionStatus.collectAsStateWithLifecycle()
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
            templateEditor.reset()
            runs.reset()
            formDrafts.reset()
            workspace.reset()
        }
        loadedIdentityId = profile?.id
        if (profile != null) {
            workspace.load()
            if (nav.currentDestination?.route == LOGIN) resetTo(pendingRoute)
        }
    }
    LaunchedEffect(nav) { onNavHostReady(nav) }
    // Solo las vistas de consulta se revalidan al entrar o volver al primer plano.
    // No hay temporizador de consultas ni recarga de formularios o borradores.
    val primaryRoute = WorkspaceDestination.entries.any { it.route == entry?.destination?.route }
    fun refreshVisibleData() {
        if (identity.state.value.profile != null && primaryRoute && identity.sessionStatus.value.warning == null) {
            workspace.refreshIfStale()
            if (entry?.destination?.route == WorkspaceDestination.Operations.route) checklists.refreshIfStale()
        }
    }
    OnAppForeground {
        identity.onForeground()
        if (identity.state.value.profile != null) refreshVisibleData()
    }
    LaunchedEffect(entry?.id, profile?.id) { refreshVisibleData() }
    val currentWorkspace = workspaceState.workspace
    LaunchedEffect(profile?.id, workspaceState.selected, currentWorkspace?.role, workspaceState.errors) {
        if (runState.userId != null && (runState.userId != profile?.id || runState.organizationId != workspaceState.selected ||
                WorkspaceDestination.Operations !in workspaceDestinations(currentWorkspace?.role, profile?.platformRole == "SUPER_ADMIN") ||
                workspaceState.errors.any { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") })) runs.reset()
    }
    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, currentWorkspace?.role, profile?.platformRole) {
        val canRead = currentWorkspace?.canManage == true || WorkspaceDestination.Operations in workspaceDestinations(currentWorkspace?.role, profile?.platformRole == "SUPER_ADMIN")
        if (profile != null && currentWorkspace != null && canRead)
            checklists.open(profile.id, currentWorkspace.organization.id)
        else checklists.reset()
    }

    LaunchedEffect(profile?.id, workspaceState.selected, currentWorkspace?.role, workspaceState.errors) {
        if (profile == null || templateEditorState.userId != null && templateEditorState.userId != profile.id ||
            templateEditorState.organizationId != null && templateEditorState.organizationId != workspaceState.selected ||
            currentWorkspace != null && !currentWorkspace.canManage || workspaceState.errors.any { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") }) templateEditor.reset()
    }
    LaunchedEffect(templateEditorState.published?.id) {
        if (templateEditorState.published != null) checklists.refresh()
    }
    LaunchedEffect(checklistState.failure, checklistState.userId, checklistState.organizationId) {
        if (checklistState.userId == profile?.id && checklistState.organizationId == workspaceState.selected &&
            checklistState.failure in setOf(ChecklistFailure.ACCESS_DENIED, ChecklistFailure.SESSION_REQUIRED)) templateEditor.reset()
    }

    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, currentWorkspace?.role) {
        if (profile != null && currentWorkspace != null && WorkspaceDestination.Operations in workspaceDestinations(currentWorkspace.role, profile.platformRole == "SUPER_ADMIN"))
            runs.open(profile.id, currentWorkspace.organization.id)
    }

    SessionContent(sessionStatus, identity::userActivity, identity::continueSession, identity::logout) {
        NavHost(navController = nav, startDestination = LOGIN, modifier = Modifier.fillMaxSize(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None }) {
            composable(LOGIN) {
                if (profile == null) {
                    if (identityState.restoring) RestoringSession()
                    else Column {
                        if (identityState.canRetryRestore) BpmTextButton(onClick = identity::restore) { Text("Reintentar sesión guardada") }
                        Box(Modifier.weight(1f)) { IdentityScreen(identityState, identity::login, identity::logout,
                        identity::verifySession,
                        googleSupported = identity.googleSupported, onGoogle = identity::loginWithGoogle,
                        onMode = identity::changeMode, onRegister = identity::register,
                        onRecover = identity::recover, onResend = identity::resend,
                        onConfirmEmail = identity::confirmEmail) }
                    }
                } else LaunchedEffect(profile.id) { resetTo(pendingRoute) }
            }
            composable(CONNECTION) {
                val connection = viewModel<ConnectionViewModel> { owner.graph.connectionViewModel() }
                val connectionState by connection.state.collectAsStateWithLifecycle()
                Column {
                    BpmTextButton(onClick = { navigateBack(if (profile == null) LOGIN else WorkspaceDestination.Home.route) }) {
                        Text("Volver")
                    }
                    Box(Modifier.weight(1f)) { ConnectionScreen(connectionState, connection::retry) }
                }
            }
            WorkspaceDestination.entries.flatMap { destination ->
                val routes = when (destination) {
                    WorkspaceDestination.Operations -> listOf(destination.route, CHECKLIST_DETAIL, CHECKLIST_START, CHECKLIST_RUN)
                    WorkspaceDestination.Home -> listOf(destination.route, ORGANIZATION_PICKER)
                    WorkspaceDestination.Company -> listOf(destination.route, TEMPLATE_MANAGEMENT, TEMPLATE_CREATE, TEMPLATE_MANAGEMENT_DETAIL)
                    else -> listOf(destination.route)
                }
                (routes + WorkspaceFormKind.entries.filter { it.destination == destination }.map { it.route })
                    .map { destination to it }
            }.forEach { (destination, route) ->
                composable(route, arguments = if (route in listOf(CHECKLIST_DETAIL, TEMPLATE_MANAGEMENT_DETAIL, CHECKLIST_START))
                    listOf(navArgument("templateId") { type = NavType.StringType }) else if (route == CHECKLIST_RUN)
                    listOf(navArgument("runId") { type = NavType.StringType }) else emptyList()) { routeEntry ->
                    val formKind = WorkspaceFormKind.entries.firstOrNull { it.route == route }
                    val formRevision = remember(routeEntry.id) { workspaceState.revision }
                    val managingTemplates = route in listOf(TEMPLATE_MANAGEMENT, TEMPLATE_CREATE, TEMPLATE_MANAGEMENT_DETAIL)
                    val editingTemplate = route == TEMPLATE_CREATE
                    val running = route == CHECKLIST_START || route == CHECKLIST_RUN
                    var runExit by remember(routeEntry.id) { mutableStateOf<(() -> Unit)?>(null) }
                    fun leaveRun(action: () -> Unit) {
                        if (running && (runState.dirty || runState.pending || runState.busy)) runExit = action
                        else action()
                    }
                    fun closeForm() {
                        if (editingTemplate && templateEditorState.step == TemplateEditorStep.Review && templateEditorState.editable) templateEditor.backToEdit()
                        else leaveRun { navigateBack(if (managingTemplates) TEMPLATE_MANAGEMENT else destination.route) }
                    }
                    LaunchedEffect(workspaceState.revision) {
                        if (formKind != null && workspaceState.revision > formRevision && entry?.id == routeEntry.id) formDrafts.complete { closeForm() }
                    }
                    LaunchedEffect(workspaceState.errors) {
                        if (formKind != null && entry?.id == routeEntry.id) formDrafts.onSaveFailure(workspaceState.errors)
                    }
                    val templateId = if (route in listOf(CHECKLIST_DETAIL, TEMPLATE_MANAGEMENT_DETAIL, CHECKLIST_START))
                        routeEntry.arguments?.let { NavType.StringType[it, "templateId"] } else null
                    val runId = if (route == CHECKLIST_RUN) routeEntry.arguments?.let { NavType.StringType[it, "runId"] } else null
                    val requestedRoute = templateId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                        ?.let { if (route == TEMPLATE_MANAGEMENT_DETAIL) "empresa/plantillas/detalle/$it" else "control/plantilla/$it" + if (route == CHECKLIST_START) "/iniciar" else "" }
                        ?: runId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }?.let { "control/ejecucion/$it" } ?: route
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
                        if ((destination !in available || formKind != null || managingTemplates) && waiting) {
                            RestoringSession()
                        } else if (destination !in available || managingTemplates && workspaceState.workspace?.canManage != true || formKind?.isAllowed(profile.platformRole == "SUPER_ADMIN", workspaceState.workspace?.canManage == true) == false) {
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
                            LaunchedEffect(formKind, profile.id, workspaceState.selected) {
                                if (formKind != null) formDrafts.open(profile.id, workspaceState.selected.orEmpty(), formKind)
                            }
                            LaunchedEffect(editingTemplate, profile.id, workspaceState.workspace?.organization?.id) {
                                if (editingTemplate && workspaceState.workspace?.canManage == true)
                                    templateEditor.open(profile.id, workspaceState.workspace!!.organization.id)
                            }
                            val managementContent: (@Composable () -> Unit)? = if (!managingTemplates) null else {
                                {
                                    if (editingTemplate) {
                                        ChecklistTemplateEditorScreen(templateEditorState, templateEditor::name, templateEditor::area, templateEditor::description,
                                            templateEditor::question, templateEditor::type, templateEditor::addQuestion, templateEditor::removeQuestion,
                                            templateEditor::move, templateEditor::undoRemove, templateEditor::clearUndo, templateEditor::review, templateEditor::confirmPublication, templateEditor::backToEdit,
                                            templateEditor::requestDiscard, templateEditor::keepEditing,
                                            onConfirmDiscard = { templateEditor.discard { navigateBack(TEMPLATE_MANAGEMENT) } },
                                            onPublished = { id -> workspace.load(); nav.navigate("empresa/plantillas/detalle/$id") { popUpTo(TEMPLATE_CREATE) { inclusive = true } } },
                                            onClose = { if (templateEditorState.published != null) workspace.load(); nav.navigate(TEMPLATE_MANAGEMENT) { popUpTo(TEMPLATE_MANAGEMENT) { inclusive = true }; launchSingleTop = true } },
                                            onProtectDraft = templateEditor::protectDraft, onSuggestion = templateEditor::useSuggestion)
                                    } else {
                                        val scopedManagement = checklistState.takeIf { it.userId == profile.id && it.organizationId == workspaceState.selected } ?: ChecklistCatalogState(loading = true)
                                        if (templateId == null && scopedManagement.failure !in setOf(ChecklistFailure.ACCESS_DENIED, ChecklistFailure.SESSION_REQUIRED)) {
                                            Text("Crea una primera versión y revisa las preguntas antes de publicarla.")
                                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                                RaisedButton("Crear plantilla", {
                                                    if (templateEditorState.published != null) templateEditor.reset()
                                                    nav.navigate(TEMPLATE_CREATE) { launchSingleTop = true }
                                                }, Modifier.widthIn(max = if (maxWidth >= 600.dp) 320.dp else maxWidth).fillMaxWidth())
                                            }
                                        }
                                        LaunchedEffect(templateId, checklistState.userId, checklistState.organizationId) {
                                            if (checklistState.organizationId == workspaceState.selected) checklists.showTemplate(templateId)
                                        }
                                        ChecklistCatalogScreen(scopedManagement, templateId, checklists::refresh, checklists::search,
                                            onSelect = { id -> nav.navigate("empresa/plantillas/detalle/$id") { launchSingleTop = true } }, onUserActivity = identity::userActivity,
                                            onNext = checklists::next, onPrevious = checklists::previous, management = true)
                                    }
                                }
                            }
                            WorkspaceScreen(
                                viewer = WorkspaceViewer(profile.id, profile.displayName, profile.email, profile.platformRole == "SUPER_ADMIN"),
                                state = workspaceState,
                                section = destination,
                                onManageTemplates = { nav.navigate(TEMPLATE_MANAGEMENT) { launchSingleTop = true } },
                                secondaryContent = if (running) ({
                                    if (runExit != null) BpmCard(Modifier.fillMaxWidth()) {
                                        Text(if (runState.pending || runState.busy) "Hay una operación sin confirmar" else "Hay respuestas sin guardar")
                                        Text("Puedes conservar el borrador en este dispositivo y retomarlo después con tu misma cuenta.")
                                        BpmSecondaryButton(onClick = { val exit = runExit; runs.leave { runExit = null; exit?.invoke() } }) { Text("Conservar borrador y salir") }
                                        if (!runState.pending && !runState.busy) BpmSecondaryButton(onClick = { val exit = runExit; runExit = null; runs.discard { exit?.invoke() } }) { Text("Descartar cambios y salir") }
                                        BpmTextButton(onClick = { runExit = null }) { Text("Continuar con el control") }
                                    }
                                    ChecklistRunContent(profile.id, workspaceState, checklistState, runState,
                                    runs, workspace, checklists, route == CHECKLIST_START, templateId, runId,
                                    onStarted = { id -> nav.navigate("control/ejecucion/$id") { popUpTo(CHECKLIST_START) { inclusive = true }; launchSingleTop = true } },
                                    onBack = { nav.navigate(WorkspaceDestination.Operations.route) { popUpTo(WorkspaceDestination.Operations.route) { inclusive = true }; launchSingleTop = true } }) }) else managementContent,
                                secondaryEditor = editingTemplate,
                                secondaryTitle = if (running) "Control de tu sede" else if (managingTemplates) "Plantillas de control" else if (route == CHECKLIST_DETAIL) "Detalle del control" else null,
                                secondaryBusy = templateEditorState.publishing || running && (runState.busy || runState.pending),
                                secondaryScrollKey = if (running) "${runState.reviewing}:${runState.failure}:${runState.run?.status}:${runState.busy}:${runExit != null}" else null,
                                formDraft = formDraftState, onSubmitForm = { formDrafts.submit { draft ->
                                    when (formKind) {
                                        WorkspaceFormKind.Organization -> workspace.organization(Organization(draft.id, draft.name, draft.trade, draft.business, draft.tax, draft.contactName, draft.phone, draft.email))
                                        WorkspaceFormKind.Location -> workspace.location(Location(draft.id, draft.name, draft.address, draft.district, draft.phone))
                                        WorkspaceFormKind.Member -> workspace.member(draft.email, draft.role, if (draft.role == "ADMIN") emptyList() else draft.locations.toList())
                                        null -> Unit
                                    }
                                } }, onFormCheck = { workspace.clearErrors(); formDrafts.checkAccount() }, onFormEdit = { change -> workspace.clearErrors(); formDrafts.edit(change) }, onFormRetry = formDrafts::retry, onFormDiscard = { formDrafts.discard { closeForm() } },
                                form = formKind,
                                choosingOrganization = route == ORGANIZATION_PICKER,
                                onOrganizationSearch = workspace::organizationSearch, onOrganizationNext = workspace::organizationNext,
                                onOrganizationPrevious = workspace::organizationPrevious, onOrganizationRetry = workspace::organizationRetry,
                                onPageSearch = workspace::pageSearch, onPageNext = workspace::pageNext,
                                onPagePrevious = workspace::pagePrevious, onPageRetry = workspace::pageRetry,
                                onChooseOrganization = { nav.navigate(ORGANIZATION_PICKER) { launchSingleTop = true } },
                                onOpenForm = { kind -> nav.navigate(kind.route) { launchSingleTop = true } },
                                onCloseForm = ::closeForm,
                                onNavigate = { next -> leaveRun { nav.navigate(next.route) { launchSingleTop = true } } },
                                onVerifySession = identity::verifySession,
                                sessionBusy = identityState.busy,
                                sessionMessage = identityState.errors.firstOrNull()?.message,
                                sessionConfirmation = identityState.verificationMessage,
                                onSelect = { id ->
                                    if (!workspaceState.saving) {
                                        checklists.reset()
                                        templateEditor.reset()
                                        runs.reset()
                                        workspace.select(id)
                                        resetTo(WorkspaceDestination.Home.route)
                                    }
                                },
                                onRefresh = {
                                    workspace.load()
                                    if (destination == WorkspaceDestination.Operations) checklists.refresh()
                                },
                                onUserActivity = identity::userActivity,
                                noticeVisible = accessNotice != null,
                                screenNotice = {
                                    if (destination in listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations) && !running && runState.resumeId != null && runState.userId == profile.id && runState.organizationId == workspaceState.selected) {
                                        BpmCard(Modifier.fillMaxWidth()) {
                                            Text("Tienes un control pendiente en este dispositivo")
                                            BpmSecondaryButton(onClick = { nav.navigate("control/ejecucion/${runState.resumeId}") { launchSingleTop = true } }) { Text("Retomar control") }
                                        }
                                    }
                                    accessNotice?.let { message -> BpmCard(Modifier.fillMaxWidth()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            BpmIcon(BpmSymbol.Alert)
                                            Text("Acceso restringido", style = MaterialTheme.typography.titleMedium)
                                        }
                                        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                        BpmTextButton(onClick = { accessNotice = null }) { Text("Entendido") }
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
                                        onUserActivity = identity::userActivity, onNext = checklists::next, onPrevious = checklists::previous,
                                        onStart = { id -> runs.reset(); nav.navigate("control/plantilla/$id/iniciar") { launchSingleTop = true } })
                                },
                                onClear = workspace::clearErrors,
                                onLogout = {
                                    pendingRoute = WorkspaceDestination.Home.route
                                    checklists.reset()
                                    templateEditor.reset()
                                    runs.reset()
                                    formDrafts.reset()
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
private const val CHECKLIST_START = "control/plantilla/{templateId}/iniciar"
private const val CHECKLIST_RUN = "control/ejecucion/{runId}"
private const val ORGANIZATION_PICKER = "organizaciones"

private const val TEMPLATE_MANAGEMENT = "empresa/plantillas"
private const val TEMPLATE_CREATE = "empresa/plantillas/nueva"
private const val TEMPLATE_MANAGEMENT_DETAIL = "empresa/plantillas/detalle/{templateId}"
