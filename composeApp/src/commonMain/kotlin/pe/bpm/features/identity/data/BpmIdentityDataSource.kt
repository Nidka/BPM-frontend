package pe.bpm.features.identity.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.features.identity.domain.*

/** Adaptador del contrato BPM: perfil, alta mínima y operaciones de sesión autorizadas por el servidor. */
internal interface BpmIdentityGateway {
    suspend fun profile(token: String, onSessionResponse: (HttpResponse) -> Unit): IdentityResult<Profile>
    suspend fun enroll(token: String, displayName: String, onSessionResponse: (HttpResponse) -> Unit): IdentityResult<Profile>
    suspend fun activity(token: String): HttpResponse
    suspend fun logout(token: String): IdentityResult<Unit>
    /** Pide al backend de BPM enviar el correo de verificación (en español, con la marca BPM). [MailAttempt.UseFallback]: usar Firebase. */
    suspend fun sendVerificationEmail(token: String): MailAttempt
    /** Pide al backend de BPM enviar el correo de nueva contraseña. Responde igual exista o no la cuenta. */
    suspend fun sendPasswordResetEmail(email: String): MailAttempt
}

/**
 * Resultado de pedir un correo propio al backend. El respaldo existe porque el envío de BPM puede no estar disponible
 * (sin proveedor configurado, proveedor caído, backend anterior sin la ruta o sin red hacia la API): en ese caso se usa
 * el correo de Firebase, que siempre estuvo disponible. Un límite de frecuencia (429) o un correo inválido (422) **no** se
 * respaldan: se informan, porque Firebase tiene su propio límite y reintentar por otra vía lo esquivaría.
 */
internal enum class MailAttempt { Sent, UseFallback }

/** Tiempo máximo del intento de correo propio; si se agota se usa el respaldo sin dejar a la persona esperando. */
private const val MAIL_ATTEMPT_TIMEOUT_MS = 15_000L

internal class BpmIdentityDataSource(private val client: HttpClient, private val config: AppConfig) : BpmIdentityGateway {
    override suspend fun profile(token: String, onSessionResponse: (HttpResponse) -> Unit): IdentityResult<Profile> = identityOperation(IdentityStage.PROFILE_READ) {
        readProfile(retryingNetwork { client.get("${config.apiBaseUrl}/api/v1/me") { bearerAuth(token); identityTimeout() } }, onSessionResponse, IdentityStage.PROFILE_READ)
    }

