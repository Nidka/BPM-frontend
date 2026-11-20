package pe.bpm.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

import pe.bpm.core.designsystem.BpmPattern
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import pe.bpm.core.presentation.supportsNativeModalWindows
import pe.bpm.core.offline.LocalOnline
import pe.bpm.core.offline.LogoutDialog
import pe.bpm.core.offline.LogoutGate
import pe.bpm.core.offline.OfflinePlatform
import pe.bpm.core.offline.OfflineStatusBar
import pe.bpm.core.offline.OfflineViewModel
import pe.bpm.core.offline.PendingChangesScreen
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
import pe.bpm.core.designsystem.BpmListDetail
import pe.bpm.core.designsystem.BpmActionTile
import pe.bpm.core.designsystem.BpmPanelLayout
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.checklists.presentation.ChecklistTemplateEditorViewModel
import pe.bpm.features.checklists.presentation.ChecklistTemplateEditorScreen
import pe.bpm.features.checklists.presentation.TemplateEditorStep
import pe.bpm.features.checklists.presentation.ChecklistCatalogScreen
import pe.bpm.features.checklists.presentation.ChecklistCatalogState
import pe.bpm.features.checklists.presentation.ChecklistCatalogViewModel
import pe.bpm.features.checklists.presentation.ChecklistHistoryViewModel
import pe.bpm.features.checklists.presentation.ChecklistHistoryScreen
import pe.bpm.features.checklists.presentation.ChecklistHistoryDetailScreen
import pe.bpm.features.checklists.domain.HistoryScope
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsViewModel
import pe.bpm.features.checklists.presentation.ChecklistAssignmentFormViewModel
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsScreen
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsState
import pe.bpm.features.checklists.presentation.ChecklistOfflineViewModel
import pe.bpm.features.checklists.presentation.ChecklistOfflineScreen
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
    pushProvider:pe.bpm.features.notifications.domain.PushProvider=remember { pe.bpm.features.notifications.domain.UnavailablePushProvider() },
    offline: OfflinePlatform = remember { OfflinePlatform() },
) {
    val owner = viewModel { GraphOwner(sessionStore, googleAuthProvider, draftStorage,pushProvider, offline) }
    val offlineVm = viewModel<OfflineViewModel> { owner.graph.offlineViewModel() }
    val offlineUi by offlineVm.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val identity = viewModel<IdentityViewModel> { owner.graph.identityViewModel() }
    val workspace = viewModel<WorkspaceViewModel> { owner.graph.workspaceViewModel() }
    val checklists = viewModel<ChecklistCatalogViewModel> { owner.graph.checklistCatalogViewModel() }
    val templateEditor = viewModel<ChecklistTemplateEditorViewModel> { owner.graph.checklistTemplateEditorViewModel() }
    val formDrafts = viewModel<WorkspaceFormViewModel> { owner.graph.workspaceFormViewModel() }
    val formDraftState by formDrafts.state.collectAsStateWithLifecycle()
    val history = viewModel<ChecklistHistoryViewModel> { owner.graph.checklistHistoryViewModel() }
    val historyState by history.state.collectAsStateWithLifecycle()
    val assignments = viewModel<ChecklistAssignmentsViewModel> { owner.graph.checklistAssignmentsViewModel() }
    val assignmentState by assignments.state.collectAsStateWithLifecycle()
    val assignmentForm = viewModel<ChecklistAssignmentFormViewModel> { owner.graph.checklistAssignmentFormViewModel() }
    val assignmentFormState by assignmentForm.state.collectAsStateWithLifecycle()
    val dailyOperations = viewModel<pe.bpm.features.operations.presentation.OperationsViewModel> { owner.graph.operationsViewModel() }
    val dailyState by dailyOperations.state.collectAsStateWithLifecycle()
    val notificationDevices = viewModel<pe.bpm.features.notifications.presentation.NotificationDevicesViewModel> {owner.graph.notificationDevicesViewModel()}
    val alerts = viewModel<pe.bpm.features.alerts.presentation.AlertsViewModel> { owner.graph.alertsViewModel() }
    val alertsState by alerts.state.collectAsStateWithLifecycle()
    val alertEditorState by alerts.editor.state.collectAsStateWithLifecycle()
    val incidents = viewModel<pe.bpm.features.incidents.presentation.IncidentsViewModel> { owner.graph.incidentsViewModel() }
    val training = viewModel<pe.bpm.features.training.presentation.TrainingViewModel> { owner.graph.trainingViewModel() }
    val trainingAuthoring = viewModel<pe.bpm.features.training.presentation.TrainingAuthoringViewModel> { owner.graph.trainingAuthoringViewModel() }
    val trainingAuthoringState by trainingAuthoring.state.collectAsStateWithLifecycle()
    val team = viewModel<pe.bpm.features.accounts.presentation.TeamViewModel> { owner.graph.teamViewModel() }
    val accountInvitations = viewModel<pe.bpm.features.accounts.presentation.InvitationsViewModel> { owner.graph.invitationsViewModel() }
    val accountOrganization = viewModel<pe.bpm.features.accounts.presentation.OrganizationViewModel> { owner.graph.organizationAccountViewModel() }
    val invitationAccept = viewModel<pe.bpm.features.accounts.presentation.InvitationAcceptViewModel> { owner.graph.invitationAcceptViewModel() }
    val myDevices = viewModel<pe.bpm.features.accounts.presentation.DevicesViewModel> { owner.graph.devicesViewModel() }
    val survey = viewModel<pe.bpm.features.research.presentation.SurveyViewModel> { owner.graph.surveyViewModel() }
    val research = viewModel<pe.bpm.features.research.presentation.ResearchViewModel> { owner.graph.researchViewModel() }
    val researchState by research.state.collectAsStateWithLifecycle()
    val trainingLearner = viewModel<pe.bpm.features.training.presentation.TrainingLearnerViewModel> { owner.graph.trainingLearnerViewModel() }
    val trainingAssignments = viewModel<pe.bpm.features.training.presentation.TrainingAssignmentAdminViewModel> { owner.graph.trainingAssignmentAdminViewModel() }
    val trainingPaths = viewModel<pe.bpm.features.training.presentation.LearningPathAdminViewModel> { owner.graph.learningPathAdminViewModel() }
    val trainingAchievements = viewModel<pe.bpm.features.training.presentation.TrainingAchievementAdminViewModel> { owner.graph.trainingAchievementAdminViewModel() }
    val certificateVerification = viewModel<pe.bpm.features.training.presentation.CertificateVerificationViewModel> { owner.graph.certificateVerificationViewModel() }
    val reportsDashboard = viewModel<pe.bpm.features.reports.presentation.DashboardViewModel> { owner.graph.reportsDashboardViewModel() }
    val reportsDashboardState by reportsDashboard.state.collectAsStateWithLifecycle()
    val reportsView = viewModel<pe.bpm.features.reports.presentation.ReportsViewModel> { owner.graph.reportsViewModel() }
    val reportsAdmin = viewModel<pe.bpm.features.reports.presentation.ReportsAdminViewModel> { owner.graph.reportsAdminViewModel() }
    val usage = viewModel<pe.bpm.features.usage.presentation.UsageViewModel> { owner.graph.usageViewModel() }
    fun resetReports() { reportsDashboard.reset(); reportsView.reset(); reportsAdmin.reset() }
    val runs = viewModel<ChecklistRunViewModel> { owner.graph.checklistRunViewModel() }
    val runState by runs.state.collectAsStateWithLifecycle()
    val evidence = viewModel<pe.bpm.features.media.presentation.MediaEvidenceViewModel> { owner.graph.mediaEvidenceViewModel() }
    val evidenceState by evidence.state.collectAsStateWithLifecycle()
    var evidenceQuestion by remember { mutableStateOf<EvidenceQuestion?>(null) }
    var incidentEvidence by remember { mutableStateOf<IncidentEvidenceTarget?>(null) }
    var cleaningPhotos by remember { mutableStateOf<String?>(null) }
    val versionsVm = viewModel<pe.bpm.features.checklists.presentation.ChecklistVersionsViewModel> { owner.graph.checklistVersionsViewModel() }
    val shiftsVm = viewModel<pe.bpm.features.operations.presentation.ShiftsViewModel> { owner.graph.shiftsViewModel() }
    val stockVm = viewModel<pe.bpm.features.operations.presentation.StockMovementsViewModel> { owner.graph.stockMovementsViewModel() }
    val offlineRuns = viewModel<ChecklistOfflineViewModel> { owner.graph.checklistOfflineViewModel() }
    val offlineState by offlineRuns.state.collectAsStateWithLifecycle()
    val templateEditorState by templateEditor.state.collectAsStateWithLifecycle()
    val identityState by identity.state.collectAsStateWithLifecycle()
    val sessionStatus by identity.sessionStatus.collectAsStateWithLifecycle()
    val workspaceState by workspace.state.collectAsStateWithLifecycle()
    val checklistState by checklists.state.collectAsStateWithLifecycle()
    val profile = identityState.profile
    var alertSource by remember(profile?.id,workspaceState.selected,workspaceState.workspace?.role) {mutableStateOf<pe.bpm.features.alerts.domain.Alert?>(null)}
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

    fun resetTrainingPlus() { trainingLearner.reset(); trainingAssignments.reset(); trainingPaths.reset(); trainingAchievements.reset() }

    fun navigateBack(fallback: String) {
        if (nav.previousBackStackEntry == null) resetTo(fallback)
        else if (onBackNavigation != null) onBackNavigation()
        else nav.popBackStack()
    }

    var loadedIdentityId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile?.id, profile?.organizations, profile?.platformRole) {
        if (loadedIdentityId != profile?.id || profile == null) {
            checklists.reset(); history.reset(); assignments.reset(); assignmentForm.reset()
            templateEditor.reset()
            runs.reset(); offlineRuns.reset(); dailyOperations.reset(); incidents.reset(); alerts.reset(); notificationDevices.reset(); training.reset(); trainingAuthoring.reset(); team.reset(); accountInvitations.reset(); accountOrganization.reset(); invitationAccept.reset(); myDevices.reset(); survey.reset(); research.reset(); resetTrainingPlus(); resetReports()
            formDrafts.reset()
            workspace.reset()
        }
        loadedIdentityId = profile?.id
        if (profile != null) {
            workspace.load()
            if (nav.currentDestination?.route == LOGIN) resetTo(pendingRoute)
        }
    }
    LaunchedEffect(profile?.id, researchState.userId) { if (profile != null) research.loadAccess(profile.id) }
    LaunchedEffect(nav) { onNavHostReady(nav) }
    // Solo las vistas de consulta se revalidan al entrar o volver al primer plano.
    // No hay temporizador de consultas ni recarga de formularios o borradores.
    val primaryRoute = WorkspaceDestination.entries.any { it.route == entry?.destination?.route } || entry?.destination?.route in listOf(CHECKLIST_HISTORY, ASSIGNMENT_MANAGEMENT)
    fun refreshVisibleData() {
        if (identity.state.value.profile != null && identity.sessionStatus.value.warning == null &&
            entry?.destination?.route in listOf(DAILY_MANAGEMENT, DAILY_EXECUTION, WorkspaceDestination.Tasks.route)) dailyOperations.refreshIfStale()
        if (identity.state.value.profile != null && entry?.destination?.route in listOf(INCIDENT_MANAGEMENT, INCIDENT_EXECUTION, INCIDENT_TASKS) && identity.sessionStatus.value.warning == null) incidents.refreshIfStale()
        if(identity.state.value.profile!=null && identity.sessionStatus.value.warning==null && entry?.destination?.route in listOf(ALERT_INBOX,ALERT_RULES)) alerts.refreshIfStale()
        if (identity.state.value.profile != null && primaryRoute && identity.sessionStatus.value.warning == null) {
            workspace.refreshIfStale()
            if (entry?.destination?.route == WorkspaceDestination.Operations.route) checklists.refreshIfStale()
            history.refreshIfStale()
            assignments.refreshIfStale()
        }
    }
    OnAppForeground {
        identity.onForeground(); usage.flushNow(); offlineVm.onForeground()
        if (identity.state.value.profile != null) { refreshVisibleData(); offlineRuns.sync(); if (evidenceQuestion != null || incidentEvidence != null) evidence.refresh() }
    }
    LaunchedEffect(entry?.id, profile?.id) { refreshVisibleData() }
    val currentWorkspace = workspaceState.workspace
    // El ADMIN supervisa a todo el equipo en «Seguimiento», pero en «Control» ve solo sus propias revisiones.
    val onControlRoute = entry?.destination?.route?.startsWith(WorkspaceDestination.Operations.route) == true
    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, currentWorkspace?.role, profile?.platformRole, onControlRoute) {
        val allowed = workspaceDestinations(currentWorkspace?.role, profile?.platformRole == "SUPER_ADMIN")
        if (profile != null && currentWorkspace != null && (WorkspaceDestination.Operations in allowed || WorkspaceDestination.Supervision in allowed))
            history.open(profile.id, currentWorkspace.organization.id, if (WorkspaceDestination.Supervision in allowed && !onControlRoute) HistoryScope.Team else HistoryScope.Mine)
        else history.reset()
    }
    LaunchedEffect(profile?.id,currentWorkspace?.organization?.id,currentWorkspace?.role,profile?.platformRole,workspaceState.selected,workspaceState.errors) {
        if(profile!=null && currentWorkspace!=null && currentWorkspace.organization.id==workspaceState.selected && workspaceState.errors.none{it.code in setOf("ACCESS_DENIED","UNAUTHENTICATED")})
            alerts.open(profile.id,currentWorkspace.organization.id,if(profile.platformRole=="SUPER_ADMIN") "SUPER_ADMIN" else currentWorkspace.role)
        else alerts.reset()
    }
    LaunchedEffect(profile?.id,currentWorkspace?.organization?.id,workspaceState.selected,workspaceState.errors) {
        if(profile!=null && currentWorkspace!=null && currentWorkspace.organization.id==workspaceState.selected && workspaceState.errors.none{it.code in setOf("ACCESS_DENIED","UNAUTHENTICATED")}) notificationDevices.open(profile.id,currentWorkspace.organization.id)
        else notificationDevices.reset()
    }
    LaunchedEffect(entry?.id) { if(entry?.destination?.route !in listOf(DAILY_MANAGEMENT,DAILY_EXECUTION)) alertSource=null }
    // Reportes: el panel de indicadores solo se abre para ADMIN, CHEF y SUPER_ADMIN con empresa autorizada.
    val reportRole = if (profile?.platformRole == "SUPER_ADMIN") "SUPER_ADMIN" else currentWorkspace?.role
    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, workspaceState.selected, reportRole, workspaceState.errors) {
        if (profile != null && currentWorkspace != null && currentWorkspace.organization.id == workspaceState.selected &&
            pe.bpm.features.reports.domain.ReportAccess.canView(reportRole) &&
            workspaceState.errors.none { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") })
            reportsDashboard.open(profile.id, currentWorkspace.organization.id, reportRole)
        else resetReports()
    }
    LaunchedEffect(entry?.id) { if (entry?.destination?.route == WorkspaceDestination.Home.route) reportsDashboard.refresh() }
    // Registro de uso (RF-27): solo con membresía; SUPER_ADMIN no registra. Sin datos sensibles.
    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, workspaceState.selected, profile?.platformRole, workspaceState.errors) {
        val eligible = profile != null && currentWorkspace != null && currentWorkspace.organization.id == workspaceState.selected &&
            profile.platformRole != "SUPER_ADMIN" && workspaceState.errors.none { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") }
        usage.configure(profile?.id, currentWorkspace?.organization?.id, eligible)
    }
    LaunchedEffect(entry?.id) { usage.moduleChanged(usageModuleFor(entry?.destination?.route)) }
    LaunchedEffect(alertsState.detail?.id, alertsState.detail?.viewedAt, alertsState.detail?.attendedAt) {
        alertsState.detail?.let { usage.alertObserved(it.id, it.viewedAt != null, it.attendedAt != null) }
    }
    LaunchedEffect(profile?.id, workspaceState.selected, currentWorkspace?.role, workspaceState.errors) {
        if (profile != null && currentWorkspace != null && currentWorkspace.organization.id == workspaceState.selected &&
            WorkspaceDestination.Operations in workspaceDestinations(currentWorkspace.role, profile.platformRole == "SUPER_ADMIN") &&
            workspaceState.errors.none { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") })
            offlineRuns.open(profile.id, currentWorkspace.organization.id)
        else offlineRuns.reset()
    }
    // Las fotos de limpieza solo se editan por quien la registró y durante las 24 horas siguientes; el servidor decide.
    fun openCleaningPhotos(execution: pe.bpm.features.operations.domain.CleaningExecution) {
        val org = workspaceState.workspace?.organization?.id ?: return
        val user = profile?.id ?: return
        val role = workspaceState.workspace?.role
        val recent = runCatching { kotlin.time.Clock.System.now() - kotlin.time.Instant.parse(execution.createdAt) < 24.hours }.getOrDefault(false)
        val canEdit = execution.responsibleUserId == user && recent && role in setOf("CHEF", "ADMIN", "WORKER")
        cleaningPhotos = execution.input.id
        evidence.openCleaning(user, org, execution.input.id, canEdit)
    }
    LaunchedEffect(offlineState.preparedId) {
        offlineState.preparedId?.let { id ->
            offlineRuns.consumePrepared()
            if (offlineState.owner?.userId == profile?.id && offlineState.owner?.organizationId == workspaceState.selected)
                nav.navigate("control/guardados/$id") { launchSingleTop = true }
        }
    }
    LaunchedEffect(offlineState.owner, offlineState.entries.filter { it.status == pe.bpm.features.checklists.domain.OfflineRunStatus.CONFIRMED }.map { it.run.id to it.run.rowVersion }) {
        if (offlineState.owner?.userId == profile?.id && offlineState.owner?.organizationId == workspaceState.selected &&
            offlineState.entries.any { it.status == pe.bpm.features.checklists.domain.OfflineRunStatus.CONFIRMED }) {
            history.refresh(); assignments.refresh()
        }
    }
    LaunchedEffect(runState.run?.id, runState.run?.rowVersion) { if (runState.run != null) { history.refresh(); assignments.refresh() } }
    LaunchedEffect(profile?.id, workspaceState.selected, currentWorkspace?.role, workspaceState.errors) {
        if (runState.userId != null && (runState.userId != profile?.id || runState.organizationId != workspaceState.selected ||
                WorkspaceDestination.Operations !in workspaceDestinations(currentWorkspace?.role, profile?.platformRole == "SUPER_ADMIN") ||
                workspaceState.errors.any { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") })) runs.reset()
    }
    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, workspaceState.selected, currentWorkspace?.role, profile?.platformRole,
        workspaceState.errors.any { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") }) {
        val canRead = currentWorkspace?.canManage == true || WorkspaceDestination.Operations in workspaceDestinations(currentWorkspace?.role, profile?.platformRole == "SUPER_ADMIN")
        if (profile != null && currentWorkspace != null && currentWorkspace.organization.id == workspaceState.selected && canRead &&
            workspaceState.errors.none { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") })
            { checklists.open(profile.id, currentWorkspace.organization.id); assignments.reset(); assignments.open(profile.id, currentWorkspace.organization.id) }
        else { checklists.reset(); assignments.reset(); assignmentForm.reset() }
    }

    LaunchedEffect(profile?.id, workspaceState.selected, currentWorkspace?.role, workspaceState.errors) {
        if (profile == null || templateEditorState.userId != null && templateEditorState.userId != profile.id ||
            templateEditorState.organizationId != null && templateEditorState.organizationId != workspaceState.selected ||
            currentWorkspace != null && !currentWorkspace.canManage || workspaceState.errors.any { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") }) { templateEditor.reset(); assignmentForm.reset() }
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

    // La raíz conecta funcionalidades sin importar media dentro de checklists.
    LaunchedEffect(profile?.id, currentWorkspace?.organization?.id, currentWorkspace?.role, entry?.id, workspaceState.errors) {
        evidenceQuestion = null
        incidentEvidence = null
        cleaningPhotos = null
        evidence.reset()
    }
    LaunchedEffect(evidenceState.revision, runState.run?.rowVersion) {
        val question = evidenceQuestion
        if (question != null && profile != null && currentWorkspace != null && evidenceState.policy != null && !evidenceState.loading &&
            evidenceState.userId == profile.id && evidenceState.organizationId == currentWorkspace.organization.id &&
            evidenceState.runId == question.runId && evidenceState.itemId == question.itemId) {
            runs.evidenceCount(profile.id, currentWorkspace.organization.id, question.runId, question.itemId, evidenceState.confirmedCount)
            offlineRuns.evidenceCount(profile.id, currentWorkspace.organization.id, question.runId, question.itemId, evidenceState.confirmedCount)
        }
    }

    fun performLogout() {
        confirmLogout = false
        pendingRoute = WorkspaceDestination.Home.route
        checklists.reset(); history.reset(); assignments.reset(); assignmentForm.reset()
        templateEditor.reset()
        runs.reset(); offlineRuns.reset(); dailyOperations.reset(); incidents.reset(); alerts.reset(); notificationDevices.reset(); training.reset(); trainingAuthoring.reset(); team.reset(); accountInvitations.reset(); accountOrganization.reset(); invitationAccept.reset(); myDevices.reset(); survey.reset(); research.reset(); resetTrainingPlus(); resetReports()
        formDrafts.reset()
        workspace.reset()
        usage.endSession()
        identity.logout()
        resetTo(LOGIN)
    }
    // Cerrar sesión borra los datos personales del dispositivo: con cambios sin enviar no se deja salir;
    // solo los cambios en conflicto o rechazados se descartan de forma explícita y con confirmación.
    fun requestLogout() { if (offlineUi.logout == LogoutGate.Clear) performLogout() else confirmLogout = true }

    CompositionLocalProvider(LocalOnline provides offlineUi.status.online, pe.bpm.core.offline.LocalPendingMarks provides offlineUi.marks) {
    SessionContent(sessionStatus, identity::userActivity, identity::continueSession, ::requestLogout,
        banner = { if (profile != null) Column { OfflineStatusBar(offlineUi, onOpen = { nav.navigate(PENDING_CHANGES) { launchSingleTop = true } }, onSync = offlineVm::syncNow); pe.bpm.core.offline.OtherAccountNotice(offlineUi) } }) {
        NavHost(navController = nav, startDestination = LOGIN, modifier = Modifier.fillMaxSize().then(
            if (!supportsNativeModalWindows() && (evidenceQuestion != null || incidentEvidence != null || cleaningPhotos != null)) Modifier.clearAndSetSemantics { } else Modifier),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None }) {
            composable(LOGIN) {
                if (profile == null) {
                    if (identityState.restoring) RestoringSession()
                    else Column {
                        if (identityState.canRetryRestore) BpmTextButton(onClick = identity::restore) { Text("Reintentar sesión guardada") }
                        // El enlace de invitación se conserva mientras la persona inicia sesión o crea su cuenta y confirma su correo.
                        if (pendingRoute.startsWith(INVITATION_BASE)) BpmCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Tienes una invitación pendiente", style = MaterialTheme.typography.titleMedium)
                            Text("Inicia sesión o crea tu cuenta con el correo al que llegó la invitación y confírmalo. Después volverás aquí para aceptarla.")
                        }
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
            composable(PENDING_CHANGES) {
                PendingChangesScreen(offlineUi, onBack = { navigateBack(WorkspaceDestination.Home.route) }, onSync = offlineVm::syncNow,
                    onRetry = offlineVm::retry, onDiscard = offlineVm::discard, onDismissNotice = offlineVm::dismissNotice)
            }
            composable(VERIFY_CERTIFICATE, arguments = listOf(navArgument("codigo") { type = NavType.StringType })) { routeEntry ->
                // Ruta pública: no exige sesión ni organización.
                val code = routeEntry.arguments?.let { NavType.StringType[it, "codigo"] }.orEmpty()
                CertificateVerificationContent(code, certificateVerification, onHome = { resetTo(if (profile == null) LOGIN else WorkspaceDestination.Home.route) })
            }
            WorkspaceDestination.entries.flatMap { destination ->
                val routes = when (destination) {
                    WorkspaceDestination.Learn -> listOf(destination.route, TRAINING_COURSE, TRAINING_ACHIEVEMENTS, TRAINING_PATHS)
                    WorkspaceDestination.Operations -> listOf(destination.route, DAILY_EXECUTION, INCIDENT_EXECUTION, CHECKLIST_CATALOG, CHECKLIST_DETAIL, CHECKLIST_START, CHECKLIST_RUN, CHECKLIST_OFFLINE, CHECKLIST_OFFLINE_DETAIL, CHECKLIST_HISTORY, CHECKLIST_HISTORY_DETAIL, ASSIGNMENT_DETAIL)
                    WorkspaceDestination.Supervision -> listOf(destination.route, SUPERVISION_DETAIL)
                    WorkspaceDestination.Home -> listOf(destination.route, ORGANIZATION_PICKER, ALERT_INBOX, STUDY_SURVEY, RESEARCH_PANEL, REPORTS)
                    WorkspaceDestination.Company -> listOf(destination.route, ACCOUNTS_MANAGEMENT, TEMPLATE_VERSIONS, TRAINING_MANAGEMENT, ALERT_RULES, DAILY_MANAGEMENT, INCIDENT_MANAGEMENT, TEMPLATE_MANAGEMENT, TEMPLATE_CREATE, TEMPLATE_MANAGEMENT_DETAIL, ASSIGNMENT_CREATE, ASSIGNMENT_MANAGEMENT, ASSIGNMENT_MANAGEMENT_DETAIL)
                    WorkspaceDestination.Tasks -> listOf(destination.route, INCIDENT_TASKS)
                    WorkspaceDestination.Account -> listOf(destination.route, DEVICES, INVITATION_ACCEPT)
                    else -> listOf(destination.route)
                }
                (routes + WorkspaceFormKind.entries.filter { it.destination == destination }.map { it.route })
                    .map { destination to it }
            }.forEach { (destination, route) ->
                composable(route, arguments = if (route == INVITATION_ACCEPT) listOf(navArgument("token") { type = NavType.StringType; nullable = true; defaultValue = null }) else if (route in listOf(CHECKLIST_DETAIL, TEMPLATE_MANAGEMENT_DETAIL, CHECKLIST_START, ASSIGNMENT_CREATE, TEMPLATE_VERSIONS))
                    listOf(navArgument("templateId") { type = NavType.StringType }) else if (route == TRAINING_COURSE) listOf(navArgument("courseId") { type = NavType.StringType }) else if (route in listOf(CHECKLIST_RUN, CHECKLIST_OFFLINE_DETAIL, CHECKLIST_HISTORY_DETAIL, SUPERVISION_DETAIL))
                    listOf(navArgument("runId") { type = NavType.StringType }) else if (route in listOf(ASSIGNMENT_DETAIL, ASSIGNMENT_MANAGEMENT_DETAIL))
                    listOf(navArgument("assignmentId") { type = NavType.StringType }) else emptyList()) { routeEntry ->
                    val alertRoute=route in listOf(ALERT_INBOX,ALERT_RULES)
                    val reportsRoute = route == REPORTS
                    val learningCourse = route == TRAINING_COURSE
                    val learningExtras = route == TRAINING_ACHIEVEMENTS || route == TRAINING_PATHS
                    val managingTraining = route == TRAINING_MANAGEMENT
                    val managingAccounts = route == ACCOUNTS_MANAGEMENT
                    val viewingDevices = route == DEVICES
                    val acceptingInvitation = route == INVITATION_ACCEPT
                    val studySurvey = route == STUDY_SURVEY
                    val researching = route == RESEARCH_PANEL
 val managingAlerts=route==ALERT_RULES
                    val daily = route in listOf(DAILY_MANAGEMENT, DAILY_EXECUTION, WorkspaceDestination.Tasks.route)
                    val incidentRoute = route in listOf(INCIDENT_MANAGEMENT, INCIDENT_EXECUTION, INCIDENT_TASKS)
                    val managingDaily = route == DAILY_MANAGEMENT
                    val formKind = WorkspaceFormKind.entries.firstOrNull { it.route == route }
                    val formRevision = remember(routeEntry.id) { workspaceState.revision }
                    val managingTemplates = route in listOf(TEMPLATE_MANAGEMENT, TEMPLATE_CREATE, TEMPLATE_MANAGEMENT_DETAIL, TEMPLATE_VERSIONS)
                    val managingAssignments = route in listOf(ASSIGNMENT_CREATE, ASSIGNMENT_MANAGEMENT, ASSIGNMENT_MANAGEMENT_DETAIL)
                    val editingAssignment = route == ASSIGNMENT_CREATE
                    val viewingAssignment = route in listOf(ASSIGNMENT_DETAIL, ASSIGNMENT_MANAGEMENT_DETAIL)
                    val editingTemplate = route == TEMPLATE_CREATE
                    val running = route == CHECKLIST_START || route == CHECKLIST_RUN
                    val offline = route == CHECKLIST_OFFLINE || route == CHECKLIST_OFFLINE_DETAIL
                    val viewingHistory = route in listOf(CHECKLIST_HISTORY_DETAIL, SUPERVISION_DETAIL)
                    val listingHistory = route == CHECKLIST_HISTORY || destination == WorkspaceDestination.Supervision && !viewingHistory
                    var runExit by remember(routeEntry.id) { mutableStateOf<(() -> Unit)?>(null) }
                    fun leaveRun(action: () -> Unit) {
                        if(managingAlerts) alerts.editor.deactivate(action)
 else if (incidentRoute) incidents.prepareLeave(action)
                        else if (daily && dailyState.form != null) dailyOperations.leave(action)
                        else if (running && (runState.dirty || runState.pending || runState.busy)) runExit = action
                        else action()
                    }
                    fun closeForm() {
                        if (managingAccounts || viewingDevices || acceptingInvitation) { navigateBack(destination.route); return }
                        if (learningCourse) { if (!training.back()) navigateBack(destination.route); return }
                        if (studySurvey) { if (!survey.back()) navigateBack(destination.route); return }
                        if (researching) { if (!research.back()) navigateBack(destination.route); return }
                        if (learningExtras) { if (!(route == TRAINING_PATHS && trainingLearner.back())) navigateBack(destination.route); return }
                        if (managingTraining) {
                            if (trainingAuthoringState.editor != null) trainingAuthoring.closeDraft()
                            else if (trainingAuthoringState.creating != null) trainingAuthoring.cancelCreate()
                            else if (trainingAssignments.back() || trainingPaths.back() || trainingAchievements.back()) Unit
                            else navigateBack(destination.route)
                            return
                        }
                        if(alertRoute && alertsState.detail!=null) alerts.closeDetail()
                        else if(managingAlerts && alertEditorState.form!=null) { if(alertEditorState.reviewing && alertEditorState.pending==null) alerts.editor.resumeEditing() else alerts.editor.close() }
                        else if (incidentRoute) incidents.prepareBack { navigateBack(destination.route) }
                        else if (daily && dailyState.picker != null) dailyOperations.closePicker()
                        else if (daily && dailyState.review && dailyState.pending == null && dailyState.saved == null) dailyOperations.editReview()
                        else if (daily && dailyState.detail != null) dailyOperations.closeDetail()
                        else if (daily && dailyState.form != null) dailyOperations.closeForm()
                        else if (daily) dailyOperations.leave { navigateBack(destination.route) }
                        else if (editingTemplate && templateEditorState.step == TemplateEditorStep.Review && templateEditorState.editable) templateEditor.backToEdit()
                        else if (offline) { offlineRuns.select(null); navigateBack(WorkspaceDestination.Operations.route) }
                        else leaveRun { navigateBack(when { managingAssignments -> ASSIGNMENT_MANAGEMENT; managingTemplates -> TEMPLATE_MANAGEMENT; else -> destination.route }) }
                    }
                    LaunchedEffect(workspaceState.revision) {
                        if (formKind != null && workspaceState.revision > formRevision && entry?.id == routeEntry.id) formDrafts.complete { closeForm() }
                    }
                    LaunchedEffect(workspaceState.errors) {
                        if (formKind != null && entry?.id == routeEntry.id) formDrafts.onSaveFailure(workspaceState.errors)
                    }
                    val templateId = if (route in listOf(CHECKLIST_DETAIL, TEMPLATE_MANAGEMENT_DETAIL, CHECKLIST_START, ASSIGNMENT_CREATE, TEMPLATE_VERSIONS))
                        routeEntry.arguments?.let { NavType.StringType[it, "templateId"] } else null
                    val trainingCourseId = if (learningCourse) routeEntry.arguments?.let { NavType.StringType[it, "courseId"] }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } else null
                    val runId = if (route in listOf(CHECKLIST_RUN, CHECKLIST_OFFLINE_DETAIL, CHECKLIST_HISTORY_DETAIL, SUPERVISION_DETAIL)) routeEntry.arguments?.let { NavType.StringType[it, "runId"] } else null
                    val assignmentId = if (viewingAssignment) routeEntry.arguments?.let { NavType.StringType[it, "assignmentId"] } else null
                    val invitationToken = if (acceptingInvitation) routeEntry.arguments?.let { NavType.StringType[it, "token"] }?.takeIf { pe.bpm.features.accounts.domain.AccountsRules.invitationToken(it) != null } else null
                    val requestedRoute = templateId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
                        ?.let { if (route == TEMPLATE_VERSIONS) "empresa/plantillas/detalle/$it/versiones" else if (route == ASSIGNMENT_CREATE) "empresa/plantillas/detalle/$it/asignar" else if (route == TEMPLATE_MANAGEMENT_DETAIL) "empresa/plantillas/detalle/$it" else "control/plantilla/$it" + if (route == CHECKLIST_START) "/iniciar" else "" }
                        ?: invitationToken?.let { "$INVITATION_BASE?token=$it" } ?: trainingCourseId?.let { "aprender/$it" } ?: runId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }?.let { when (route) { CHECKLIST_OFFLINE_DETAIL -> "control/guardados/$it"; SUPERVISION_DETAIL -> "seguimiento/$it"; CHECKLIST_HISTORY_DETAIL -> "control/revisiones/$it"; else -> "control/ejecucion/$it" } } ?: assignmentId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }?.let { if (managingAssignments) "empresa/controles-programados/$it" else "control/programacion/$it" } ?: route
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
                        val shortcuts = workspaceShortcuts(workspaceState.workspace?.role, profile.platformRole == "SUPER_ADMIN",
                            researchState.access?.canResearch == true, researchState.access?.canManageGrants == true)
                        val activeShortcut = when (route) {
                            INCIDENT_EXECUTION -> WorkspaceShortcut.Incidents
                            RESEARCH_PANEL -> if (WorkspaceShortcut.Access in shortcuts) WorkspaceShortcut.Access else WorkspaceShortcut.Research
                            ORGANIZATION_PICKER -> WorkspaceShortcut.Companies
                            else -> null
                        }?.takeIf { it in shortcuts }
                        // El aviso de acceso se descarta al navegar a otra sección permitida; no debe seguir a la persona por toda la app.
                        LaunchedEffect(route) {
                            if (accessNotice != null && destination in available && route != WorkspaceDestination.Home.route) accessNotice = null
                        }
                        val waiting = workspaceState.loading && workspaceState.workspace == null
                        if ((destination !in available || formKind != null || studySurvey || researching || managingTraining || managingTemplates || managingAssignments || managingDaily || daily || alertRoute || managingAccounts || reportsRoute) && waiting) {
                            RestoringSession()
                        } else if (destination !in available || reportsRoute && !pe.bpm.features.reports.domain.ReportAccess.canView(reportRole) || (managingTraining || managingTemplates || managingAssignments || managingDaily || managingAlerts || managingAccounts) && workspaceState.workspace?.canManage != true || formKind?.isAllowed(profile.platformRole == "SUPER_ADMIN", workspaceState.workspace?.canManage == true) == false) {
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
                            LaunchedEffect(alertRoute,managingAlerts,alertsState.userId,alertsState.organizationId) {
                                if(alertRoute && alertsState.userId==profile.id && alertsState.organizationId==workspaceState.selected) {
                                    if(managingAlerts) alerts.rules() else alerts.inbox()
                                }
                            }
                            val scopedOfflineState = offlineState.takeIf { it.owner?.userId == profile.id && it.owner.organizationId == workspaceState.selected }
                                ?: pe.bpm.features.checklists.presentation.ChecklistOfflineState(busy = true)
                            val scopedHistoryState = historyState.takeIf { it.userId == profile.id && it.organizationId == workspaceState.selected }
                                ?: pe.bpm.features.checklists.presentation.ChecklistHistoryState(loading = true, detailLoading = true)
                            fun openAssignedRun(run: pe.bpm.features.checklists.domain.AssignedChecklistRun) {
                                val target = if (destination == WorkspaceDestination.Company) "seguimiento/${run.id}"
                                    else if (run.status == "DRAFT" && run.performedBy == profile.id) "control/ejecucion/${run.id}"
                                    else if (destination == WorkspaceDestination.Home) "seguimiento/${run.id}" else "control/revisiones/${run.id}"
                                nav.navigate(target) { launchSingleTop = true }
                            }
                            val scopedAssignments = assignmentState.takeIf { it.userId == profile.id && it.organizationId == workspaceState.selected }
                                ?: ChecklistAssignmentsState(loading = true, detailLoading = true)
                            val assignmentContent: (@Composable () -> Unit)? = when {
                                editingAssignment -> ({ pe.bpm.core.offline.ServerOnly { ChecklistAssignmentFormContent(profile.id, templateId, workspaceState, assignmentFormState, assignmentForm,
                                    checklistState, checklists, workspace,
                                    onSaved = { assignments.refresh(); nav.navigate(ASSIGNMENT_MANAGEMENT) { popUpTo(ASSIGNMENT_CREATE) { inclusive = true }; launchSingleTop = true } },
                                    onDiscard = { navigateBack(TEMPLATE_MANAGEMENT) }) } })
                                viewingAssignment -> ({ ChecklistAssignmentDetailContent(profile.id, workspaceState.selected.orEmpty(), assignmentId, managingAssignments,
                                    scopedAssignments, assignments, runState, runs, checklistState, checklists, ::openAssignedRun,
                                    onStarted = { id -> nav.navigate("control/ejecucion/$id") { popUpTo(ASSIGNMENT_DETAIL) { inclusive = true }; launchSingleTop = true } }) })
                                route == ASSIGNMENT_MANAGEMENT -> ({ ChecklistAssignmentsScreen(scopedAssignments, true, assignments::search, assignments::refresh, assignments::previous, assignments::next,
                                    onOpen = { nav.navigate("empresa/controles-programados/${it.id}") { launchSingleTop = true } },
                                    assignments::requestDeactivate, assignments::confirmDeactivate) })
                                else -> null
                            }
                            val managementContent: (@Composable () -> Unit)? = if (!managingTemplates) null else {
                                { pe.bpm.core.offline.ServerOnly {
                                    if (route == TEMPLATE_VERSIONS) {
                                        ChecklistVersionsContent(profile.id, workspaceState.selected.orEmpty(), templateId,
                                            workspaceState.workspace?.canManage == true, versionsVm, checklists)
                                    } else if (editingTemplate) {
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
                                            BpmSecondaryButton(onClick = { nav.navigate(ASSIGNMENT_MANAGEMENT) { launchSingleTop = true } }) { Text("Controles programados por sede") }
                                            Text("Crea una primera versión y revisa las preguntas antes de publicarla.")
                                            RaisedButton("Crear plantilla", {
                                                if (templateEditorState.published != null) templateEditor.reset()
                                                nav.navigate(TEMPLATE_CREATE) { launchSingleTop = true }
                                            }, Modifier.widthIn(min = 160.dp))
                                        }
                                        LaunchedEffect(templateId, checklistState.userId, checklistState.organizationId) {
                                            if (checklistState.organizationId == workspaceState.selected) checklists.showTemplate(templateId)
                                        }
                                        ChecklistCatalogScreen(scopedManagement, templateId, checklists::refresh, checklists::search,
                                            onSelect = { id -> nav.navigate("empresa/plantillas/detalle/$id") { launchSingleTop = true } }, onUserActivity = identity::userActivity,
                                            onNext = checklists::next, onPrevious = checklists::previous, management = true,
                                            onAssign = { id -> nav.navigate("empresa/plantillas/detalle/$id/asignar") { launchSingleTop = true } },
                                            onVersions = { id -> nav.navigate("empresa/plantillas/detalle/$id/versiones") { launchSingleTop = true } })
                                    }
                                } }
                            }
                            LaunchedEffect(viewingHistory, runId, historyState.userId, historyState.organizationId) {
                                if (viewingHistory && historyState.userId == profile.id && historyState.organizationId == workspaceState.selected) history.select(runId)
                            }
                            LaunchedEffect(offline, runId, offlineState.entries.map { it.run.id }, offlineState.localConflict) {
                                if (offline) offlineRuns.select(runId)
                            }
                            WorkspaceScreen(
                                viewer = WorkspaceViewer(profile.id, profile.displayName, profile.email, profile.platformRole == "SUPER_ADMIN"),
                                state = workspaceState,
                                section = destination,
                                onAlerts=if(currentWorkspace!=null) ({leaveRun{nav.navigate(ALERT_INBOX){launchSingleTop=true}}}) else null,
                                unreadAlertCount=alertsState.unreadCount,
                                onAlertRules={nav.navigate(ALERT_RULES){launchSingleTop=true}},
                                onManageTemplates = { nav.navigate(TEMPLATE_MANAGEMENT) { launchSingleTop = true } },
                                onDailyOperations = { nav.navigate(DAILY_MANAGEMENT) { launchSingleTop = true } },
                                onTraining = { nav.navigate(TRAINING_MANAGEMENT) { launchSingleTop = true } },
                                onAccounts = { nav.navigate(ACCOUNTS_MANAGEMENT) { launchSingleTop = true } },
                                onDevices = { nav.navigate(DEVICES) { launchSingleTop = true } },
                                onAcceptInvitation = { nav.navigate(INVITATION_BASE) { launchSingleTop = true } },
                                homeContent = if (pe.bpm.features.reports.domain.ReportAccess.canView(reportRole) || currentWorkspace?.role == "ADMIN") ({
                                    if (pe.bpm.features.reports.domain.ReportAccess.canView(reportRole))
                                        pe.bpm.features.reports.presentation.DashboardPanel(reportsDashboardState, reportsDashboard::refresh, { nav.navigate(REPORTS) { launchSingleTop = true } })
                                    // El ADMIN miembro también ejecuta controles: junto al panel ve los de hoy en todas sus sedes.
                                    if (currentWorkspace?.role == "ADMIN" && profile.platformRole != "SUPER_ADMIN")
                                        ChecklistAssignmentsScreen(scopedAssignments, false, assignments::search, assignments::refresh, assignments::previous, assignments::next,
                                            onOpen = { item -> if (item.run != null) openAssignedRun(item.run) else nav.navigate("control/programacion/${item.id}") { launchSingleTop = true } },
                                            assignments::requestDeactivate, assignments::confirmDeactivate, title = "Controles de hoy")
                                }) else null,
                                onOpenCourse = { id -> nav.navigate("aprender/$id") { launchSingleTop = true } },
                                learnHeader = if (destination == WorkspaceDestination.Learn && currentWorkspace != null) ({
                                    TrainingLearnerHeader(profile.id, currentWorkspace.organization.id, profile.platformRole != "SUPER_ADMIN", trainingLearner,
                                        onOpenCourse = { id -> nav.navigate("aprender/$id") { launchSingleTop = true } },
                                        onAchievements = { nav.navigate(TRAINING_ACHIEVEMENTS) { launchSingleTop = true } },
                                        onPaths = { nav.navigate(TRAINING_PATHS) { launchSingleTop = true } })
                                }) else null,
                                onIncidents = { nav.navigate(if (destination == WorkspaceDestination.Company) INCIDENT_MANAGEMENT else INCIDENT_EXECUTION) { launchSingleTop = true } },
                                taskContent = {
                                    Column(Modifier.fillMaxSize()) {
                                        BpmSecondaryButton(onClick = { leaveRun { nav.navigate(INCIDENT_TASKS) { launchSingleTop = true } } }, enabled = !dailyState.busy) { Text("Incidencias y correcciones") }
                                        DailyOperationsContent(profile.id, workspaceState.workspace?.role.orEmpty(), workspaceState, workspace, dailyOperations, Modifier.weight(1f),
                                            onCleaningPhotos = { execution -> openCleaningPhotos(execution) })
                                    }
                                },
                                secondaryContent = if (studySurvey) ({
                                    SurveyContent(profile.id, workspaceState.selected.orEmpty(), survey)
                                }) else if (researching) ({
                                    pe.bpm.core.offline.ServerOnly { ResearchContent(profile.id, research) }
                                }) else if (learningCourse) ({
                                    if (trainingCourseId != null) TrainingCourseContent(profile.id, workspaceState.selected.orEmpty(), trainingCourseId, profile.platformRole != "SUPER_ADMIN", training)
                                    else LaunchedEffect(routeEntry.id) { resetTo(WorkspaceDestination.Learn.route) }
                                }) else if (managingAccounts) ({
                                    AccountsManagementContent(profile.id, workspaceState.selected.orEmpty(), workspaceState.workspace?.canManage == true, workspaceState.workspace?.role == "ADMIN", team, accountInvitations, accountOrganization,
                                        organizationName = workspaceState.workspace?.organization?.name.orEmpty())
                                }) else if (viewingDevices) ({
                                    DevicesContent(profile.id, myDevices)
                                }) else if (acceptingInvitation) ({
                                    InvitationAcceptContent(profile.id, invitationToken, invitationAccept, onAccepted = identity::verifySession, onContinue = { resetTo(WorkspaceDestination.Home.route) })
                                }) else if (learningExtras) ({
                                    TrainingLearnerContent(profile.id, workspaceState.selected.orEmpty(), profile.platformRole != "SUPER_ADMIN", route == TRAINING_PATHS, trainingLearner,
                                        onOpenCourse = { id -> nav.navigate("aprender/$id") { launchSingleTop = true } })
                                }) else if (managingTraining) ({
                                    pe.bpm.core.offline.ServerOnly { TrainingPlusAdminContent(workspaceState.selected.orEmpty(), workspaceState.workspace?.canManage == true, workspaceState, workspace,
                                        trainingAssignments, trainingPaths, trainingAchievements, tabsLocked = trainingAuthoringState.editor != null || trainingAuthoringState.creating != null) {
                                        TrainingAdminContent(profile.id, workspaceState.selected.orEmpty(), workspaceState.workspace?.canManage == true, trainingAuthoring)
                                    } }
                                }) else if(alertRoute) ({
                                    if(managingAlerts) pe.bpm.core.offline.ServerOnly { pe.bpm.features.alerts.presentation.AlertRulesScreen(alerts,modifier=Modifier,
                                        locationSelector={selected,onSelect,enabled->
                                            AlertScopeSelector(workspaceState,workspace,selected,alertEditorState.form?.locationName.orEmpty(),if(alertEditorState.form!=null) "Toda la empresa" else "Empresa y todas las sedes",enabled,onSelect)
                                        }) }
                                    else pe.bpm.features.alerts.presentation.AlertsScreen(alerts,modifier=Modifier,pushControls={pe.bpm.features.notifications.presentation.PushOptInPanel(notificationDevices)},onOpenSource={notice->
                                        if(notice.sourceAvailable) when(notice.sourceType){
                                            "CHECKLIST_ASSIGNMENT"->nav.navigate("control/programacion/${notice.sourceId}"){launchSingleTop=true}
                                            "RECEIPT_ITEM"->{alertSource=notice;nav.navigate(if(workspaceState.workspace?.canManage==true) DAILY_MANAGEMENT else DAILY_EXECUTION){launchSingleTop=true}}
                                            "ORGANIZATION"->nav.navigate(WorkspaceDestination.Company.route){launchSingleTop=true}
                                            "TRAINING_ASSIGNMENT"->nav.navigate(WorkspaceDestination.Learn.route){launchSingleTop=true}
                                        }
                                    })
                                }) else if (reportsRoute) ({
                                    pe.bpm.core.offline.ServerOnly {
                                    ReportsContent(profile.id, workspaceState.selected.orEmpty(), reportRole,
                                        workspaceState.workspace?.locations.orEmpty().map { pe.bpm.features.reports.presentation.ReportLocationOption(it.id, it.name) }, reportsView, reportsAdmin)
                                    }
                                }) else if (incidentRoute) ({
                                    IncidentsContent(profile.id, if (profile.platformRole == "SUPER_ADMIN") "SUPER_ADMIN" else workspaceState.workspace?.role.orEmpty(), workspaceState, workspace, incidents,
                                        onEvidence = { incidentId, actionId, stage, writable ->
                                            pe.bpm.features.media.domain.EvidenceStage.entries.firstOrNull { it.name == stage }?.let { phase ->
                                                incidentEvidence = IncidentEvidenceTarget(incidentId, actionId, phase)
                                                evidence.openIncident(profile.id, workspaceState.workspace!!.organization.id, incidentId, actionId, phase, writable)
                                            }
                                        })
                                }) else if (daily && destination != WorkspaceDestination.Tasks) ({
                                    DailyOperationsContent(profile.id, if (profile.platformRole == "SUPER_ADMIN") "SUPER_ADMIN" else workspaceState.workspace?.role.orEmpty(), workspaceState, workspace, dailyOperations,sourceSiteId=alertSource?.locationId,sourceSiteName=alertSource?.locationName.orEmpty(),sourceReceiptId=alertSource?.sourceParentId,
                                        shifts = shiftsVm, stock = stockVm, onCleaningPhotos = { execution -> openCleaningPhotos(execution) })
                                }) else if (offline) ({
                                    ChecklistOfflineScreen(scopedOfflineState, runId,
                                        onOpen = { id -> nav.navigate("control/guardados/$id") { launchSingleTop = true } },
                                        onEdit = offlineRuns::edit, onProtect = offlineRuns::protect, onReview = offlineRuns::review,
                                        onBackToEdit = offlineRuns::backToEdit, onQueue = offlineRuns::enqueue,
                                        onSync = { offlineRuns.sync(manual = true) }, onRetryOpen = offlineRuns::retryOpen,
                                        onRemove = { id -> offlineRuns.remove(id) }, onUseServerVersion = offlineRuns::useServerVersion,
                                        evidence = { item -> scopedOfflineState.selected?.let { saved ->
                                            EvidenceQuestionAction(item.label, saved.run.evidenceCounts[item.id] ?: 0, saved.editable && !scopedOfflineState.busy && !scopedOfflineState.localConflict,
                                                onOpen = { edit -> evidenceQuestion = EvidenceQuestion(saved.run.id, item.id, item.label); evidence.open(profile.id, saved.run.organizationId, saved.run.id, item.id, edit && currentWorkspace?.role in setOf("CHEF", "ADMIN")) })
                                        } })
                                }) else if (assignmentContent != null) assignmentContent else if (viewingHistory) ({
                                    // Lista–detalle: desde 840 dp la lista de revisiones queda a la izquierda y el detalle al lado.
                                    BpmListDetail(list = {
                                        ChecklistHistoryScreen(scopedHistoryState, destination == WorkspaceDestination.Operations,
                                            history::search, history::filter, history::next, history::previous, history::refresh,
                                            onOpen = { item ->
                                                val target = when {
                                                    destination == WorkspaceDestination.Supervision -> "seguimiento/${item.id}"
                                                    item.status == "DRAFT" && item.performedBy == profile.id -> "control/ejecucion/${item.id}"
                                                    else -> "control/revisiones/${item.id}"
                                                }
                                                nav.navigate(target) { launchSingleTop = true }
                                            })
                                    }, detail = { ChecklistHistoryDetailScreen(scopedHistoryState, onRetry = { history.select(runId) }, evidence = { item ->
                                    scopedHistoryState.detail?.let { reviewed -> EvidenceQuestionAction(item.label, reviewed.evidenceCounts[item.id] ?: 0, false,
                                        onOpen = { evidenceQuestion = EvidenceQuestion(reviewed.id, item.id, item.label); evidence.open(profile.id, reviewed.organizationId, reviewed.id, item.id, false) }) }
                                }) }) }) else if (running) ({
                                    if (runExit != null) BpmCard(Modifier.fillMaxWidth()) {
                                        Text(if (runState.pending || runState.busy) "Hay una operación sin confirmar" else "Hay respuestas sin guardar")
                                        Text("Puedes conservar el borrador en este dispositivo y retomarlo después con tu misma cuenta.")
                                        BpmSecondaryButton(onClick = { val exit = runExit; runs.leave { runExit = null; exit?.invoke() } }) { Text("Conservar borrador y salir") }
                                        if (!runState.pending && !runState.busy) BpmSecondaryButton(onClick = { val exit = runExit; runExit = null; runs.discard { exit?.invoke() } }) { Text("Descartar cambios y salir") }
                                        BpmTextButton(onClick = { runExit = null }) { Text("Continuar con el control") }
                                    }
                                    if (scopedOfflineState.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    scopedOfflineState.failure?.let { Text(pe.bpm.features.checklists.presentation.runFailureMessage(it), color = MaterialTheme.colorScheme.error) }
                                    if (scopedOfflineState.localStatus == pe.bpm.core.presentation.DraftSaveStatus.FAILED)
                                        Text(scopedOfflineState.notice.orEmpty(), color = MaterialTheme.colorScheme.error)
                                    ChecklistRunContent(profile.id, workspaceState, checklistState, runState,
                                    runs, workspace, checklists, route == CHECKLIST_START, templateId, runId,
                                    onStarted = { id -> nav.navigate("control/ejecucion/$id") { popUpTo(CHECKLIST_START) { inclusive = true }; launchSingleTop = true } },
                                    onWorkOffline = if (scopedOfflineState.owner == null || scopedOfflineState.busy) null else { id -> offlineRuns.prepare(id) },
                                    onBack = { nav.navigate(CHECKLIST_HISTORY) { popUpTo(WorkspaceDestination.Operations.route) { inclusive = false }; launchSingleTop = true } },
                                    evidence = { item -> runState.run?.let { active -> EvidenceQuestionAction(item.label, active.evidenceCounts[item.id] ?: 0, runState.editable && !runState.reviewing,
                                        onOpen = { edit -> evidenceQuestion = EvidenceQuestion(active.id, item.id, item.label); evidence.open(profile.id, active.organizationId, active.id, item.id, edit && currentWorkspace?.role in setOf("CHEF", "ADMIN")) }) }
                                    }) }) else managementContent,
                                secondaryEditor = studySurvey || researching || learningCourse || learningExtras || managingAccounts || viewingDevices || acceptingInvitation || managingTraining || alertRoute || reportsRoute || incidentRoute || editingTemplate || daily && destination != WorkspaceDestination.Tasks,
                                // Patrón de la pantalla alojada: lectura de una columna (pasos), formulario o contenido amplio.
                                secondaryPattern = when {
                                    studySurvey || learningCourse || running || offline -> BpmPattern.StepFlow
                                    acceptingInvitation -> BpmPattern.Access
                                    editingAssignment || editingTemplate -> BpmPattern.Form
                                    else -> BpmPattern.Panel
                                },
                                secondaryTitle = if (studySurvey) "Encuesta del estudio" else if (researching) "Investigación" else if (managingAccounts) "Cuentas y equipo" else if (viewingDevices) "Mis dispositivos" else if (acceptingInvitation) "Aceptar invitación" else if(alertRoute) (if(managingAlerts) "Reglas de avisos" else "Mis avisos") else if (reportsRoute) "Reportes" else if (incidentRoute) "Incidencias y correcciones" else if (daily && destination != WorkspaceDestination.Tasks) "Insumos y limpieza" else if (offline) "Controles en este dispositivo" else if (editingAssignment) "Programar control" else if (managingAssignments) "Controles programados" else if (viewingAssignment) "Control programado" else if (viewingHistory) "Detalle de revisión" else if (route == CHECKLIST_HISTORY) "Mis revisiones" else if (running) "Revisión de tu sede" else if (route == TEMPLATE_VERSIONS) "Versiones de la plantilla" else if (managingTemplates) "Plantillas de control" else if (route == CHECKLIST_DETAIL) "Detalle del control" else null,
                                secondaryBusy = daily && dailyState.saving || offline && offlineState.busy || assignmentFormState.busy || templateEditorState.publishing || running && (runState.busy || runState.pending),
                                secondaryScrollKey = if (offline) "${offlineState.reviewing}:${offlineState.selected?.status}" else if (running) "${runState.reviewing}:${runState.failure}:${runState.run?.status}:${runState.busy}:${runExit != null}" else null,
                                formDraft = formDraftState, onSubmitForm = { formDrafts.submit { draft ->
                                    when (formKind) {
                                        WorkspaceFormKind.Organization -> workspace.organization(Organization(draft.id, draft.name, draft.trade, draft.business, draft.tax, draft.contactName, draft.phone, draft.email))
                                        WorkspaceFormKind.Location -> workspace.location(Location(draft.id, draft.name, draft.address, draft.district, draft.phone))
                                        WorkspaceFormKind.Member -> workspace.member(draft.email, draft.role, if (draft.role == "ADMIN") emptyList() else draft.locations.toList())
                                        null -> Unit
                                    }
                                } }, onFormRetryAccount = { workspace.clearErrors(); formDrafts.retryAccountSearch() }, onFormSelectAccount = { email -> workspace.clearErrors(); formDrafts.selectAccount(email) }, onFormEdit = { change -> workspace.clearErrors(); formDrafts.edit(change) }, onFormRetry = formDrafts::retry, onFormDiscard = { formDrafts.discard { closeForm() } },
                                form = formKind,
                                choosingOrganization = route == ORGANIZATION_PICKER,
                                onOrganizationSearch = workspace::organizationSearch, onOrganizationNext = workspace::organizationNext,
                                onOrganizationPrevious = workspace::organizationPrevious, onOrganizationRetry = workspace::organizationRetry,
                                onPageSearch = workspace::pageSearch, onPageNext = workspace::pageNext,
                                onPagePrevious = workspace::pagePrevious, onPageRetry = workspace::pageRetry,
                                onChooseOrganization = { leaveRun { nav.navigate(ORGANIZATION_PICKER) { launchSingleTop = true } } },
                                onOpenForm = { kind -> nav.navigate(kind.route) { launchSingleTop = true } },
                                onCloseForm = ::closeForm,
                                onNavigate = { next -> leaveRun { nav.navigate(next.route) { launchSingleTop = true } } },
                                shortcuts = shortcuts, activeShortcut = activeShortcut,
                                onShortcut = { shortcut -> leaveRun { when (shortcut) {
                                    WorkspaceShortcut.Incidents -> nav.navigate(INCIDENT_EXECUTION) { launchSingleTop = true }
                                    WorkspaceShortcut.Research, WorkspaceShortcut.Access -> nav.navigate(RESEARCH_PANEL) { launchSingleTop = true }
                                    WorkspaceShortcut.Companies -> nav.navigate(ORGANIZATION_PICKER) { launchSingleTop = true }
                                } } },
                                onVerifySession = identity::verifySession,
                                sessionBusy = identityState.busy,
                                sessionMessage = identityState.errors.firstOrNull()?.message,
                                sessionConfirmation = identityState.verificationMessage,
                                onSelect = { id ->
                                    if (!workspaceState.saving) {
                                        checklists.reset(); history.reset(); assignments.reset(); assignmentForm.reset()
                                        templateEditor.reset()
                                        runs.reset(); offlineRuns.reset(); dailyOperations.reset(); incidents.reset(); alerts.reset(); notificationDevices.reset(); training.reset(); trainingAuthoring.reset(); team.reset(); accountInvitations.reset(); accountOrganization.reset(); invitationAccept.reset(); myDevices.reset(); survey.reset(); research.reset(); resetTrainingPlus(); resetReports()
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
                                    if (destination == WorkspaceDestination.Home && route == WorkspaceDestination.Home.route) {
                                        if (currentWorkspace != null && profile.platformRole != "SUPER_ADMIN") BpmCard(Modifier.fillMaxWidth()) {
                                            Text("Encuesta del estudio", style = MaterialTheme.typography.titleMedium)
                                            Text("Participa de forma voluntaria y anónima en el estudio sobre buenas prácticas de manipulación.")
                                            BpmSecondaryButton(onClick = { nav.navigate(STUDY_SURVEY) { launchSingleTop = true } }) { Text("Abrir encuesta del estudio") }
                                        }
                                        if (researchState.access?.let { it.canResearch || it.canManageGrants } == true) BpmCard(Modifier.fillMaxWidth()) {
                                            Text("Investigación", style = MaterialTheme.typography.titleMedium)
                                            Text("Consentimiento, cuestionarios, resultados por empresa y necesidades detectadas.")
                                            BpmSecondaryButton(onClick = { nav.navigate(RESEARCH_PANEL) { launchSingleTop = true } }) { Text("Abrir investigación") }
                                        }
                                    }
                                    if (destination in listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations) && !running && runState.resumeId != null && runState.userId == profile.id && runState.organizationId == workspaceState.selected) {
                                        BpmCard(Modifier.fillMaxWidth()) {
                                            Text("Tienes un control pendiente en este dispositivo")
                                            BpmSecondaryButton(onClick = { nav.navigate("control/ejecucion/${runState.resumeId}") { launchSingleTop = true } }) { Text("Retomar control") }
                                        }
                                    }
                                    accessNotice?.let { message -> BpmCard(Modifier.fillMaxWidth()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            BpmIcon(BpmSymbol.Alert)
                                            Text("Acceso restringido", style = MaterialTheme.typography.titleMedium)
                                        }
                                        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                        BpmTextButton(onClick = { accessNotice = null }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 8.dp)) { Text("Entendido") }
                                    } }
                                    identityState.notice?.let { Text(it,
                                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                                },
                                checklistContent = {
                                    if (route == WorkspaceDestination.Operations.route) {
                                        // Panel de Control: controles de hoy como contenido principal y accesos a la derecha (clase grande) o debajo.
                                        BpmPanelLayout(support = {
                                            BpmActionTile(BpmSymbol.Sprout, "Insumos y limpieza", "Recepciones, lotes y tareas de tu sede.",
                                                { nav.navigate(DAILY_EXECUTION) { launchSingleTop = true } }, Modifier.fillMaxWidth())
                                            BpmActionTile(BpmSymbol.Control, if (offlineState.pendingCount > 0) "En este dispositivo · ${offlineState.pendingCount} pendientes" else "Controles en este dispositivo",
                                                "Revisiones guardadas para trabajar sin conexión.", { nav.navigate(CHECKLIST_OFFLINE) { launchSingleTop = true } }, Modifier.fillMaxWidth())
                                            BpmActionTile(BpmSymbol.Clock, "Mis revisiones", "Historial de tus controles.",
                                                { nav.navigate(CHECKLIST_HISTORY) { launchSingleTop = true } }, Modifier.fillMaxWidth())
                                            BpmActionTile(BpmSymbol.Learn, "Otras plantillas", "Consulta las plantillas publicadas.",
                                                { nav.navigate(CHECKLIST_CATALOG) { launchSingleTop = true } }, Modifier.fillMaxWidth())
                                        }) {
                                            ChecklistAssignmentsScreen(scopedAssignments, false, assignments::search, assignments::refresh, assignments::previous, assignments::next,
                                                onOpen = { item -> if (item.run != null) openAssignedRun(item.run) else nav.navigate("control/programacion/${item.id}") { launchSingleTop = true } },
                                                assignments::requestDeactivate, assignments::confirmDeactivate)
                                        }
                                    } else if (listingHistory) {
                                        if (destination == WorkspaceDestination.Operations) BpmSecondaryButton(onClick = { nav.navigate(WorkspaceDestination.Operations.route) { launchSingleTop = true } }) { Text("Preparar una nueva revisión") }
                                        val scopedHistory = historyState.takeIf { it.userId == profile.id && it.organizationId == workspaceState.selected }
                                        if (scopedHistory != null) ChecklistHistoryScreen(scopedHistory, destination == WorkspaceDestination.Operations,
                                            history::search, history::filter, history::next, history::previous, history::refresh,
                                            onOpen = { item ->
                                                val target = when {
                                                    destination == WorkspaceDestination.Supervision -> "seguimiento/${item.id}"
                                                    item.status == "DRAFT" && item.performedBy == profile.id -> "control/ejecucion/${item.id}"
                                                    else -> "control/revisiones/${item.id}"
                                                }
                                                nav.navigate(target) { launchSingleTop = true }
                                            })
                                    } else {
                                    BpmTextButton(onClick = { nav.navigate(CHECKLIST_HISTORY) { launchSingleTop = true } }) { Text("Mis revisiones en curso y finalizadas") }
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
                                    }
                                },
                                onClear = workspace::clearErrors,
                                onLogout = ::requestLogout,
                            )
                        }
                    }
                }
            }
        }
        if (confirmLogout) LogoutDialog(offlineUi, online = offlineUi.status.online,
            onSync = offlineVm::syncNow, onOpenPending = { confirmLogout = false; nav.navigate(PENDING_CHANGES) { launchSingleTop = true } },
            onLogout = ::performLogout, onDiscardAndLogout = { ids -> offlineVm.discardAll(ids, ::performLogout) },
            onDismiss = { confirmLogout = false })
        evidenceQuestion?.let { question ->
            ChecklistEvidenceHost(question, evidence, evidenceState) { evidence.close(); evidenceQuestion = null }
        }
        cleaningPhotos?.let {
            PrivateEvidenceHost("Fotos de la limpieza", evidence, evidenceState,
                "El registro se conserva. La foto pendiente tendrá que seleccionarse de nuevo.",
                onClose = { evidence.close(); cleaningPhotos = null })
        }
        incidentEvidence?.let { target ->
            PrivateEvidenceHost(if (target.stage == pe.bpm.features.media.domain.EvidenceStage.BEFORE) "Fotos de la incidencia" else "Fotos de la corrección", evidence, evidenceState,
                onClose = { evidence.close(); incidentEvidence = null; incidents.refresh() })
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
private const val PENDING_CHANGES = "cambios-pendientes"
private const val CHECKLIST_DETAIL = "control/plantilla/{templateId}"
private const val CHECKLIST_START = "control/plantilla/{templateId}/iniciar"
private const val CHECKLIST_RUN = "control/ejecucion/{runId}"
private const val ORGANIZATION_PICKER = "organizaciones"

private const val TEMPLATE_MANAGEMENT = "empresa/plantillas"
private const val TEMPLATE_CREATE = "empresa/plantillas/nueva"
private const val TEMPLATE_MANAGEMENT_DETAIL = "empresa/plantillas/detalle/{templateId}"

private const val CHECKLIST_HISTORY = "control/revisiones"
private const val CHECKLIST_HISTORY_DETAIL = "control/revisiones/{runId}"
private const val SUPERVISION_DETAIL = "seguimiento/{runId}"

private const val CHECKLIST_CATALOG = "control/plantillas"
private const val TEMPLATE_VERSIONS = "empresa/plantillas/detalle/{templateId}/versiones"
private const val ASSIGNMENT_DETAIL = "control/programacion/{assignmentId}"
private const val ASSIGNMENT_CREATE = "empresa/plantillas/detalle/{templateId}/asignar"
private const val ASSIGNMENT_MANAGEMENT = "empresa/controles-programados"
private const val ASSIGNMENT_MANAGEMENT_DETAIL = "empresa/controles-programados/{assignmentId}"

private const val CHECKLIST_OFFLINE = "control/guardados"
private const val CHECKLIST_OFFLINE_DETAIL = "control/guardados/{runId}"

private const val DAILY_MANAGEMENT = "empresa/operacion"
private const val DAILY_EXECUTION = "control/operacion"

private data class IncidentEvidenceTarget(val incidentId: String, val actionId: String?, val stage: pe.bpm.features.media.domain.EvidenceStage)
private const val INCIDENT_MANAGEMENT = "empresa/incidencias"
private const val INCIDENT_EXECUTION = "control/incidencias"
private const val INCIDENT_TASKS = "tareas/incidencias"

private const val TRAINING_COURSE = "aprender/{courseId}"
private const val TRAINING_MANAGEMENT = "empresa/academia"
private const val STUDY_SURVEY = "estudio"
private const val RESEARCH_PANEL = "investigacion"

private const val ACCOUNTS_MANAGEMENT = "empresa/cuentas"
private const val DEVICES = "cuenta/dispositivos"
private const val INVITATION_BASE = "cuenta/invitacion"
private const val INVITATION_ACCEPT = "cuenta/invitacion?token={token}"
private const val TRAINING_ACHIEVEMENTS = "aprender-logros"
private const val TRAINING_PATHS = "aprender-rutas"
private const val VERIFY_CERTIFICATE = "verificar/{codigo}"

private val Int.hours get() = kotlin.time.Duration.parse("PT${this}H")
private const val ALERT_INBOX="avisos"
private const val ALERT_RULES="empresa/avisos"
private const val REPORTS = "reportes"
