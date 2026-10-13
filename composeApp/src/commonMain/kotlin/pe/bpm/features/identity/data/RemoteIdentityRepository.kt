package pe.bpm.features.identity.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import pe.bpm.core.config.AppConfig
import pe.bpm.features.identity.domain.*

class RemoteIdentityRepository(
    private val client: HttpClient,
    private val config: AppConfig,
) : IdentityRepository {
    // Solo memoria. No usar almacenamiento del navegador ni guardar la contraseña.
    private var idToken: String? = null
    private var apiKey: String? = null

    override suspend fun signIn(email: String, password: String): IdentityResult<Profile> {
        signOut()
        return safely {
            val key = firebaseKey()
            if (key.isBlank()) return@safely failure("AUTH_NOT_CONFIGURED")
            val response = client.post("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword") {
                url { parameters.append("key", key) }
                contentType(ContentType.Application.Json)
                setBody(SignInRequest(email, password, returnSecureToken = true))
            }
            if (!response.status.isSuccess()) {
                if (response.status.value == 429) return@safely failure("RATE_LIMITED")
                if (response.status.value >= 500) return@safely failure("AUTH_UNAVAILABLE")
                val code = response.body<FirebaseErrorEnvelope>().error.message.substringBefore(" : ")
                return@safely when (code) {
                    "EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "USER_DISABLED" -> failure("INVALID_CREDENTIALS")
                    "TOO_MANY_ATTEMPTS_TRY_LATER" -> failure("RATE_LIMITED")
                    "OPERATION_NOT_ALLOWED", "CONFIGURATION_NOT_FOUND", "API_KEY_INVALID" -> failure("AUTH_NOT_CONFIGURED")
                    else -> failure("AUTH_UNAVAILABLE")
                }
            }
            val credential = response.body<FirebaseCredential>()
            if (credential.idToken.isBlank() || credential.localId.isBlank()) return@safely failure("INVALID_RESPONSE")
            val profile = fetchProfile(credential.idToken)
            if (profile is IdentityResult.Success) {
                if (profile.value.id != credential.localId) return@safely failure("INVALID_RESPONSE")
                idToken = credential.idToken
            }
            profile
        }
    }

    override suspend fun currentProfile(): IdentityResult<Profile> = safely {
        val token = idToken ?: return@safely failure("UNAUTHENTICATED")
        val result = fetchProfile(token)
        if (result is IdentityResult.Failure && result.errors.any {
            it.code in setOf("UNAUTHENTICATED", "USER_INACTIVE", "USER_NOT_REGISTERED")
        }) signOut()
        result
    }

    override fun signOut() { idToken = null }

    private suspend fun firebaseKey(): String {
        apiKey?.let { return it }
        val value = if (config.publicConfigUrl != null) {
            val response = client.get(config.publicConfigUrl)
            if (!response.status.isSuccess()) return ""
            response.body<PublicConfigDto>().firebaseApiKey
        } else config.firebaseApiKey
        require(value.isEmpty() || value.matches(Regex("[A-Za-z0-9_-]{20,100}")))
        if (value.isNotEmpty()) apiKey = value
        return value
    }

    private suspend fun fetchProfile(token: String): IdentityResult<Profile> {
        val response = client.get("${config.apiBaseUrl}/api/v1/me") { bearerAuth(token) }
        if (!response.status.isSuccess()) {
            val code = response.body<ApiErrorEnvelope>().errors.firstOrNull()?.code
            return when (response.status.value) {
                401 -> failure("UNAUTHENTICATED")
                403 -> failure(if (code == "USER_INACTIVE") "USER_INACTIVE" else "USER_NOT_REGISTERED")
                429 -> failure("RATE_LIMITED")
                503 -> failure(if (code == "AUTHENTICATION_UNAVAILABLE") "AUTH_NOT_CONFIGURED" else "AUTH_UNAVAILABLE")
                else -> failure("AUTH_UNAVAILABLE")
            }
        }
        val profile = response.body<ProfileEnvelope>().data
        if (profile.id.isBlank() || profile.email.isBlank() || profile.displayName.isBlank()) return failure("INVALID_RESPONSE")
        return IdentityResult.Success(Profile(profile.id, profile.email, profile.displayName,
            profile.organizations.map { Organization(it.id, it.tradeName?.takeIf(String::isNotBlank) ?: it.legalName, it.role) }))
    }

    private suspend fun safely(block: suspend () -> IdentityResult<Profile>): IdentityResult<Profile> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        failure("TIMEOUT")
    } catch (_: SerializationException) {
        failure("INVALID_RESPONSE")
    } catch (_: JsonConvertException) {
        failure("INVALID_RESPONSE")
    } catch (_: Exception) {
        failure("NETWORK_ERROR")
    }
}

private fun failure(code: String): IdentityResult.Failure {
    val message = when (code) {
        "INVALID_CREDENTIALS" -> "El correo o la contraseña no son correctos."
        "AUTH_NOT_CONFIGURED" -> "El inicio de sesión aún no está configurado. Contacta al administrador."
        "USER_NOT_REGISTERED" -> "Tu cuenta no está habilitada en BPM. Contacta al administrador."
        "USER_INACTIVE" -> "Tu cuenta se encuentra inactiva. Contacta al administrador."
        "UNAUTHENTICATED" -> "Tu sesión terminó. Inicia sesión nuevamente."
        "RATE_LIMITED" -> "Demasiados intentos. Espera un momento antes de volver a intentar."
        "TIMEOUT" -> "La conexión tardó demasiado. Inténtalo nuevamente."
        "NETWORK_ERROR" -> "No pudimos conectar. Revisa tu conexión a internet."
        "INVALID_RESPONSE" -> "No pudimos verificar tu sesión. Inténtalo nuevamente."
        else -> "El servicio de autenticación no está disponible. Inténtalo nuevamente."
    }
    return IdentityResult.Failure(listOf(IdentityError(code, message)))
}

@Serializable private class SignInRequest(val email: String, val password: String, val returnSecureToken: Boolean)
@Serializable private class FirebaseCredential(val idToken: String, val localId: String)
@Serializable private data class FirebaseErrorEnvelope(val error: FirebaseError)
@Serializable private data class FirebaseError(val message: String)
@Serializable private data class PublicConfigDto(val firebaseApiKey: String = "")
@Serializable private data class ApiErrorEnvelope(val errors: List<ApiErrorDto> = emptyList())
@Serializable private data class ApiErrorDto(val code: String)
@Serializable private data class ProfileEnvelope(val data: ProfileDto)
@Serializable private data class ProfileDto(val id: String, val email: String, val displayName: String, val organizations: List<OrganizationDto>)
@Serializable private data class OrganizationDto(val id: String, val legalName: String, val tradeName: String? = null, val role: String)
