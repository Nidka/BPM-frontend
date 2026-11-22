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
import pe.bpm.core.network.platformHttpEngine
import pe.bpm.core.offline.HttpOutboxHandler
import pe.bpm.core.offline.OfflineHttpEngine
import pe.bpm.core.offline.OfflinePlatform
import pe.bpm.core.offline.OfflineRuntime
import pe.bpm.core.offline.OfflineViewModel
import pe.bpm.core.offline.OutboxStatus
import pe.bpm.core.offline.RuntimeOfflineIdentity
import pe.bpm.core.domain.DraftOwner
import pe.bpm.features.checklists.data.LegacyChecklistMigration
import pe.bpm.features.identity.domain.IdentityResult
import pe.bpm.features.identity.domain.permitsRestoreRetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import pe.bpm.features.checklists.data.RemoteChecklistShiftReader
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
/** Resultado de un envío en segundo plano: RETRY pide a WorkManager volver a intentarlo con backoff. */
enum class BackgroundSyncResult { DONE, RETRY }

class AppGraph(
    config: AppConfig,
    httpClientOverride: HttpClient? = null,
    sessionStore: SessionStore = EphemeralSessionStore(),
    googleAuthProvider: GoogleAuthProvider? = null,
    draftStorage: DraftStorage = UnavailableDraftStorage(),
    private val pushProvider:pe.bpm.features.notifications.domain.PushProvider=pe.bpm.features.notifications.domain.UnavailablePushProvider(),
    offline: OfflinePlatform = OfflinePlatform(),
) : AutoCloseable {
    // Offline-first: el motor HTTP guarda lecturas y difiere escrituras; la Outbox las envía al volver la red.
    private val offlineRuntime = OfflineRuntime(offline)
    private val localFirst = pe.bpm.core.offline.LocalFirstReader.Cached
    private val platformEngine = if (httpClientOverride == null) platformHttpEngine() else null
    private val offlineEngine = platformEngine?.let { OfflineHttpEngine(it, offlineRuntime.http, config.apiBaseUrl) }
    private val httpClient: HttpClient = httpClientOverride ?: createHttpClient(offlineEngine!!)
    /** Cliente sin capa offline para reenviar la Outbox sin volver a encolar. */
    private val replayClient: HttpClient = if (platformEngine != null) createHttpClient(platformEngine) else this.httpClient
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
        config.apiBaseUrl, googleAuth, sessionStore, offline = RuntimeOfflineIdentity(offlineRuntime, config.apiBaseUrl))
    /** Datos de capacitación del dispositivo: catálogo, avance, estudio y evaluaciones enviadas. */
    private val trainingStore = pe.bpm.features.training.data.TrainingOfflineStore(offlineRuntime.store, offlineRuntime.clock)
    /** Tareas de larga duración (descarga de videos) que siguen aunque se cambie de pantalla. */
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    init {
        offlineRuntime.register(HttpOutboxHandler.KIND, HttpOutboxHandler(replayClient, config.apiBaseUrl,
            identityRepository::validSessionToken, identityRepository::rejectSession, offlineRuntime.store,
            // Al sincronizar, el servidor devuelve la calificación de una evaluación hecha sin conexión: se guarda aquí.
            onResponse = { path, _, status, response -> trainingStore.onSynced(path, status, response) }))
        // Cierre de sesión explícito: también se borran los borradores heredados del dispositivo.
        offlineRuntime.onWipe { draftStorage.clearAll() }
    }
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

    fun checklistCatalogViewModel() = ChecklistCatalogViewModel(LoadChecklistCatalog(checklistRepository), LoadChecklistTemplate(checklistRepository), localFirst = localFirst)

    private val runRepository = RemoteChecklistRunRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val objectClient = createMediaObjectHttpClient()
    private val mediaRepository = RemoteMediaRepository(httpClient, objectClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val offlineMediaWriter = pe.bpm.features.media.data.OfflineMediaWriter(mediaRepository, offline.files, offlineRuntime.outbox,
        offlineRuntime.connectivity, { offlineRuntime.newId() }, offlineRuntime.clock)
    init { offlineRuntime.register(pe.bpm.core.offline.PHOTO_KIND, pe.bpm.features.media.data.PhotoOutboxHandler(mediaRepository, offline.files)) }
    @OptIn(ExperimentalUuidApi::class)
    fun mediaEvidenceViewModel() = MediaEvidenceViewModel(LoadEvidence(mediaRepository), UploadEvidence(offlineMediaWriter),
        RemoveEvidence(mediaRepository), LoadEvidencePreview(mediaRepository)) { Uuid.random().toString() }

    private val offlineRepository = LocalChecklistOfflineRepository(draftStorage, config.apiBaseUrl)
    private val legacyChecklistMigration = LegacyChecklistMigration(offlineRepository, offlineRuntime.outbox, { offlineRuntime.newId() }, offlineRuntime.clock)
    init { offlineRuntime.beforeSync { migrateLegacyChecklists() } }
    /** Traslada a la Outbox los envíos de controles preparados que la cola antigua dejó pendientes. */
    private suspend fun migrateLegacyChecklists() {
        val scope = offlineRuntime.active.current ?: return
        val profile = offlineRuntime.store.get(RuntimeOfflineIdentity.PROFILE_COLLECTION, RuntimeOfflineIdentity.PROFILE_KEY) ?: return
        val organizations = runCatching {
            Json.parseToJsonElement(profile.json).jsonObject["organizations"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        organizations.forEach { legacyChecklistMigration.migrate(DraftOwner(scope.userId, it)) }
    }
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
        LoadChecklistAssignment(assignmentRepository), DeactivateChecklistAssignment(assignmentRepository), localFirst = localFirst)
    @OptIn(ExperimentalUuidApi::class)
    fun checklistAssignmentFormViewModel() = ChecklistAssignmentFormViewModel(
        CreateChecklistAssignment(assignmentRepository, assignmentRepository), assignmentDrafts,
        { org, location -> checklistShiftReader.shifts(org, location) }) { Uuid.random().toString() }
    private val checklistShiftReader = RemoteChecklistShiftReader(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val versionRepository = pe.bpm.features.checklists.data.RemoteChecklistVersionRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    @OptIn(ExperimentalUuidApi::class)
    fun checklistVersionsViewModel() = pe.bpm.features.checklists.presentation.ChecklistVersionsViewModel(
        LoadChecklistTemplate(checklistRepository), LoadChecklistVersions(versionRepository), LoadChecklistVersion(versionRepository),
        PublishChecklistVersion(versionRepository)) { Uuid.random().toString() }
    @OptIn(ExperimentalUuidApi::class)
    fun checklistRunViewModel() = ChecklistRunViewModel(LoadChecklistRun(runRepository), StartChecklistRun(runRepository),
        SaveChecklistRun(runRepository), SubmitChecklistRun(runRepository), { Uuid.random().toString() }, { Clock.System.now().toString() }, runDrafts)

    private val operationsRepository = RemoteOperationsRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val operationsDrafts = ManageOperationsDraft(LocalOperationsDraftRepository(draftStorage, config.apiBaseUrl))
    @OptIn(ExperimentalUuidApi::class)
    private val shiftsRepository = pe.bpm.features.operations.data.RemoteShiftsRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val manageShifts = pe.bpm.features.operations.domain.ManageShifts(shiftsRepository)
    private val stockRepository = pe.bpm.features.operations.data.RemoteStockRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    @OptIn(ExperimentalUuidApi::class)
    fun operationsViewModel() = OperationsViewModel(ManageOperations(operationsRepository, operationsRepository), operationsDrafts,
        { Uuid.random().toString() }, { Clock.System.now().toString() }, { org, site, role -> manageShifts.list(org, site, role, false) }, localFirst = localFirst)
    @OptIn(ExperimentalUuidApi::class)
    fun shiftsViewModel() = pe.bpm.features.operations.presentation.ShiftsViewModel(manageShifts) { Uuid.random().toString() }
    @OptIn(ExperimentalUuidApi::class)
    fun stockMovementsViewModel() = pe.bpm.features.operations.presentation.StockMovementsViewModel(
        pe.bpm.features.operations.domain.ManageStock(stockRepository), { Uuid.random().toString() }, { Clock.System.now().toString() })


    private val incidentsRepository = pe.bpm.features.incidents.data.RemoteIncidentsRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val incidentDrafts = pe.bpm.features.incidents.data.LocalIncidentDraftRepository(draftStorage, config.apiBaseUrl)
    @OptIn(ExperimentalUuidApi::class)
    fun incidentsViewModel() = pe.bpm.features.incidents.presentation.IncidentsViewModel(
        pe.bpm.features.incidents.domain.ManageIncidents(incidentsRepository, incidentsRepository, incidentDrafts),
        { Uuid.random().toString() }, { Clock.System.now().toString() }, localFirst = localFirst)

    // Academia: API autorizada y cliente separado para subir videos directo a R2 (sin token BPM).
    private val trainingObjects = createObjectStorageClient()
    private val trainingRepository = pe.bpm.features.training.data.RemoteTrainingRepository(httpClient, trainingObjects, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    /** Aprender con o sin red: catálogo guardado, avance y evaluaciones por la Outbox, estudio con lo ya visto. */
    private val offlineTrainingRepository = pe.bpm.features.training.data.OfflineTrainingRepository(trainingRepository, trainingStore)
    private val trainingCatalog = pe.bpm.features.training.data.OfflineTrainingCatalog(trainingRepository, trainingStore)
    private val syncTrainingCatalog = pe.bpm.features.training.domain.SyncOfflineCatalog(trainingCatalog)
    // Los videos van al almacén del dispositivo (no se borra al cerrar sesión); el personal solo se usa para migrar descargas antiguas.
    private val trainingDownloads = pe.bpm.features.training.data.OfflineTrainingDownloads(offline.videos,
        pe.bpm.core.offline.VerifiedDownloader(trainingObjects, offline.videos), trainingRepository, offlineRuntime.store, appScope,
        catalog = trainingStore::catalogVideos, legacy = offline.files)
    private val trainingAttempts = object : pe.bpm.features.training.domain.TrainingAttemptLedger {
        override fun observe(courseId: String) = trainingStore.observeAttempts(courseId)
    }
    @OptIn(ExperimentalUuidApi::class)
    fun trainingViewModel() = pe.bpm.features.training.presentation.TrainingViewModel(
        pe.bpm.features.training.domain.LoadTrainingCourse(offlineTrainingRepository), pe.bpm.features.training.domain.RequestLessonVideo(offlineTrainingRepository),
        pe.bpm.features.training.domain.SaveLessonProgress(offlineTrainingRepository), pe.bpm.features.training.domain.CheckStudyAnswer(offlineTrainingRepository),
        pe.bpm.features.training.domain.SubmitTrainingAttempt(offlineTrainingRepository), { Uuid.random().toString() }, { Clock.System.now().toString() },
        downloads = trainingDownloads, attempts = trainingAttempts, syncCatalog = { org -> syncTrainingCatalog(org) })
    @OptIn(ExperimentalUuidApi::class)
    fun trainingAuthoringViewModel() = pe.bpm.features.training.presentation.TrainingAuthoringViewModel(
        pe.bpm.features.training.domain.ListAdminCourses(trainingRepository), pe.bpm.features.training.domain.CreateTrainingCourse(trainingRepository),
        pe.bpm.features.training.domain.LoadTrainingDraft(trainingRepository), pe.bpm.features.training.domain.SaveTrainingDraft(trainingRepository),
        pe.bpm.features.training.domain.PublishTrainingCourse(trainingRepository), pe.bpm.features.training.domain.UploadLessonVideo(trainingRepository)) { Uuid.random().toString() }

    // Cuentas: miembros, invitaciones, organización/sedes y dispositivos propios.
    private val accountsRepository = pe.bpm.features.accounts.data.RemoteAccountsRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    fun teamViewModel() = pe.bpm.features.accounts.presentation.TeamViewModel(
        pe.bpm.features.accounts.domain.ListAccountMembers(accountsRepository), pe.bpm.features.accounts.domain.LoadActiveLocations(accountsRepository),
        pe.bpm.features.accounts.domain.UpdateAccountMember(accountsRepository), pe.bpm.features.accounts.domain.SetMemberActive(accountsRepository),
        pe.bpm.features.accounts.domain.TransferAdministration(accountsRepository))
    fun organizationAccountViewModel() = pe.bpm.features.accounts.presentation.OrganizationViewModel(
        pe.bpm.features.accounts.domain.LoadOrganizationAccount(accountsRepository), pe.bpm.features.accounts.domain.UpdateOrganizationAccount(accountsRepository),
        pe.bpm.features.accounts.domain.ListAccountLocations(accountsRepository), pe.bpm.features.accounts.domain.UpdateAccountLocation(accountsRepository))
    @OptIn(ExperimentalUuidApi::class)
    fun invitationsViewModel() = pe.bpm.features.accounts.presentation.InvitationsViewModel(
        pe.bpm.features.accounts.domain.ListInvitations(accountsRepository), pe.bpm.features.accounts.domain.LoadActiveLocations(accountsRepository),
        pe.bpm.features.accounts.domain.InviteMember(accountsRepository), pe.bpm.features.accounts.domain.ResendInvitation(accountsRepository),
        pe.bpm.features.accounts.domain.RevokeInvitation(accountsRepository), { Uuid.random().toString() }, { Clock.System.now() })
    fun invitationAcceptViewModel() = pe.bpm.features.accounts.presentation.InvitationAcceptViewModel(pe.bpm.features.accounts.domain.AcceptInvitation(accountsRepository))
    fun devicesViewModel() = pe.bpm.features.accounts.presentation.DevicesViewModel(
        pe.bpm.features.accounts.domain.ListMyDevices(accountsRepository), pe.bpm.features.accounts.domain.RevokeMyDevice(accountsRepository))
    // Estudio CAP: participante e investigación comparten un solo adaptador autenticado.
    private val researchRepository = pe.bpm.features.research.data.RemoteResearchRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    @OptIn(ExperimentalUuidApi::class)
    fun surveyViewModel() = pe.bpm.features.research.presentation.SurveyViewModel(
        pe.bpm.features.research.domain.LoadParticipation(researchRepository), pe.bpm.features.research.domain.RecordConsent(researchRepository),
        pe.bpm.features.research.domain.SubmitSurvey(researchRepository), { Uuid.random().toString() }, { Clock.System.now().toString() }, localFirst)
    fun researchViewModel() = pe.bpm.features.research.presentation.ResearchViewModel(
        pe.bpm.features.research.domain.LoadResearchAccess(researchRepository), pe.bpm.features.research.domain.ManageAccessGrants(researchRepository),
        pe.bpm.features.research.domain.ManageConsentDocuments(researchRepository), pe.bpm.features.research.domain.ManageInstruments(researchRepository),
        pe.bpm.features.research.data.JsonInstrumentImportParser(), pe.bpm.features.research.domain.AnalyzeResearch(researchRepository)) { Clock.System.now().toString() }
    // Academia ampliada: asignaciones, insignias, rutas y verificación pública.
    private val trainingPlusRepository = pe.bpm.features.training.data.RemoteTrainingPlusRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    fun trainingLearnerViewModel() = pe.bpm.features.training.presentation.TrainingLearnerViewModel(
        pe.bpm.features.training.domain.LoadMyAssignments(trainingPlusRepository), pe.bpm.features.training.domain.LoadMyAchievements(trainingPlusRepository),
        pe.bpm.features.training.domain.LoadLearningPaths(trainingPlusRepository), syncCatalog = { org -> syncTrainingCatalog(org) }, localFirst = localFirst)
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
        pe.bpm.features.alerts.domain.ManageAlerts(alertsRepository,alertsRepository,alertDrafts), localFirst = localFirst) { Uuid.random().toString() }

    private val notificationDevicesRepository=pe.bpm.features.notifications.data.RemoteNotificationDevicesRepository(httpClient,config,
        onSessionRejected=identityRepository::rejectSession,onSessionResponse=identityRepository::observeSessionResponse,token=identityRepository::validSessionToken)
    fun notificationDevicesViewModel()=pe.bpm.features.notifications.presentation.NotificationDevicesViewModel(
        pe.bpm.features.notifications.domain.ManageNotificationDevices(notificationDevicesRepository,pushProvider))

    // Reportes (cumplimiento, KPIs, exportación, constancias) y registro de uso (RF-27).
    private val reportsRepository = pe.bpm.features.reports.data.RemoteReportsRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    private val normativeRepository = pe.bpm.features.normative.data.RemoteNormativeRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    /** Agente normativo (RF-46/47): consulta para todos y gestión de documentos para el administrador general. */
    fun normativeViewModel() = pe.bpm.features.normative.presentation.NormativeViewModel(pe.bpm.features.normative.domain.NormativeUseCases(normativeRepository))
    private val webhookRepository = pe.bpm.features.webhooks.data.RemoteWebhookRepository(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    /** Webhooks salientes para n8n (RF-48), gestionados por el ADMIN. */
    fun webhooksViewModel() = pe.bpm.features.webhooks.presentation.WebhooksViewModel(pe.bpm.features.webhooks.domain.WebhookUseCases(webhookRepository))
    fun reportsDashboardViewModel() = pe.bpm.features.reports.presentation.DashboardViewModel(pe.bpm.features.reports.domain.LoadKpis(reportsRepository))
    fun reportsViewModel() = pe.bpm.features.reports.presentation.ReportsViewModel(pe.bpm.features.reports.domain.LoadCompliance(reportsRepository),
        pe.bpm.features.reports.domain.LoadSummary(reportsRepository), pe.bpm.features.reports.domain.ExportComplianceCsv(reportsRepository))
    fun reportsAdminViewModel() = pe.bpm.features.reports.presentation.ReportsAdminViewModel(pe.bpm.features.reports.domain.LoadTrainingCourseProgress(reportsRepository),
        pe.bpm.features.reports.domain.LoadTrainingPeopleProgress(reportsRepository), pe.bpm.features.reports.domain.LoadReportSettings(reportsRepository),
        pe.bpm.features.reports.domain.SaveReportSettings(reportsRepository), pe.bpm.features.reports.domain.IssueComplianceCertificate(reportsRepository),
        pe.bpm.features.reports.domain.ListComplianceCertificates(reportsRepository))
    // Cola persistente sobre el almacén local (sobrevive a reinicios y se borra al cerrar sesión).
    private val usageQueue: pe.bpm.features.usage.domain.UsageEventQueue = pe.bpm.features.usage.data.PersistentUsageEventQueue(offlineRuntime.store)
    private val usageSink = pe.bpm.features.usage.data.RemoteUsageEventSink(httpClient, config,
        onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse,
        token = identityRepository::validSessionToken)
    @OptIn(ExperimentalUuidApi::class)
    fun usageViewModel(): pe.bpm.features.usage.presentation.UsageViewModel {
        val now = { Clock.System.now().toEpochMilliseconds() }
        val batcher = pe.bpm.features.usage.domain.UsageBatcher(usageQueue, usageSink, now)
        return pe.bpm.features.usage.presentation.UsageViewModel(pe.bpm.features.usage.domain.UsageTracker(batcher, { Uuid.random().toString() }, now))
    }

    fun workspaceViewModel() = WorkspaceViewModel(LoadOrganizations(workspaceRepository),
        LoadWorkspace(workspaceRepository), CreateOrganization(workspaceRepository),
        CreateLocation(workspaceRepository), AddMember(workspaceRepository), LoadWorkspacePage(workspaceRepository), localFirst = localFirst)

    fun offlineViewModel() = OfflineViewModel(offlineRuntime)

    /**
     * Envío con la app cerrada (WorkManager): restaura la sesión guardada si hace falta y sincroniza.
     * Devuelve RETRY mientras queden operaciones por enviar para que el planificador reintente con backoff.
     */
    suspend fun syncInBackground(): BackgroundSyncResult {
        if (offlineRuntime.active.current == null) {
            val restored = identityRepository.restoreSession()
            if (restored is IdentityResult.Failure) return if (restored.errors.all { it.permitsRestoreRetry }) BackgroundSyncResult.RETRY else BackgroundSyncResult.DONE
        }
        // Solo se envía la partición de la cuenta con sesión activa; sin sesión no se envía nada.
        if (offlineRuntime.active.current == null) return BackgroundSyncResult.DONE
        offlineRuntime.syncNow()
        val remaining = offlineRuntime.outbox.snapshot().any { it.status == OutboxStatus.PENDING && !it.exhausted }
        return if (remaining) BackgroundSyncResult.RETRY else BackgroundSyncResult.DONE
    }

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
        appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        trainingObjects.close()
        httpClient.close()
        if (platformEngine != null) replayClient.close()
        offlineEngine?.close()
    }
}
