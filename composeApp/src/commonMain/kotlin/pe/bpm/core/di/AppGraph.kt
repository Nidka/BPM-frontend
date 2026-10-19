package pe.bpm.core.di

import io.ktor.client.HttpClient
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.checklists.data.RemoteChecklistCatalogRepository
import pe.bpm.features.checklists.data.RemoteChecklistTemplatePublisher
import pe.bpm.features.checklists.data.LocalChecklistRunDraftRepository
import pe.bpm.features.checklists.data.LocalChecklistTemplateDraftRepository
import pe.bpm.features.workspace.data.LocalWorkspaceFormDraftRepository
import pe.bpm.features.workspace.presentation.WorkspaceFormViewModel
import pe.bpm.core.storage.DraftStorage
import pe.bpm.core.storage.UnavailableDraftStorage
import pe.bpm.features.checklists.data.RemoteChecklistRunRepository
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
) : AutoCloseable {
    private val runDrafts = ManageRunDraft(LocalChecklistRunDraftRepository(draftStorage, config.apiBaseUrl))
    private val templateDrafts = ManageTemplateDraft(LocalChecklistTemplateDraftRepository(draftStorage, config.apiBaseUrl))
    private val workspaceDrafts = ManageWorkspaceFormDraft(LocalWorkspaceFormDraftRepository(draftStorage, config.apiBaseUrl))
    @OptIn(ExperimentalUuidApi::class)
    internal fun workspaceFormViewModel() = WorkspaceFormViewModel(workspaceDrafts) { Uuid.random().toString() }
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
    @OptIn(ExperimentalUuidApi::class)
    fun checklistRunViewModel() = ChecklistRunViewModel(LoadChecklistRun(runRepository), StartChecklistRun(runRepository),
        SaveChecklistRun(runRepository), SubmitChecklistRun(runRepository), { Uuid.random().toString() }, { Clock.System.now().toString() }, runDrafts)

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
        httpClient.close()
    }
}
