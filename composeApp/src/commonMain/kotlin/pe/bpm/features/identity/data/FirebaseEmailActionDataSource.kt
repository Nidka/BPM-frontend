package pe.bpm.features.identity.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.features.identity.domain.*

/**
 * Acciones de los enlaces de correo de Firebase por REST (identitytoolkit). Usa la clave pública de la propia configuración
 * de BPM y nunca la `apiKey` del enlace. No guarda sesión: el código `oobCode` es de un solo uso.
 */
internal class FirebaseEmailActionDataSource(private val client: HttpClient, config: AppConfig) : EmailActionRepository {
    private val publicKey = FirebasePublicKey(client, config)

    override suspend fun verifyEmail(oobCode: String): EmailActionResult<String?> =
        call("update", ApplyCodeRequest(oobCode)) { it.body<ActionResponse>().email }

    override suspend fun checkPasswordResetCode(oobCode: String): EmailActionResult<String> {
        val result = call("resetPassword", ApplyCodeRequest(oobCode), safeToRepeat = true) { it.body<ActionResponse>().email }
        return when (result) {
            is EmailActionResult.Success -> result.value?.takeIf { it.isNotBlank() }?.let { EmailActionResult.Success(it) }
                ?: EmailActionResult.Failure(EmailActionFailure.Unavailable)
            is EmailActionResult.Failure -> result
        }
    }

    override suspend fun confirmPasswordReset(oobCode: String, newPassword: String): EmailActionResult<Unit> =
        call("resetPassword", ConfirmResetRequest(oobCode, newPassword)) { }

    /** Aplicar `RECOVER_EMAIL` revierte el cambio de correo; la respuesta trae el correo restaurado. */
    override suspend fun recoverEmail(oobCode: String): EmailActionResult<String?> =
        call("update", ApplyCodeRequest(oobCode)) { it.body<ActionResponse>().email }

    private suspend inline fun <reified B, T> call(
        action: String, body: B, safeToRepeat: Boolean = false, crossinline read: suspend (HttpResponse) -> T,
    ): EmailActionResult<T> = try {
        val key = publicKey.get()
        val send: suspend () -> HttpResponse = {
            client.post("https://identitytoolkit.googleapis.com/v1/accounts:$action") {
                url { parameters.append("key", key) }
                identityTimeout()
                contentType(ContentType.Application.Json)
                header("X-Firebase-Locale", "es")
                setBody(body)
            }
        }
        val response = if (safeToRepeat) retryingNetwork(block = send) else send()
        if (response.status.isSuccess()) EmailActionResult.Success(read(response))
        else EmailActionResult.Failure(emailActionFailure(response.status.value, rawCode(response)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IdentityDataException) {
        EmailActionResult.Failure(if (error.failure.errors.any { it.code == "AUTH_NOT_CONFIGURED" }) EmailActionFailure.Unavailable else EmailActionFailure.Network)
    } catch (_: HttpRequestTimeoutException) {
        EmailActionResult.Failure(EmailActionFailure.Network)
    } catch (_: kotlinx.serialization.SerializationException) {
        EmailActionResult.Failure(EmailActionFailure.Unavailable)
    } catch (_: Throwable) {
        // En wasm los fallos de fetch pueden llegar como Error; la cancelación ya se propagó.
        EmailActionResult.Failure(EmailActionFailure.Network)
    }

    private suspend fun rawCode(response: HttpResponse): String =
        try { response.body<ErrorEnvelope>().error.message.substringBefore(" : ") } catch (_: Throwable) { "" }
}

/** Traduce el motivo del proveedor a un fallo público; los códigos de Firebase no llegan a la pantalla. */
internal fun emailActionFailure(status: Int, providerCode: String): EmailActionFailure = when {
    status == 429 || providerCode in setOf("TOO_MANY_ATTEMPTS_TRY_LATER", "TOO_MANY_ATTEMPTS", "RESET_PASSWORD_EXCEED_LIMIT") -> EmailActionFailure.RateLimited
    status >= 500 -> EmailActionFailure.Unavailable
    providerCode in setOf("EXPIRED_OOB_CODE", "INVALID_OOB_CODE", "EMAIL_NOT_FOUND") -> EmailActionFailure.InvalidOrExpired
    providerCode == "USER_DISABLED" -> EmailActionFailure.UserDisabled
    providerCode == "WEAK_PASSWORD" || providerCode == "PASSWORD_DOES_NOT_MEET_REQUIREMENTS" -> EmailActionFailure.WeakPassword
    else -> EmailActionFailure.Unavailable
}

@Serializable private class ApplyCodeRequest(val oobCode: String)
@Serializable private class ConfirmResetRequest(val oobCode: String, val newPassword: String)
@Serializable private class ActionResponse(val email: String? = null)
@Serializable private class ErrorEnvelope(val error: ErrorBody)
@Serializable private class ErrorBody(val message: String = "")
