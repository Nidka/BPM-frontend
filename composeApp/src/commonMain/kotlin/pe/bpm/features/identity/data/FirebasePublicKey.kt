package pe.bpm.features.identity.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.features.identity.domain.*

/** Clave pública del cliente Firebase: viene de la configuración fija o de `/config.json` del servidor web, y se conserva una vez leída. */
internal class FirebasePublicKey(private val client: HttpClient, private val config: AppConfig) {
    private var apiKey: String? = null

    suspend fun get(): String {
        apiKey?.let { return it }
        val value = if (config.publicConfigUrl != null) {
            val response = retryingNetwork { client.get(config.publicConfigUrl) { identityTimeout() } }
            if (!response.status.isSuccess()) throw IdentityDataException(identityFailure("AUTH_NOT_CONFIGURED", IdentityDiagnostic(IdentityStage.PUBLIC_CONFIGURATION, response.status.value, "CONFIGURATION")))
            response.body<PublicConfigDto>().firebaseApiKey
        } else config.firebaseApiKey
        if (value.isBlank() || !value.matches(Regex("[A-Za-z0-9_-]{20,100}")))
            throw IdentityDataException(identityFailure("AUTH_NOT_CONFIGURED", IdentityDiagnostic(IdentityStage.PUBLIC_CONFIGURATION, reason = "CONFIGURATION")))
        apiKey = value
        return value
    }
}

@Serializable private data class PublicConfigDto(val firebaseApiKey: String = "")
