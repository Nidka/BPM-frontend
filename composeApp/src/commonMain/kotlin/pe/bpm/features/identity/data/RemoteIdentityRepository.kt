package pe.bpm.features.identity.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import pe.bpm.core.network.SessionAccessException
import io.ktor.client.statement.HttpResponse
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
    private val googleAuth: GoogleAuthProvider = platformGoogleAuthProvider(),
    private val sessionStore: SessionStore = EphemeralSessionStore(),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : IdentityRepository {
    // Solo memoria. No usar almacenamiento del navegador ni guardar la contraseña.
    private var idToken: String? = null
    private var refreshToken: String? = null
    private var sessionUid: String? = null
    private var expiresAt: Long = 0
    private var generation: Long = 0
    private val refreshMutex = Mutex()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun sessionInvalidations() = invalidations.asSharedFlow()

    private var apiKey: String? = null
    private var verificationToken: String? = null
    private var pendingName: String? = null
    private var pendingUid: String? = null

    // Solo el composition root entrega este acceso a otros adaptadores de red.
    fun sessionToken(): String? = idToken

    /** Single-flight refresh; used only by adapters wired in AppGraph. */
    suspend fun validSessionToken(): String? = refreshMutex.withLock {
        val token = idToken ?: return@withLock null
        if (nowSeconds() < expiresAt - 60) return@withLock token
        val refresh = refreshToken ?: run { invalidateSession(); throw SessionAccessException(true) }
        val attempt = generation
        val credential = renew(refresh)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) throw SessionAccessException(true)
        if (credential.uid != sessionUid) { invalidateSession(); throw SessionAccessException(true) }
        persist(credential.uid, credential.refreshToken)
        idToken = credential.idToken
        refreshToken = credential.refreshToken
        expiresAt = nowSeconds() + credential.expiresIn.toLong()
        credential.idToken
    }

    fun rejectSession(token: String) {
        if (token == idToken) invalidateSession()
    }

    fun invalidateSession() {
        signOut()
        invalidations.tryEmit(Unit)
    }

    override suspend fun restoreSession(): IdentityResult<Profile> = safely {
        val attempt = generation
        val raw = sessionStore.read() ?: return@safely failure("UNAUTHENTICATED")
        val stored = try { Json.decodeFromString<StoredSession>(raw) } catch (_: Exception) {
            sessionStore.clear(); return@safely failure("UNAUTHENTICATED")
        }
        if (stored.origin != config.apiBaseUrl || stored.uid.isBlank() || stored.refreshToken.isBlank()) {
            sessionStore.clear(); return@safely failure("UNAUTHENTICATED")
        }
        refreshMutex.withLock {
            val credential = renew(stored.refreshToken)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return@withLock failure("UNAUTHENTICATED")
            if (credential.uid != stored.uid) { signOut(); return@withLock failure("INVALID_RESPONSE") }
            persist(credential.uid, credential.refreshToken)
            val profile = fetchProfile(credential.idToken)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return@withLock failure("UNAUTHENTICATED")
            if (profile is IdentityResult.Success && profile.value.id == stored.uid) {
                idToken = credential.idToken
                refreshToken = credential.refreshToken
                sessionUid = credential.uid
                expiresAt = nowSeconds() + credential.expiresIn.toLong()
                profile
            } else {
                // Preserve credentials on transient failures so the user can retry restoration.
                if (profile is IdentityResult.Success || (profile is IdentityResult.Failure && profile.errors.any { it.code in sessionDenials })) signOut()
                if (profile is IdentityResult.Success) failure("INVALID_RESPONSE") else profile
            }
        }
    }

    private fun persist(uid: String, refresh: String) {
        try {
            if (refresh.isBlank()) sessionStore.clear()
            else sessionStore.write(Json.encodeToString(StoredSession(config.apiBaseUrl, uid, refresh)))
        } catch (_: Exception) {
            invalidateSession()
            throw SessionAccessException(true)
        }
    }

    private suspend fun renew(refresh: String): RefreshCredential {
        val attempt = generation
        val key = firebaseKey()
        if (key.isBlank()) throw SessionAccessException(false)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) throw SessionAccessException(true)
        val response = client.submitForm(
            url = "https://securetoken.googleapis.com/v1/token?key=$key",
            formParameters = Parameters.build { append("grant_type", "refresh_token"); append("refresh_token", refresh) },
        )
        currentCoroutineContext().ensureActive()
        if (attempt != generation) throw SessionAccessException(true)
        if (!response.status.isSuccess()) {
            // Only definitive credential rejection ends a session; network/5xx/429 remain retryable.
            val code = if (response.status.value == 400) response.body<FirebaseErrorEnvelope>().error.message.substringBefore(" : ") else ""
            if (code in setOf("TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN", "USER_DISABLED", "USER_NOT_FOUND")) {
                invalidateSession(); throw SessionAccessException(true)
            }
            throw SessionAccessException(false)
        }
        val credential = response.body<RefreshCredential>()
        if (credential.idToken.isBlank() || credential.refreshToken.isBlank() || credential.uid.isBlank() ||
            (credential.expiresIn.toLongOrNull() ?: 0) !in 61..86400) throw SessionAccessException(false)
        return credential
    }

    /** Graph disposal preserves the mobile refresh credential; explicit logout erases it. */
    fun close() {
        generation++
        idToken = null; refreshToken = null; sessionUid = null
        verificationToken = null; pendingName = null; pendingUid = null
        googleAuth.clear()
    }

    override suspend fun signIn(email: String, password: String): IdentityResult<Profile> {
        signOut()
        val attempt = generation
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
            acceptCredential(credential.idToken, credential.localId, credential.displayName, credential.refreshToken, credential.expiresIn, attempt)
        }
    }

    override suspend fun signInWithGoogle(): IdentityResult<Profile> {
        // El puente controla su propia limpieza. No cancelar ni cerrar el SDK
        // justo antes de abrir una nueva ventana OAuth.
        generation++
        idToken = null; refreshToken = null; sessionUid = null
        sessionStore.clear()
        verificationToken = null
        pendingName = null
        pendingUid = null
        val attempt = generation
        return safely {
            when (val result = googleAuth.signIn()) {
                is GoogleAuthResult.Failure -> failure(result.code)
                is GoogleAuthResult.Success -> acceptCredential(result.credential.idToken, result.credential.uid, result.credential.displayName, result.credential.refreshToken, result.credential.expiresIn, attempt)
            }
        }
    }

    private suspend fun acceptCredential(token: String, uid: String, displayName: String, refresh: String, expires: String, attempt: Long): IdentityResult<Profile> {
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return failure("UNAUTHENTICATED")
        if (token.isBlank() || uid.isBlank() || (expires.toLongOrNull() ?: 0) !in 61..86400) return failure("INVALID_RESPONSE")
        var profile = fetchProfile(token)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return failure("UNAUTHENTICATED")
        if (profile is IdentityResult.Failure && profile.errors.any { it.code == "USER_NOT_REGISTERED" }) {
            val response = client.post("${config.apiBaseUrl}/api/v1/auth/register") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                // Google permite nombres cortos o vacíos. No bloquear el alta por
                // un nombre externo que no cumple el formulario BPM.
                val safeName = displayName.trim().takeIf { it.length in 2..100 && it.none { char -> char.code < 32 || char.code in 127..159 } }
                setBody(RegisterProfileRequest(safeName ?: "Usuario BPM"))
            }
            profile = readProfile(response)
        }
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return failure("UNAUTHENTICATED")
        if (profile is IdentityResult.Failure && profile.errors.any { it.code == "EMAIL_NOT_VERIFIED" }) verificationToken = token
        if (profile is IdentityResult.Success) {
            if (profile.value.id != uid) return failure("INVALID_RESPONSE")
            persist(uid, refresh)
            idToken = token
            refreshToken = refresh.takeIf { it.isNotBlank() }
            sessionUid = uid
            expiresAt = nowSeconds() + expires.toLong()
        }
        return profile
    }

    override suspend fun currentProfile(): IdentityResult<Profile> = safely {
        val attempt = generation
        val token = validSessionToken() ?: return@safely failure("UNAUTHENTICATED")
        val result = fetchProfile(token)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return@safely failure("UNAUTHENTICATED")
        if (result is IdentityResult.Success && result.value.id != sessionUid) { invalidateSession(); return@safely failure("INVALID_RESPONSE") }
        if (result is IdentityResult.Failure && result.errors.any { it.code in sessionDenials }) invalidateSession()
        result
    }

    override fun signOut() {
        close()
        sessionStore.clear()
    }

    override suspend fun register(name: String, email: String, password: String): IdentityResult<Unit> {
        signOut()
        return safely {
            val key = firebaseKey()
            if (key.isBlank()) return@safely failure("AUTH_NOT_CONFIGURED")
            val created = client.post("https://identitytoolkit.googleapis.com/v1/accounts:signUp") {
                url { parameters.append("key", key) }; contentType(ContentType.Application.Json)
                setBody(SignInRequest(email, password, returnSecureToken = true))
            }
            if (!created.status.isSuccess()) return@safely firebaseFailure(created, registration = true)
            val credential = created.body<FirebaseCredential>()
            if (credential.idToken.isBlank() || credential.localId.isBlank()) return@safely failure("INVALID_RESPONSE")
            verificationToken = credential.idToken
            pendingName = name
            pendingUid = credential.localId
            val completed = resendVerification()
            // Firebase ya creó la cuenta: no ofrecer otro signUp como si hubiera
            // fallado toda la operación ni borrar la identidad del proveedor.
            if (completed is IdentityResult.Failure) failure("REGISTRATION_INCOMPLETE") else completed
        }
    }

    override suspend fun resendVerification(): IdentityResult<Unit> = safely {
        var token = verificationToken ?: return@safely failure("UNAUTHENTICATED")
        val key = firebaseKey()
        if (key.isBlank()) return@safely failure("AUTH_NOT_CONFIGURED")
        pendingName?.let { name ->
            val updated = client.post("https://identitytoolkit.googleapis.com/v1/accounts:update") {
                url { parameters.append("key", key) }; contentType(ContentType.Application.Json)
                setBody(UpdateNameRequest(token, name, returnSecureToken = true))
            }
            if (!updated.status.isSuccess()) return@safely firebaseFailure(updated)
            val credential = updated.body<FirebaseCredential>()
            if (credential.localId != pendingUid || credential.idToken.isBlank()) return@safely failure("INVALID_RESPONSE")
            token = credential.idToken
            verificationToken = token
            pendingName = null
            pendingUid = null
        }
        val response = client.post("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode") {
            url { parameters.append("key", key) }; contentType(ContentType.Application.Json)
            header("X-Firebase-Locale", "es")
            setBody(VerificationRequest("VERIFY_EMAIL", token))
        }
        if (!response.status.isSuccess()) return@safely firebaseFailure(response)
        IdentityResult.Success(Unit)
    }

    override suspend fun resetPassword(email: String): IdentityResult<Unit> = safely {
        val key = firebaseKey()
        if (key.isBlank()) return@safely failure("AUTH_NOT_CONFIGURED")
        val response = client.post("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode") {
            url { parameters.append("key", key) }; contentType(ContentType.Application.Json)
            header("X-Firebase-Locale", "es")
            setBody(ResetRequest("PASSWORD_RESET", email))
        }
        if (!response.status.isSuccess()) {
            if (response.status.value == 400 && response.body<FirebaseErrorEnvelope>().error.message == "EMAIL_NOT_FOUND")
                return@safely IdentityResult.Success(Unit)
            return@safely firebaseFailure(response)
        }
        IdentityResult.Success(Unit)
    }

    private suspend fun firebaseFailure(response: HttpResponse, registration: Boolean = false): IdentityResult.Failure {
        if (response.status.value == 429) return failure("RATE_LIMITED")
        if (response.status.value >= 500) return failure("AUTH_UNAVAILABLE")
        val code = response.body<FirebaseErrorEnvelope>().error.message.substringBefore(" : ")
        return failure(when (code) {
            "OPERATION_NOT_ALLOWED", "CONFIGURATION_NOT_FOUND", "API_KEY_INVALID" -> "AUTH_NOT_CONFIGURED"
            "TOO_MANY_ATTEMPTS_TRY_LATER" -> "RATE_LIMITED"
            "WEAK_PASSWORD", "PASSWORD_DOES_NOT_MEET_REQUIREMENTS" -> "PASSWORD_POLICY"
            "INVALID_ID_TOKEN", "TOKEN_EXPIRED" -> "UNAUTHENTICATED"
            else -> if (registration) "REGISTRATION_REJECTED" else "AUTH_UNAVAILABLE"
        })
    }

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
        return readProfile(response)
    }

    private suspend fun readProfile(response: HttpResponse): IdentityResult<Profile> {
        if (!response.status.isSuccess()) {
            val code = response.body<ApiErrorEnvelope>().errors.firstOrNull()?.code
            return when (response.status.value) {
                401 -> failure("UNAUTHENTICATED")
                403 -> failure(when (code) {
                    "USER_INACTIVE", "EMAIL_NOT_VERIFIED", "USER_NOT_REGISTERED" -> code
                    else -> "ACCESS_DENIED"
                })
                409 -> failure("REGISTRATION_CONFLICT")
                422 -> failure("INVALID_PROFILE_NAME")
                429 -> failure("RATE_LIMITED")
                503 -> failure(if (code == "AUTHENTICATION_UNAVAILABLE") "AUTH_NOT_CONFIGURED" else "AUTH_UNAVAILABLE")
                else -> failure("AUTH_UNAVAILABLE")
            }
        }
        val profile = response.body<ProfileEnvelope>().data
        if (profile.id.isBlank() || profile.email.isBlank() || profile.displayName.isBlank()) return failure("INVALID_RESPONSE")
        return IdentityResult.Success(Profile(profile.id, profile.email, profile.displayName,
            profile.organizations.map { Organization(it.id, it.tradeName?.takeIf(String::isNotBlank) ?: it.legalName, it.role) }, profile.platformRole))
    }

    private suspend fun <T> safely(block: suspend () -> IdentityResult<T>): IdentityResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SessionAccessException) {
        failure(if (error.expired) "UNAUTHENTICATED" else "AUTH_UNAVAILABLE")
    } catch (_: HttpRequestTimeoutException) {
        failure("TIMEOUT")
    } catch (_: SerializationException) {
        failure("INVALID_RESPONSE")
    } catch (_: JsonConvertException) {
        failure("INVALID_RESPONSE")
    } catch (_: Throwable) {
        // Wasm fetch failures may extend Error rather than Exception. Cancellation was handled above.
        failure("NETWORK_ERROR")
    }
}