    override suspend fun enroll(token: String, displayName: String, onSessionResponse: (HttpResponse) -> Unit): IdentityResult<Profile> = identityOperation(IdentityStage.PROFILE_ENROLLMENT) {
        val safeName = displayName.trim().takeIf { it.length in 2..100 && it.none { char -> char.code < 32 || char.code in 127..159 } }
        val response = client.post("${config.apiBaseUrl}/api/v1/auth/register") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(RegisterProfileRequest(safeName ?: "Usuario BPM"))
        }
        readProfile(response, onSessionResponse, IdentityStage.PROFILE_ENROLLMENT)
    }

    override suspend fun activity(token: String): HttpResponse = client.post("${config.apiBaseUrl}/api/v1/auth/activity") { bearerAuth(token) }

    override suspend fun logout(token: String): IdentityResult<Unit> = identityOperation(IdentityStage.SIGN_OUT) {
        val response = client.post("${config.apiBaseUrl}/api/v1/auth/logout") { bearerAuth(token) }
        when {
            response.status.isSuccess() -> IdentityResult.Success(Unit)
            response.status.value == 401 && response.body<ApiErrorEnvelope>().errors.any { it.code == "SESSION_EXPIRED" } -> IdentityResult.Success(Unit)
            else -> identityFailure("AUTH_UNAVAILABLE", IdentityDiagnostic(IdentityStage.SIGN_OUT, response.status.value, "AUTH_UNAVAILABLE"))
        }
    }

    override suspend fun sendVerificationEmail(token: String): MailAttempt = mailAttempt(IdentityStage.VERIFICATION_EMAIL) {
        client.post("${config.apiBaseUrl}/api/v1/auth/email/verification") {
            bearerAuth(token)
            timeout { requestTimeoutMillis = MAIL_ATTEMPT_TIMEOUT_MS; socketTimeoutMillis = MAIL_ATTEMPT_TIMEOUT_MS }
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): MailAttempt = mailAttempt(IdentityStage.PASSWORD_RECOVERY) {
        client.post("${config.apiBaseUrl}/api/v1/auth/email/password-reset") {
            contentType(ContentType.Application.Json)
            setBody(PasswordResetMailRequest(email))
            timeout { requestTimeoutMillis = MAIL_ATTEMPT_TIMEOUT_MS; socketTimeoutMillis = MAIL_ATTEMPT_TIMEOUT_MS }
        }
    }

    /** Ejecuta la llamada y decide entre éxito, error público (429/422) o respaldo; nunca registra cuerpos ni enlaces. */
    private suspend fun mailAttempt(stage: IdentityStage, call: suspend () -> HttpResponse): MailAttempt {
        val response = try { call() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Throwable) { return MailAttempt.UseFallback }
        return when {
            response.status.isSuccess() -> MailAttempt.Sent
            response.status.value == 429 -> throw IdentityDataException(identityFailure("RATE_LIMITED", IdentityDiagnostic(stage, 429, "RATE_LIMITED")))
            response.status.value == 422 && stage == IdentityStage.PASSWORD_RECOVERY ->
                throw IdentityDataException(identityFailure("INVALID_EMAIL", IdentityDiagnostic(stage, 422, "INVALID_EMAIL")))
            else -> MailAttempt.UseFallback
        }
    }

    private suspend fun readProfile(response: HttpResponse, onSessionResponse: (HttpResponse) -> Unit, stage: IdentityStage): IdentityResult<Profile> {
        if (!response.status.isSuccess()) {
            val code = response.body<ApiErrorEnvelope>().errors.firstOrNull()?.code
            val publicCode = when (response.status.value) {
                401 -> if (code == "SESSION_EXPIRED") code else "UNAUTHENTICATED"
                403 -> when (code) { "USER_INACTIVE", "EMAIL_NOT_VERIFIED", "USER_NOT_REGISTERED" -> code; else -> "ACCESS_DENIED" }
                409 -> "REGISTRATION_CONFLICT"
                422 -> "INVALID_PROFILE_NAME"
                429 -> "RATE_LIMITED"
                else -> "AUTH_UNAVAILABLE"
            }
            return identityFailure(publicCode, IdentityDiagnostic(stage, response.status.value, publicCode))
        }
        val profile = response.body<ProfileEnvelope>().data
        if (profile.id.isBlank() || profile.email.isBlank() || profile.displayName.isBlank()) return identityFailure("INVALID_RESPONSE")
        onSessionResponse(response)
        return IdentityResult.Success(Profile(profile.id, profile.email, profile.displayName,
            profile.organizations.map { Organization(it.id, it.tradeName?.takeIf(String::isNotBlank) ?: it.legalName, it.role) }, profile.platformRole))
    }

}

@Serializable private class PasswordResetMailRequest(val email: String)
@Serializable private class RegisterProfileRequest(val displayName: String)
@Serializable private data class ApiErrorEnvelope(val errors: List<ApiErrorDto> = emptyList())
@Serializable private data class ApiErrorDto(val code: String)
@Serializable private data class ProfileEnvelope(val data: ProfileDto)
@Serializable private data class ProfileDto(val id: String, val email: String, val displayName: String, val organizations: List<OrganizationDto>, val platformRole: String = "USER")
@Serializable private data class OrganizationDto(val id: String, val legalName: String, val tradeName: String? = null, val role: String)

