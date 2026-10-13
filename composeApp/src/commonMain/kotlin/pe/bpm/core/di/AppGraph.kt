package pe.bpm.core.di

import io.ktor.client.HttpClient
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.connection.data.RemoteConnectionRepository
import pe.bpm.features.connection.domain.CheckConnection
import pe.bpm.features.connection.presentation.ConnectionViewModel

// Raíz de composición manual. Los ViewModels reciben casos de uso, no clientes HTTP.
class AppGraph(
    config: AppConfig,
    private val httpClient: HttpClient = createHttpClient(),
) : AutoCloseable {
    private val checkConnection = CheckConnection(RemoteConnectionRepository(httpClient, config))

    fun connectionViewModel(): ConnectionViewModel = ConnectionViewModel(checkConnection)
    override fun close() = httpClient.close()
}
