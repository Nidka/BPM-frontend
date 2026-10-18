package pe.bpm.core.di

import io.ktor.client.HttpClient
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.connection.data.RemoteConnectionRepository
import pe.bpm.features.connection.domain.CheckConnection
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.data.RemoteIdentityRepository
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
import pe.bpm.features.identity.data.platformGoogleAuthProvider
import pe.bpm.features.identity.presentation.IdentityViewModel
import pe.bpm.features.workspace.data.RemoteWorkspaceRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

// Raíz de composición manual. Los ViewModels reciben casos de uso, no clientes HTTP.
class AppGraph(
    config: AppConfig,
    private val httpClient: HttpClient = createHttpClient(),
    sessionStore: SessionStore = EphemeralSessionStore(),
) : AutoCloseable {
    private val checkConnection = CheckConnection(RemoteConnectionRepository(httpClient, config))
    private val googleAuth = platformGoogleAuthProvider()
    private val identityRepository = RemoteIdentityRepository(httpClient, config, googleAuth, sessionStore)
    private val workspaceRepository = RemoteWorkspaceRepository(httpClient, config, onSessionRejected = identityRepository::rejectSession, onSessionResponse = identityRepository::observeSessionResponse, token = identityRepository::validSessionToken)

    fun workspaceViewModel() = WorkspaceViewModel(LoadOrganizations(workspaceRepository),
        LoadWorkspace(workspaceRepository), CreateOrganization(workspaceRepository),
        CreateLocation(workspaceRepository), AddMember(workspaceRepository))

    fun connectionViewModel(): ConnectionViewModel = ConnectionViewModel(checkConnection)
    fun identityViewModel(): IdentityViewModel = IdentityViewModel(
        SignIn(identityRepository), GetCurrentProfile(identityRepository), SignOut(identityRepository),
        SignInWithGoogle(identityRepository), googleAuth.supported,
        Register(identityRepository), ResetPassword(identityRepository), ResendVerification(identityRepository),
        RestoreSession(identityRepository), ObserveSessionInvalidations(identityRepository),
        ObserveSessionWindow(identityRepository), RecordSessionActivity(identityRepository), EndSession(identityRepository))
    override fun close() {
        identityRepository.close()
        httpClient.close()
    }
}
