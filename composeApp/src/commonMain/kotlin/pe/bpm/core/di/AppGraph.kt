package pe.bpm.core.di

import io.ktor.client.HttpClient
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.connection.data.RemoteConnectionRepository
import pe.bpm.features.connection.domain.CheckConnection
import pe.bpm.features.connection.presentation.ConnectionViewModel
import pe.bpm.features.identity.data.RemoteIdentityRepository
import pe.bpm.features.identity.domain.GetCurrentProfile
import pe.bpm.features.identity.domain.SignIn
import pe.bpm.features.identity.domain.SignOut
import pe.bpm.features.identity.domain.SignInWithGoogle
import pe.bpm.features.identity.data.platformGoogleAuthProvider
import pe.bpm.features.identity.presentation.IdentityViewModel

// Raíz de composición manual. Los ViewModels reciben casos de uso, no clientes HTTP.
class AppGraph(
    config: AppConfig,
    private val httpClient: HttpClient = createHttpClient(),
) : AutoCloseable {
    private val checkConnection = CheckConnection(RemoteConnectionRepository(httpClient, config))
    private val googleAuth = platformGoogleAuthProvider()
    private val identityRepository = RemoteIdentityRepository(httpClient, config, googleAuth)

    fun connectionViewModel(): ConnectionViewModel = ConnectionViewModel(checkConnection)
    fun identityViewModel(): IdentityViewModel = IdentityViewModel(
        SignIn(identityRepository), GetCurrentProfile(identityRepository), SignOut(identityRepository),
        SignInWithGoogle(identityRepository), googleAuth.supported)
    override fun close() {
        identityRepository.signOut()
        httpClient.close()
    }
}
