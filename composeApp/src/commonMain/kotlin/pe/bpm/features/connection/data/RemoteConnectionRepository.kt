package pe.bpm.features.connection.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.AppFailure
import pe.bpm.core.domain.AppResult
import pe.bpm.features.connection.domain.ConnectionRepository
import pe.bpm.features.connection.domain.ConnectionStatus

class RemoteConnectionRepository(
    private val client: HttpClient,
    private val config: AppConfig,
) : ConnectionRepository {
    override suspend fun check(): AppResult<ConnectionStatus> = try {
        val health = client.get("${config.apiBaseUrl}/health")
        if (!health.status.isSuccess()) {
            AppResult.Failure(AppFailure.UNAVAILABLE)
        } else {
            val live = health.body<HealthDto>()
            val ready = client.get("${config.apiBaseUrl}/ready")
            if (!ready.status.isSuccess()) {
                AppResult.Failure(AppFailure.UNAVAILABLE)
            } else if (live.status != "ok" || ready.body<ReadyDto>().status != "ready") {
                AppResult.Failure(AppFailure.INVALID_RESPONSE)
            } else {
                AppResult.Success(ConnectionStatus(live.version))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        AppResult.Failure(AppFailure.TIMEOUT)
    } catch (_: SerializationException) {
        AppResult.Failure(AppFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) {
        AppResult.Failure(AppFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        AppResult.Failure(AppFailure.NETWORK)
    }
}

@Serializable
private data class HealthDto(val status: String, val version: String)

@Serializable
private data class ReadyDto(val status: String)