private fun failure(code: String): IdentityResult.Failure {
    val message = when (code) {
        "INVALID_CREDENTIALS" -> "El correo o la contraseña no son correctos."
        "AUTH_NOT_CONFIGURED" -> "El inicio de sesión aún no está configurado. Contacta al administrador."
        "AUTH_DOMAIN_NOT_AUTHORIZED" -> "Este dominio no está autorizado en Firebase. Contacta al administrador."
        "EMAIL_NOT_VERIFIED" -> "Verifica tu correo y vuelve a iniciar sesión para completar tu cuenta BPM."
        "REGISTRATION_REJECTED" -> "No pudimos crear la cuenta. Intenta iniciar sesión o recuperar tu contraseña."
        "REGISTRATION_INCOMPLETE" -> "Tu cuenta se creó, pero no pudimos completar la verificación. Reenvía el enlace o inicia sesión para continuar; no necesitas registrarte otra vez."
        "REGISTRATION_CONFLICT" -> "Usa el método original de tu cuenta o contacta al administrador."
        "PASSWORD_POLICY" -> "La contraseña no cumple la política de seguridad de Firebase. Usa una más segura."
        "INVALID_PROFILE_NAME" -> "Revisa el nombre de tu perfil. Debe tener entre 2 y 100 caracteres."
        "USER_NOT_REGISTERED" -> "Tu cuenta no está habilitada en BPM. Contacta al administrador."
        "USER_INACTIVE" -> "Tu cuenta se encuentra inactiva. Contacta al administrador."
        "ACCESS_DENIED" -> "No tienes permiso para acceder. Contacta al administrador."
        "UNAUTHENTICATED" -> "Tu sesión terminó. Inicia sesión nuevamente."
        "RATE_LIMITED" -> "Demasiados intentos. Espera un momento antes de volver a intentar."
        "TIMEOUT" -> "La conexión tardó demasiado. Inténtalo nuevamente."
        "NETWORK_ERROR" -> "No pudimos conectar. Revisa tu conexión a internet."
        "INVALID_RESPONSE" -> "No pudimos verificar tu sesión. Inténtalo nuevamente."
        "GOOGLE_CANCELLED" -> "Se canceló el acceso con Google."
        "GOOGLE_IN_PROGRESS" -> "Ya hay un acceso con Google en curso. Completa o cierra la ventana antes de volver a intentar."
        "GOOGLE_NETWORK_ERROR" -> "Google no pudo completar la conexión. Revisa tu red y vuelve a intentar."
        "GOOGLE_UNAVAILABLE" -> "Google no pudo completar el acceso. Vuelve a intentarlo; si continúa, contacta al administrador."
        "GOOGLE_POPUP_BLOCKED" -> "Permite la ventana emergente para iniciar sesión con Google."
        "GOOGLE_NOT_READY" -> "Google se está preparando. Vuelve a intentarlo en un momento."
        "GOOGLE_NOT_SUPPORTED" -> "El acceso con Google todavía no está habilitado en esta plataforma."
        "ACCOUNT_CONFLICT" -> "Ingresa con el método original de tu cuenta. Su vinculación con Google requiere verificación."
        else -> "El servicio de autenticación no está disponible. Inténtalo nuevamente."
    }
    val fields = when (code) {
        "PASSWORD_POLICY" -> mapOf("password" to message)
        "INVALID_PROFILE_NAME" -> mapOf("displayName" to message)
        else -> emptyMap()
    }
    return IdentityResult.Failure(listOf(IdentityError(code, message, fields)))
}

