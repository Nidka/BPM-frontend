package pe.bpm.features.identity.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.features.identity.domain.*

/** Adaptador del contrato BPM: perfil, alta mínima y operaciones de sesión autorizadas por el servidor. */
internal interface BpmIdentityGateway {
    suspend fun profile(token: String, onSessionResponse: (HttpResponse) -> Unit): IdentityResult<Profile>
    suspend fun enroll(token: String, displayName: String, onSessionResponse: (HttpResponse) -> Unit): IdentityResult<Profile>
    suspend fun activity(token: String): HttpResponse
    suspend fun logout(token: String): IdentityResult<Unit>
}

internal class BpmIdentityDataSource(private val client: HttpClient, private val config: AppConfig) : BpmIdentityGateway {
    override suspend fun profile(token: String, onSessionResponse: (HttpResponse) -> Unit): IdentityResult<Profile> = identityOperation(IdentityStage.PROFILE_READ) {
        readProfile(client.get("${config.apiBaseUrl}/api/v1/me") { bearerAuth(token) }, onSessionResponse, IdentityStage.PROFILE_READ)
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

@Serializable private class RegisterProfileRequest(val displayName: String)
@Serializable private data class ApiErrorEnvelope(val errors: List<ApiErrorDto> = emptyList())
@Serializable private data class ApiErrorDto(val code: String)
@Serializable private data class ProfileEnvelope(val data: ProfileDto)
@Serializable private data class ProfileDto(val id: String, val email: String, val displayName: String, val organizations: List<OrganizationDto>, val platformRole: String = "USER")
@Serializable private data class OrganizationDto(val id: String, val legalName: String, val tradeName: String? = null, val role: String)

