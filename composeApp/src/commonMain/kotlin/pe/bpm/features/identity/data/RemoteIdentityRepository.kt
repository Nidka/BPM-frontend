package pe.bpm.features.identity.data

import io.ktor.http.isSuccess
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.identity.domain.*

class RemoteIdentityRepository internal constructor(
    private val firebase: FirebaseIdentityGateway,
    private val profiles: BpmIdentityGateway,
    private val apiOrigin: String,
    private val googleAuth: GoogleAuthProvider = platformGoogleAuthProvider(),
    private val sessionStore: SessionStore = EphemeralSessionStore(),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : IdentityRepository {
    /** Entrada de integración para pruebas HTTP y clientes existentes; AppGraph inyecta las fuentes explícitas. */
    constructor(client: HttpClient, config: AppConfig, googleAuth: GoogleAuthProvider = platformGoogleAuthProvider(),
        sessionStore: SessionStore = EphemeralSessionStore(), nowSeconds: () -> Long = { Clock.System.now().epochSeconds }) :
        this(FirebaseIdentityDataSource(client, config), BpmIdentityDataSource(client, config), config.apiBaseUrl, googleAuth, sessionStore, nowSeconds)

    // Solo memoria. No usar almacenamiento del navegador ni guardar la contraseña.
    private var idToken: String? = null
    private var refreshToken: String? = null
    private var sessionUid: String? = null
    private var expiresAt: Long = 0
    private var generation: Long = 0
    private val refreshMutex = Mutex()
    private val invalidations = MutableSharedFlow<IdentityError>(extraBufferCapacity = 1)
    private val window = MutableStateFlow<SessionWindow?>(null)
    private val activityMutex = Mutex()
    private var lastActivitySent: Long? = null

    override fun sessionInvalidations() = invalidations.asSharedFlow()
    override fun sessionWindow() = window.asStateFlow()

    // Las cabeceras describen la política del servidor; no se confía en un JWT decodificado localmente sin verificar.
    fun observeSessionResponse(response: HttpResponse) {
        val token = idToken ?: return
        if (response.call.request.headers["Authorization"] != "Bearer $token") return
        updateSessionWindow(response)
    }

    private fun updateSessionWindow(response: HttpResponse) {
        val absolute = response.headers["X-BPM-Session-Expires-At"]?.toLongOrNull() ?: return
        val idle = response.headers["X-BPM-Session-Idle-Expires-At"]?.toLongOrNull() ?: return
        if (absolute > 0 && idle > 0) {
            val previous = window.value
            // Una respuesta tardía de /me no debe revertir una actividad confirmada más reciente.
            window.value = SessionWindow(absolute,
                if (previous?.absoluteExpiresAt == absolute) maxOf(previous.idleExpiresAt, idle) else idle)
        }
    }

    override suspend fun recordActivity(): IdentityResult<Unit> = identityOperation(IdentityStage.SESSION_ACTIVITY) {
        activityMutex.withLock {
            // Los servidores anteriores no anuncian esta política; no se envían solicitudes de actividad sin ese contrato.
            if (window.value == null) return@withLock IdentityResult.Success(Unit)
            val now = nowSeconds()
            if (lastActivitySent?.let { now >= it && now - it < 60 } == true) return@withLock IdentityResult.Success(Unit)
            val attempt = generation
            val token = validSessionToken() ?: return@withLock identityFailure("UNAUTHENTICATED")
            val response = profiles.activity(token)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return@withLock identityFailure("UNAUTHENTICATED")
            if (response.status.value == 401) { if (token == idToken) invalidateSession(identityFailure("SESSION_EXPIRED").errors.single()); return@withLock identityFailure("SESSION_EXPIRED") }
            if (!response.status.isSuccess()) return@withLock identityFailure("AUTH_UNAVAILABLE", IdentityDiagnostic(IdentityStage.SESSION_ACTIVITY, response.status.value, "AUTH_UNAVAILABLE"))
            observeSessionResponse(response)
            lastActivitySent = now
            IdentityResult.Success(Unit)
        }
    }

    override suspend fun endSession(): IdentityResult<Unit> {
        val token = idToken
        val serverSessions = window.value != null
        // Borrar las credenciales locales antes de suspender; las respuestas tardías no deben restaurarlas.
        signOut()
        if (token == null || !serverSessions) return IdentityResult.Success(Unit)
        return profiles.logout(token)
    }

    private var verificationToken: String? = null
    private var verificationRefreshToken: String? = null
    private var verificationUid: String? = null
    private var verificationName: String = "Usuario BPM"
    private var pendingName: String? = null
    private var pendingUid: String? = null

    // Solo el composition root entrega este acceso a otros adaptadores de red.
    fun sessionToken(): String? = idToken

    /** Renueva el token con una única operación concurrente desde los adaptadores conectados en AppGraph. */
    suspend fun validSessionToken(): String? = refreshMutex.withLock {
        val token = idToken ?: return@withLock null
        if (nowSeconds() < expiresAt - 60) return@withLock token
        val refresh = refreshToken ?: run { invalidateSession(); throw SessionAccessException(true) }
        val attempt = generation
        val credential = try { renew(refresh) } catch (error: IdentityDataException) {
            throw SessionAccessException(error.failure.errors.any { it.deniesSession })
        }
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

    fun invalidateSession(error: IdentityError = identityFailure("UNAUTHENTICATED").errors.single()) {
        signOut()
        invalidations.tryEmit(error)
    }

    override suspend fun restoreSession(): IdentityResult<Profile> = identityOperation {
        val attempt = generation
        val raw = sessionStore.read() ?: return@identityOperation identityFailure("UNAUTHENTICATED")
        val stored = try { Json.decodeFromString<StoredSession>(raw) } catch (_: Exception) {
            sessionStore.clear(); return@identityOperation identityFailure("UNAUTHENTICATED")
        }
        if (stored.origin != apiOrigin || stored.uid.isBlank() || stored.refreshToken.isBlank()) {
            sessionStore.clear(); return@identityOperation identityFailure("UNAUTHENTICATED")
        }
        refreshMutex.withLock {
            val credential = renew(stored.refreshToken)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return@withLock identityFailure("UNAUTHENTICATED")
            if (credential.uid != stored.uid) { signOut(); return@withLock identityFailure("INVALID_RESPONSE") }
            persist(credential.uid, credential.refreshToken)
            val profile = fetchProfile(credential.idToken)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return@withLock identityFailure("UNAUTHENTICATED")
            if (profile is IdentityResult.Success && profile.value.id == stored.uid) {
                idToken = credential.idToken
                refreshToken = credential.refreshToken
                sessionUid = credential.uid
                expiresAt = nowSeconds() + credential.expiresIn.toLong()
                profile
            } else {
                // Conservar las credenciales ante fallos temporales para permitir reintentar la restauración.
                if (profile is IdentityResult.Success || (profile is IdentityResult.Failure && profile.errors.any { it.deniesSession })) signOut()
                if (profile is IdentityResult.Success) identityFailure("INVALID_RESPONSE") else profile
            }
        }
    }

    private fun persist(uid: String, refresh: String) {
        try {
            if (refresh.isBlank()) sessionStore.clear()
            else sessionStore.write(Json.encodeToString(StoredSession(apiOrigin, uid, refresh)))
        } catch (_: Exception) {
            invalidateSession()
            throw SessionAccessException(true)
        }
    }

    private suspend fun renew(refresh: String): RefreshCredential {
        val attempt = generation
        val credential = try { firebase.renew(refresh) } catch (error: IdentityDataException) {
            if (error.failure.errors.any { it.code == "UNAUTHENTICATED" }) invalidateSession()
            throw error
        }
        currentCoroutineContext().ensureActive()
        if (attempt != generation) throw SessionAccessException(true)
        return credential
    }

    /** Liberar el grafo conserva la credencial móvil de renovación; cerrar sesión explícitamente la elimina. */
    fun close() {
        generation++
        idToken = null; refreshToken = null; sessionUid = null
        window.value = null; lastActivitySent = null
        clearPendingVerification()
        googleAuth.clear()
    }

    override suspend fun signIn(email: String, password: String): IdentityResult<Profile> {
        signOut()
        val attempt = generation
        return identityOperation {
            val credential = firebase.signIn(email, password)
            acceptCredential(credential.idToken, credential.localId, credential.displayName, credential.refreshToken, credential.expiresIn, attempt)
        }
    }

    override suspend fun signInWithGoogle(): IdentityResult<Profile> {
        // El puente controla su propia limpieza. No cancelar ni cerrar el SDK
        // justo antes de abrir una nueva ventana OAuth.
        generation++
        idToken = null; refreshToken = null; sessionUid = null
        window.value = null; lastActivitySent = null
        sessionStore.clear()
        clearPendingVerification()
        val attempt = generation
        return identityOperation {
            when (val result = googleAuth.signIn()) {
                is GoogleAuthResult.Failure -> identityFailure(result.code)
                is GoogleAuthResult.Success -> acceptCredential(result.credential.idToken, result.credential.uid, result.credential.displayName, result.credential.refreshToken, result.credential.expiresIn, attempt)
                is GoogleAuthResult.Federated -> {
                    currentCoroutineContext().ensureActive()
                    if (attempt != generation) return@identityOperation identityFailure("UNAUTHENTICATED")
                    val credential = firebase.signInWithGoogle(result.idToken, result.nonce)
                    acceptCredential(credential.idToken, credential.localId, credential.displayName, credential.refreshToken, credential.expiresIn, attempt)
                }
            }
        }
    }

    private suspend fun acceptCredential(token: String, uid: String, displayName: String, refresh: String, expires: String, attempt: Long): IdentityResult<Profile> {
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return identityFailure("UNAUTHENTICATED")
        if (token.isBlank() || uid.isBlank() || (expires.toLongOrNull() ?: 0) !in 61..86400) return identityFailure("INVALID_RESPONSE")
        var profile = fetchProfile(token)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return identityFailure("UNAUTHENTICATED")
        if (profile is IdentityResult.Failure && profile.errors.any { it.code == "USER_NOT_REGISTERED" }) {
            profile = profiles.enroll(token, displayName) { if (attempt == generation) updateSessionWindow(it) }
        }
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return identityFailure("UNAUTHENTICATED")
        if (profile is IdentityResult.Failure && profile.errors.any { it.code == "EMAIL_NOT_VERIFIED" }) {
            verificationToken = token
            verificationRefreshToken = refresh.takeIf { it.isNotBlank() }
            verificationUid = uid
            verificationName = displayName
        }
        if (profile is IdentityResult.Success) {
            if (profile.value.id != uid) return identityFailure("INVALID_RESPONSE")
            persist(uid, refresh)
            idToken = token
            refreshToken = refresh.takeIf { it.isNotBlank() }
            sessionUid = uid
            expiresAt = nowSeconds() + expires.toLong()
            clearPendingVerification()
        }
        return profile
    }

    override suspend fun currentProfile(): IdentityResult<Profile> = identityOperation {
        val attempt = generation
        val token = validSessionToken() ?: return@identityOperation identityFailure("UNAUTHENTICATED")
        val result = fetchProfile(token)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return@identityOperation identityFailure("UNAUTHENTICATED")
        if (result is IdentityResult.Success && result.value.id != sessionUid) { invalidateSession(); return@identityOperation identityFailure("INVALID_RESPONSE") }
        if (result is IdentityResult.Failure) result.errors.firstOrNull { it.deniesSession }?.let(::invalidateSession)
        result
    }

    override fun signOut() {
        close()
        sessionStore.clear()
    }

    override suspend fun register(name: String, email: String, password: String): IdentityResult<Unit> {
        signOut()
        val attempt = generation
        return identityOperation {
            val credential = firebase.signUp(email, password)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return@identityOperation identityFailure("UNAUTHENTICATED")
            verificationToken = credential.idToken
            verificationRefreshToken = credential.refreshToken.takeIf { it.isNotBlank() }
            verificationUid = credential.localId
            verificationName = name
            pendingName = name
            pendingUid = credential.localId
            val completed = resendVerification()
            // Firebase ya creó la cuenta: no ofrecer otro signUp como si hubiera
            // fallado toda la operación ni borrar la identidad del proveedor.
            if (completed is IdentityResult.Failure) IdentityResult.Failure(listOf(IdentityError(
                if (completed.errors.any { it.code == "RATE_LIMITED" }) "REGISTRATION_RATE_LIMITED" else "REGISTRATION_INCOMPLETE",
                when {
                    pendingName != null -> "Tu cuenta ya existe, pero falta terminar de guardar tus datos. Pulsa «Reintentar envío» para continuar."
                    completed.errors.any { it.code == "RATE_LIMITED" } -> "Tu cuenta ya está creada. Espera un minuto antes de solicitar el correo de verificación."
                    else -> "Tu cuenta ya está creada, pero no pudimos enviar el correo de verificación. Pulsa «Reintentar envío»; si el problema continúa, inténtalo más tarde."
                }, diagnostic = completed.errors.firstOrNull()?.diagnostic
            ))) else completed
        }
    }

    override suspend fun resendVerification(): IdentityResult<Unit> = identityOperation {
        val attempt = generation
        val completed = completePendingName()
        if (completed is IdentityResult.Failure) return@identityOperation completed
        val token = verificationToken ?: return@identityOperation identityFailure("UNAUTHENTICATED")
        firebase.sendVerification(token)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return@identityOperation identityFailure("UNAUTHENTICATED")
        IdentityResult.Success(Unit)
    }

    private suspend fun completePendingName(): IdentityResult<Unit> {
        val token = verificationToken ?: return identityFailure("UNAUTHENTICATED")
        val attempt = generation
        pendingName?.let { name ->
            val credential = firebase.updateName(token, name)
            currentCoroutineContext().ensureActive()
            if (attempt != generation) return identityFailure("UNAUTHENTICATED")
            if (credential.localId != pendingUid) return identityFailure("INVALID_RESPONSE")
            // Una actualización de nombre puede conservar el token existente.
            credential.idToken?.takeIf { it.isNotBlank() }?.let { verificationToken = it }
            credential.refreshToken?.takeIf { it.isNotBlank() }?.let { verificationRefreshToken = it }
            pendingName = null
            pendingUid = null
        }
        return IdentityResult.Success(Unit)
    }

    override suspend fun confirmEmailVerification(): IdentityResult<Profile> = identityOperation {
        val attempt = generation
        val uid = verificationUid ?: return@identityOperation identityFailure("UNAUTHENTICATED")
        val refresh = verificationRefreshToken ?: return@identityOperation identityFailure("UNAUTHENTICATED")
        val completed = completePendingName()
        if (completed is IdentityResult.Failure) return@identityOperation completed
        // El token renovado incorpora la verificación; /me conserva la decisión de acceso.
        val credential = renew(verificationRefreshToken ?: refresh)
        currentCoroutineContext().ensureActive()
        if (attempt != generation) return@identityOperation identityFailure("UNAUTHENTICATED")
        if (credential.uid != uid) { clearPendingVerification(); return@identityOperation identityFailure("INVALID_RESPONSE") }
        verificationToken = credential.idToken
        verificationRefreshToken = credential.refreshToken
        acceptCredential(credential.idToken, uid, verificationName, credential.refreshToken, credential.expiresIn, attempt)
    }

    private fun clearPendingVerification() {
        verificationToken = null; verificationRefreshToken = null; verificationUid = null
        verificationName = "Usuario BPM"; pendingName = null; pendingUid = null
    }

    override suspend fun resetPassword(email: String): IdentityResult<Unit> = identityOperation(IdentityStage.PASSWORD_RECOVERY) {
        firebase.resetPassword(email)
        IdentityResult.Success(Unit)
    }

    private suspend fun fetchProfile(token: String): IdentityResult<Profile> {
        val attempt = generation
        return profiles.profile(token) { if (attempt == generation) updateSessionWindow(it) }
    }
}

@Serializable private class StoredSession(val origin: String, val uid: String, val refreshToken: String)