@Serializable private class SignInRequest(val email: String, val password: String, val returnSecureToken: Boolean)
@Serializable private class FirebaseCredential(val idToken: String, val localId: String, val displayName: String = "Usuario BPM", val refreshToken: String = "", val expiresIn: String = "3600")
@Serializable private class UpdateNameRequest(val idToken: String, val displayName: String, val returnSecureToken: Boolean)
@Serializable private class RegisterProfileRequest(val displayName: String)
@Serializable private class VerificationRequest(val requestType: String, val idToken: String)
@Serializable private class ResetRequest(val requestType: String, val email: String)
@Serializable private data class FirebaseErrorEnvelope(val error: FirebaseError)
@Serializable private data class FirebaseError(val message: String)
@Serializable private data class PublicConfigDto(val firebaseApiKey: String = "")
@Serializable private data class ApiErrorEnvelope(val errors: List<ApiErrorDto> = emptyList())
@Serializable private data class ApiErrorDto(val code: String)
@Serializable private data class ProfileEnvelope(val data: ProfileDto)
@Serializable private data class ProfileDto(val id: String, val email: String, val displayName: String, val organizations: List<OrganizationDto>, val platformRole: String = "USER")
@Serializable private data class OrganizationDto(val id: String, val legalName: String, val tradeName: String? = null, val role: String)

private val sessionDenials = setOf("UNAUTHENTICATED", "USER_INACTIVE", "USER_NOT_REGISTERED", "ACCESS_DENIED", "EMAIL_NOT_VERIFIED")
@Serializable private class StoredSession(val origin: String, val uid: String, val refreshToken: String)
@Serializable private class RefreshCredential(
    @kotlinx.serialization.SerialName("id_token") val idToken: String,
    @kotlinx.serialization.SerialName("refresh_token") val refreshToken: String,
    @kotlinx.serialization.SerialName("user_id") val uid: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: String,
)
