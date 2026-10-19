package pe.bpm.features.identity.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.features.identity.domain.*

/** Adaptador de Firebase: configuración pública, credenciales y acciones de correo. No administra sesiones BPM. */
internal interface FirebaseIdentityGateway {
    suspend fun signInWithGoogle(idToken: String, nonce: String): FirebaseCredential
    suspend fun signIn(email: String, password: String): FirebaseCredential
    suspend fun signUp(email: String, password: String): FirebaseCredential
    suspend fun updateName(token: String, name: String): UpdatedProfileCredential
    suspend fun sendVerification(token: String)
    suspend fun resetPassword(email: String)
    suspend fun renew(refresh: String): RefreshCredential
}

internal class FirebaseIdentityDataSource(private val client: HttpClient, private val config: AppConfig) : FirebaseIdentityGateway {
    private var apiKey: String? = null
    override suspend fun signInWithGoogle(idToken: String, nonce: String): FirebaseCredential = identityDataRequest(IdentityStage.SIGN_IN) {
        if (idToken.length !in 20..16384 || !idToken.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) ||
            !nonce.matches(Regex("[A-Za-z0-9_-]{32,128}")))
            throw IdentityDataException(identityFailure("INVALID_RESPONSE"))
        val postBody = Parameters.build {
            append("id_token", idToken); append("providerId", "google.com"); append("nonce", nonce)
        }.formUrlEncode()
        val response = jsonPost("signInWithIdp", FederatedRequest(postBody))
        if (!response.status.isSuccess()) reject(response, IdentityStage.SIGN_IN)
        val credential = response.body<FederatedResponse>()
        if (credential.needConfirmation || credential.pendingToken.isNotEmpty() || credential.errorMessage.isNotEmpty())
            throw IdentityDataException(identityFailure("ACCOUNT_CONFLICT"))
        if (credential.refreshToken.isBlank() || (credential.expiresIn.toLongOrNull() ?: 0) !in 61..86400)
            throw IdentityDataException(identityFailure("INVALID_RESPONSE"))
        FirebaseCredential(credential.idToken, credential.localId, credential.displayName, credential.refreshToken, credential.expiresIn)
            .also { validate(it, IdentityStage.SIGN_IN) }
    }

    override suspend fun signIn(email: String, password: String): FirebaseCredential = identityDataRequest(IdentityStage.SIGN_IN) {
        val response = jsonPost("signInWithPassword", SignInRequest(email, password, true))
        if (!response.status.isSuccess()) reject(response, IdentityStage.SIGN_IN)
        response.body<FirebaseCredential>().also { validate(it, IdentityStage.SIGN_IN) }
    }

    override suspend fun signUp(email: String, password: String): FirebaseCredential = identityDataRequest(IdentityStage.REGISTRATION) {
        val response = jsonPost("signUp", SignInRequest(email, password, true))
        if (!response.status.isSuccess()) reject(response, IdentityStage.REGISTRATION)
        response.body<FirebaseCredential>().also { validate(it, IdentityStage.REGISTRATION) }
    }

    override suspend fun updateName(token: String, name: String): UpdatedProfileCredential = identityDataRequest(IdentityStage.PROFILE_UPDATE) {
        val response = jsonPost("update", UpdateNameRequest(token, name, true))
        if (!response.status.isSuccess()) reject(response, IdentityStage.PROFILE_UPDATE)
        response.body<UpdatedProfileCredential>()
    }

    override suspend fun sendVerification(token: String): Unit = identityDataRequest(IdentityStage.VERIFICATION_EMAIL) {
        val response = jsonPost("sendOobCode", VerificationRequest("VERIFY_EMAIL", token), spanish = true)
        if (!response.status.isSuccess()) reject(response, IdentityStage.VERIFICATION_EMAIL)
    }

    override suspend fun resetPassword(email: String): Unit = identityDataRequest(IdentityStage.PASSWORD_RECOVERY) {
        val response = jsonPost("sendOobCode", ResetRequest("PASSWORD_RESET", email), spanish = true)
        if (!response.status.isSuccess()) {
            if (response.status.value == 400 && providerCode(response) == "EMAIL_NOT_FOUND") return@identityDataRequest
            reject(response, IdentityStage.PASSWORD_RECOVERY)
        }
    }

    override suspend fun renew(refresh: String): RefreshCredential = identityDataRequest(IdentityStage.TOKEN_RENEWAL) {
        val response = client.submitForm(
            url = "https://securetoken.googleapis.com/v1/token?key=${key()}",
            formParameters = Parameters.build { append("grant_type", "refresh_token"); append("refresh_token", refresh) },
        )
        if (!response.status.isSuccess()) reject(response, IdentityStage.TOKEN_RENEWAL)
        response.body<RefreshCredential>().also {
            if (it.idToken.isBlank() || it.refreshToken.isBlank() || it.uid.isBlank() || (it.expiresIn.toLongOrNull() ?: 0) !in 61..86400)
                throw IdentityDataException(identityFailure("INVALID_RESPONSE", IdentityDiagnostic(IdentityStage.TOKEN_RENEWAL, response.status.value, "RESPONSE_FORMAT")))
        }
    }

    private fun validate(credential: FirebaseCredential, stage: IdentityStage) {
        if (credential.idToken.isBlank() || credential.localId.isBlank())
            throw IdentityDataException(identityFailure("INVALID_RESPONSE", IdentityDiagnostic(stage, reason = "RESPONSE_FORMAT")))
    }

    private suspend inline fun <reified T> jsonPost(action: String, body: T, spanish: Boolean = false): HttpResponse {
        val publicKey = key()
        return client.post("https://identitytoolkit.googleapis.com/v1/accounts:$action") {
            url { parameters.append("key", publicKey) }
            contentType(ContentType.Application.Json)
            if (spanish) header("X-Firebase-Locale", "es")
            setBody(body)
        }
    }

    private suspend fun key(): String {
        apiKey?.let { return it }
        val value = if (config.publicConfigUrl != null) {
            val response = client.get(config.publicConfigUrl)
            if (!response.status.isSuccess()) throw IdentityDataException(identityFailure("AUTH_NOT_CONFIGURED", IdentityDiagnostic(IdentityStage.PUBLIC_CONFIGURATION, response.status.value, "CONFIGURATION")))
            response.body<PublicConfigDto>().firebaseApiKey
        } else config.firebaseApiKey
        if (value.isBlank() || !value.matches(Regex("[A-Za-z0-9_-]{20,100}")))
            throw IdentityDataException(identityFailure("AUTH_NOT_CONFIGURED", IdentityDiagnostic(IdentityStage.PUBLIC_CONFIGURATION, reason = "CONFIGURATION")))
        apiKey = value
        return value
    }

    private suspend fun providerCode(response: HttpResponse) = response.body<FirebaseErrorEnvelope>().error.message.substringBefore(" : ")

    private suspend fun reject(response: HttpResponse, stage: IdentityStage): Nothing {
        val raw = if (response.status.value in 400..499 && response.status.value != 429) providerCode(response) else ""
        val code = when {
            response.status.value == 429 -> "RATE_LIMITED"
            response.status.value >= 500 -> "AUTH_UNAVAILABLE"
            stage == IdentityStage.SIGN_IN && raw in setOf("EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "USER_DISABLED") -> "INVALID_CREDENTIALS"
            stage == IdentityStage.TOKEN_RENEWAL && raw in setOf("TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_DISABLED", "USER_NOT_FOUND") -> "UNAUTHENTICATED"
            else -> when (raw) {
                "OPERATION_NOT_ALLOWED", "CONFIGURATION_NOT_FOUND", "API_KEY_INVALID" -> "AUTH_NOT_CONFIGURED"
                "TOO_MANY_ATTEMPTS_TRY_LATER", "TOO_MANY_ATTEMPTS", "RESET_PASSWORD_EXCEED_LIMIT" -> "RATE_LIMITED"
                "WEAK_PASSWORD", "PASSWORD_DOES_NOT_MEET_REQUIREMENTS" -> "PASSWORD_POLICY"
                "INVALID_ID_TOKEN", "TOKEN_EXPIRED" -> "UNAUTHENTICATED"
                "EMAIL_EXISTS" -> if (stage == IdentityStage.REGISTRATION) "REGISTRATION_REJECTED" else "ACCOUNT_CONFLICT"
                "FEDERATED_USER_ID_ALREADY_LINKED", "ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "ACCOUNT_CONFLICT"
                "INVALID_IDP_RESPONSE", "INVALID_NONCE" -> "GOOGLE_UNAVAILABLE"
                "INVALID_EMAIL" -> "INVALID_EMAIL"
                "QUOTA_EXCEEDED" -> "EMAIL_DELIVERY_UNAVAILABLE"
                else -> if (stage == IdentityStage.REGISTRATION) "REGISTRATION_UNAVAILABLE" else "AUTH_UNAVAILABLE"
            }
        }
        throw IdentityDataException(identityFailure(code, IdentityDiagnostic(stage, response.status.value, code)))
    }
}

