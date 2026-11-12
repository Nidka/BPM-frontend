package pe.bpm.core.di

import io.ktor.client.HttpClient
import pe.bpm.features.operations.data.RemoteOperationsRepository
import pe.bpm.features.operations.data.LocalOperationsDraftRepository
import pe.bpm.features.operations.domain.ManageOperations
import pe.bpm.features.operations.domain.ManageOperationsDraft
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.media.data.RemoteMediaRepository
import pe.bpm.features.media.data.createMediaObjectHttpClient
import pe.bpm.features.media.domain.*
import pe.bpm.features.media.presentation.MediaEvidenceViewModel
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.network.createObjectStorageClient
import pe.bpm.features.checklists.data.RemoteChecklistCatalogRepository
import pe.bpm.features.checklists.data.RemoteChecklistTemplatePublisher
import pe.bpm.features.checklists.data.LocalChecklistRunDraftRepository
import pe.bpm.features.checklists.data.LocalChecklistTemplateDraftRepository
import pe.bpm.features.workspace.data.LocalWorkspaceFormDraftRepository
import pe.bpm.features.workspace.presentation.WorkspaceFormViewModel
import pe.bpm.core.storage.DraftStorage
import pe.bpm.core.storage.UnavailableDraftStorage
import pe.bpm.features.checklists.data.LocalChecklistOfflineRepository
import pe.bpm.features.checklists.presentation.ChecklistOfflineViewModel
import pe.bpm.features.checklists.data.RemoteChecklistHistoryRepository
import pe.bpm.features.checklists.presentation.ChecklistHistoryViewModel
import pe.bpm.features.checklists.data.RemoteChecklistRunRepository
import pe.bpm.features.checklists.data.RemoteChecklistAssignmentRepository
import pe.bpm.features.checklists.data.LocalChecklistAssignmentDraftRepository
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsViewModel
import pe.bpm.features.checklists.presentation.ChecklistAssignmentFormViewModel
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.ChecklistRunViewModel
import kotlin.time.Clock
import pe.bpm.features.checklists.domain.PublishChecklistTemplate
import pe.bpm.features.checklists.presentation.ChecklistTemplateEditorViewModel
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi
import pe.bpm.features.checklists.domain.LoadChecklistTemplate
import pe.bpm.features.checklists.domain.LoadChecklistCatalog
import pe.bpm.features.checklists.presentation.ChecklistCatalogViewModel
import pe.bpm.features.connection.data.RemoteConnectionRepository
import pe.bpm.features.connection.domain.CheckConnection
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.data.RemoteIdentityRepository
import pe.bpm.features.identity.data.FirebaseIdentityDataSource
import pe.bpm.features.identity.data.BpmIdentityDataSource
import pe.bpm.features.identity.data.SessionStore
import pe.bpm.features.identity.data.EphemeralSessionStore
import pe.bpm.features.identity.domain.RestoreSession
import pe.bpm.features.identity.domain.ObserveSessionInvalidations
import pe.bpm.features.identity.domain.ObserveSessionWindow
import pe.bpm.features.identity.domain.RecordSessionActivity
import pe.bpm.features.identity.domain.EndSession
import pe.bpm.features.identity.domain.GetCurrentProfile
import pe.bpm.features.identity.domain.SignIn
import pe.bpm.features.identity.domain.SignOut
import pe.bpm.features.identity.domain.SignInWithGoogle
import pe.bpm.features.identity.domain.Register
import pe.bpm.features.identity.domain.ResetPassword
import pe.bpm.features.identity.domain.ResendVerification
import pe.bpm.features.identity.domain.ConfirmEmailVerification
import pe.bpm.features.identity.data.platformGoogleAuthProvider
import pe.bpm.features.identity.data.GoogleAuthProvider
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.identity.presentation.SessionMonitor
import pe.bpm.features.workspace.data.RemoteWorkspaceRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

// Raíz de composición manual. Los ViewModels reciben casos de uso, no clientes HTTP.
class AppGraph(
    config: AppConfig,
    private val httpClient: HttpClient = createHttpClient(),
    sessionStore: SessionStore = EphemeralSessionStore(),
    googleAuthProvider: GoogleAuthProvider? = null,
    draftStorage: DraftStorage = UnavailableDraftStorage(),
    private val pushProvider:pe.bpm.features.notifications.domain.PushProvider=pe.bpm.features.notifications.domain.UnavailablePushProvider(),
) : AutoCloseable {
    private val runDrafts = ManageRunDraft(LocalChecklistRunDraftRepository(draftStorage, config.apiBaseUrl))
    private val templateDrafts = ManageTemplateDraft(LocalChecklistTemplateDraftRepository(draftStorage, config.apiBaseUrl))
    private val assignmentDrafts = ManageAssignmentDraft(LocalChecklistAssignmentDraftRepository(draftStorage, config.apiBaseUrl))
    private val workspaceDrafts = ManageWorkspaceFormDraft(LocalWorkspaceFormDraftRepository(draftStorage, config.apiBaseUrl))
    @OptIn(ExperimentalUuidApi::class)
    internal fun workspaceFormViewModel() = WorkspaceFormViewModel(workspaceDrafts, SearchMemberAccounts(workspaceRepository)) { Uuid.random().toString() }
    private val checkConnection = CheckConnection(RemoteConnectionRepository(httpClient, config))
    private val googleAuth = googleAuthProvider ?: platformGoogleAuthProvider()
    private val identityRepository = RemoteIdentityRepository(
        FirebaseIdentityDataSource(httpClient, config), BpmIdentityDataSource(httpClient, config),
        config.apiBaseUrl, googleAuth, sessionStore)
    private val workspaceRepository = RemoteWorkspaceRepository(httpClient, config, onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse, token = identityRepository::validSessionToken)
    private val checklistRepository = RemoteChecklistCatalogRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession,
        onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)

    private val templatePublisher = RemoteChecklistTemplatePublisher(httpClient, config, checklistRepository,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    @OptIn(ExperimentalUuidApi::class)
    fun checklistTemplateEditorViewModel() = ChecklistTemplateEditorViewModel(PublishChecklistTemplate(templatePublisher), templateDrafts) { Uuid.random().toString() }

    fun checklistCatalogViewModel() = ChecklistCatalogViewModel(LoadChecklistCatalog(checklistRepository), LoadChecklistTemplate(checklistRepository))

    private val runRepository = RemoteChecklistRunRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val objectClient = createMediaObjectHttpClient()
    private val mediaRepository = RemoteMediaRepository(httpClient, objectClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    @OptIn(ExperimentalUuidApi::class)
    fun mediaEvidenceViewModel() = MediaEvidenceViewModel(LoadEvidence(mediaRepository), UploadEvidence(mediaRepository),
        RemoveEvidence(mediaRepository), LoadEvidencePreview(mediaRepository)) { Uuid.random().toString() }

    private val offlineRepository = LocalChecklistOfflineRepository(draftStorage, config.apiBaseUrl)
    @OptIn(ExperimentalUuidApi::class)
    fun checklistOfflineViewModel() = ChecklistOfflineViewModel(
        ManageOfflineChecklists(offlineRepository, LoadChecklistRun(runRepository)) { Uuid.random().toString() },
        SyncOfflineChecklists(offlineRepository, SaveChecklistRun(runRepository), SubmitChecklistRun(runRepository)))

    private val historyRepository = RemoteChecklistHistoryRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    fun checklistHistoryViewModel() = ChecklistHistoryViewModel(LoadChecklistHistory(historyRepository), LoadChecklistRun(runRepository))
    private val assignmentRepository = RemoteChecklistAssignmentRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    fun checklistAssignmentsViewModel() = ChecklistAssignmentsViewModel(LoadChecklistAssignments(assignmentRepository),
        LoadChecklistAssignment(assignmentRepository), DeactivateChecklistAssignment(assignmentRepository))
    @OptIn(ExperimentalUuidApi::class)
    fun checklistAssignmentFormViewModel() = ChecklistAssignmentFormViewModel(
        CreateChecklistAssignment(assignmentRepository, assignmentRepository), assignmentDrafts) { Uuid.random().toString() }
    @OptIn(ExperimentalUuidApi::class)
    fun checklistRunViewModel() = ChecklistRunViewModel(LoadChecklistRun(runRepository), StartChecklistRun(runRepository),
        SaveChecklistRun(runRepository), SubmitChecklistRun(runRepository), { Uuid.random().toString() }, { Clock.System.now().toString() }, runDrafts)

    private val operationsRepository = RemoteOperationsRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val operationsDrafts = ManageOperationsDraft(LocalOperationsDraftRepository(draftStorage, config.apiBaseUrl))
    @OptIn(ExperimentalUuidApi::class)
    fun operationsViewModel() = OperationsViewModel(ManageOperations(operationsRepository, operationsRepository), operationsDrafts,
        { Uuid.random().toString() }, { Clock.System.now().toString() })


    private val incidentsRepository = pe.bpm.features.incidents.data.RemoteIncidentsRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val incidentDrafts = pe.bpm.features.incidents.data.LocalIncidentDraftRepository(draftStorage, config.apiBaseUrl)
    @OptIn(ExperimentalUuidApi::class)
    fun incidentsViewModel() = pe.bpm.features.incidents.presentation.IncidentsViewModel(
        pe.bpm.features.incidents.domain.ManageIncidents(incidentsRepository, incidentsRepository, incidentDrafts),
        { Uuid.random().toString() }, { Clock.System.now().toString() })

    // Academia: API autorizada y cliente separado para subir videos directo a R2 (sin token BPM).
    private val trainingObjects = createObjectStorageClient()
    private val trainingRepository = pe.bpm.features.training.data.RemoteTrainingRepository(httpClient, trainingObjects, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    @OptIn(ExperimentalUuidApi::class)
    fun trainingViewModel() = pe.bpm.features.training.presentation.TrainingViewModel(
        pe.bpm.features.training.domain.LoadTrainingCourse(trainingRepository), pe.bpm.features.training.domain.RequestLessonVideo(trainingRepository),
        pe.bpm.features.training.domain.SaveLessonProgress(trainingRepository), pe.bpm.features.training.domain.CheckStudyAnswer(trainingRepository),
        pe.bpm.features.training.domain.SubmitTrainingAttempt(trainingRepository), { Uuid.random().toString() }, { Clock.System.now().toString() })
    @OptIn(ExperimentalUuidApi::class)
    fun trainingAuthoringViewModel() = pe.bpm.features.training.presentation.TrainingAuthoringViewModel(
        pe.bpm.features.training.domain.ListAdminCourses(trainingRepository), pe.bpm.features.training.domain.CreateTrainingCourse(trainingRepository),
        pe.bpm.features.training.domain.LoadTrainingDraft(trainingRepository), pe.bpm.features.training.domain.SaveTrainingDraft(trainingRepository),
        pe.bpm.features.training.domain.PublishTrainingCourse(trainingRepository), pe.bpm.features.training.domain.UploadLessonVideo(trainingRepository)) { Uuid.random().toString() }

    // Academia ampliada: asignaciones, insignias, rutas y verificación pública.
    private val trainingPlusRepository = pe.bpm.features.training.data.RemoteTrainingPlusRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    fun trainingLearnerViewModel() = pe.bpm.features.training.presentation.TrainingLearnerViewModel(
        pe.bpm.features.training.domain.LoadMyAssignments(trainingPlusRepository), pe.bpm.features.training.domain.LoadMyAchievements(trainingPlusRepository),
        pe.bpm.features.training.domain.LoadLearningPaths(trainingPlusRepository))
    @OptIn(ExperimentalUuidApi::class)
    fun trainingAssignmentAdminViewModel() = pe.bpm.features.training.presentation.TrainingAssignmentAdminViewModel(
        pe.bpm.features.training.domain.ListTrainingAssignments(trainingPlusRepository), pe.bpm.features.training.domain.CreateTrainingAssignment(trainingPlusRepository),
        pe.bpm.features.training.domain.CancelTrainingAssignment(trainingPlusRepository), pe.bpm.features.training.domain.ListAdminCourses(trainingRepository), { Uuid.random().toString() })
    fun trainingAchievementAdminViewModel() = pe.bpm.features.training.presentation.TrainingAchievementAdminViewModel(
        pe.bpm.features.training.domain.ListTrainingAchievements(trainingPlusRepository), pe.bpm.features.training.domain.UpdateTrainingAchievement(trainingPlusRepository))
    @OptIn(ExperimentalUuidApi::class)
    fun learningPathAdminViewModel() = pe.bpm.features.training.presentation.LearningPathAdminViewModel(
        pe.bpm.features.training.domain.ManageLearningPaths(trainingPlusRepository), pe.bpm.features.training.domain.ListAdminCourses(trainingRepository)) { Uuid.random().toString() }
    fun certificateVerificationViewModel() = pe.bpm.features.training.presentation.CertificateVerificationViewModel(
        pe.bpm.features.training.domain.VerifyTrainingCertificate(trainingPlusRepository))

    private val alertsRepository = pe.bpm.features.alerts.data.RemoteAlertsRepository(httpClient,config,
        onSessionRejected=identityRepository::rejectSession,onSessionResponse=identityRepository::observeSessionResponse,
        token=identityRepository::validSessionToken)
    private val alertDrafts = pe.bpm.features.alerts.data.LocalAlertRuleDraftRepository(draftStorage,config.apiBaseUrl)
    @OptIn(ExperimentalUuidApi::class)
    fun alertsViewModel() = pe.bpm.features.alerts.presentation.AlertsViewModel(
        pe.bpm.features.alerts.domain.ManageAlerts(alertsRepository,alertsRepository,alertDrafts)) { Uuid.random().toString() }

    private val notificationDevicesRepository=pe.bpm.features.notifications.data.RemoteNotificationDevicesRepository(httpClient,config,
        onSessionRejected=identityRepository::rejectSession,onSessionResponse=identityRepository::observeSessionResponse,token=identityRepository::validSessionToken)
    fun notificationDevicesViewModel()=pe.bpm.features.notifications.presentation.NotificationDevicesViewModel(
        pe.bpm.features.notifications.domain.ManageNotificationDevices(notificationDevicesRepository,pushProvider))

    fun workspaceViewModel() = WorkspaceViewModel(LoadOrganizations(workspaceRepository),
        LoadWorkspace(workspaceRepository), CreateOrganization(workspaceRepository),
        CreateLocation(workspaceRepository), AddMember(workspaceRepository), LoadWorkspacePage(workspaceRepository))

    fun connectionViewModel(): ConnectionViewModel = ConnectionViewModel(checkConnection)
    fun identityViewModel(): IdentityViewModel = IdentityViewModel(
        SignIn(identityRepository), GetCurrentProfile(identityRepository), SignOut(identityRepository),
        SignInWithGoogle(identityRepository), googleAuth.supported,
        Register(identityRepository), ResetPassword(identityRepository), ResendVerification(identityRepository),
        ConfirmEmailVerification(identityRepository),
        RestoreSession(identityRepository), ObserveSessionInvalidations(identityRepository),
        SessionMonitor(ObserveSessionWindow(identityRepository), RecordSessionActivity(identityRepository)), EndSession(identityRepository))
    override fun close() {
        identityRepository.close()
        objectClient.close()
        trainingObjects.close()
        httpClient.close()
    }
}