@Serializable private class SignInRequest(val email: String, val password: String, val returnSecureToken: Boolean)
@Serializable private class FederatedRequest(val postBody: String, val requestUri: String = "http://localhost", val returnSecureToken: Boolean = true)
@Serializable private class FederatedResponse(
    val idToken: String = "", val localId: String = "", val displayName: String = "Usuario BPM",
    val refreshToken: String = "", val expiresIn: String = "3600", val needConfirmation: Boolean = false,
    val pendingToken: String = "", val errorMessage: String = "",
)
@Serializable internal class FirebaseCredential(val idToken: String, val localId: String, val displayName: String = "Usuario BPM", val refreshToken: String = "", val expiresIn: String = "3600")
@Serializable private class UpdateNameRequest(val idToken: String, val displayName: String, val returnSecureToken: Boolean)
@Serializable internal class UpdatedProfileCredential(val localId: String, val idToken: String? = null, val refreshToken: String? = null)

@Serializable private class VerificationRequest(val requestType: String, val idToken: String)
@Serializable private class ResetRequest(val requestType: String, val email: String)
@Serializable private data class FirebaseErrorEnvelope(val error: FirebaseError)
@Serializable private data class FirebaseError(val message: String)
@Serializable private data class PublicConfigDto(val firebaseApiKey: String = "")

@Serializable internal class RefreshCredential(
    @kotlinx.serialization.SerialName("id_token") val idToken: String,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String,
    @kotlinx.serialization.SerialName("user_id") val uid: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: String,
)
