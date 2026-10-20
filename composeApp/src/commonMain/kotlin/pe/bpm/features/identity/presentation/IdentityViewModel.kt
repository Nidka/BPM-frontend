package pe.bpm.features.identity.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.identity.domain.*

enum class AuthMode { Login, Register, Recovery, Verification }

data class IdentityUiState(
    val busy: Boolean = false,
    val restoring: Boolean = false,
    val canRetryRestore: Boolean = false,
    val profile: Profile? = null,
    val errors: List<IdentityError> = emptyList(),
    val mode: AuthMode = AuthMode.Login,
    val notice: String? = null,
    val canResendVerification: Boolean = false,
    val verificationMessage: String? = null,
    val verificationEmail: String? = null,
    val verificationEmailSent: Boolean = false,
    val resendCooldownSeconds: Int = 0,
    val confirmingEmail: Boolean = false,
    val verificationDeliveryFailed: Boolean = false,
)

class IdentityViewModel(
    private val signIn: SignIn,
    private val getCurrentProfile: GetCurrentProfile,
    private val signOut: SignOut,
    private val signInWithGoogle: SignInWithGoogle,
    val googleSupported: Boolean,
    private val registerAccount: Register,
    private val resetPassword: ResetPassword,
    private val resendVerification: ResendVerification,
    private val confirmEmailVerification: ConfirmEmailVerification,
    private val restoreSession: RestoreSession? = null,
    private val observeInvalidations: ObserveSessionInvalidations? = null,
    private val sessionMonitor: SessionMonitor? = null,
    private val endSession: EndSession? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(IdentityUiState())
    val state = mutableState.asStateFlow()
    private var requestJob: Job? = null
    private var resendCooldownJob: Job? = null

    init {
        observeInvalidations?.let { observe -> viewModelScope.launch {
            observe().collect { error ->
                requestJob?.cancel()
                sessionMonitor?.reset()
                mutableState.value = IdentityUiState(errors = listOf(error))
            }
        } }
        restore()
        sessionMonitor?.bind(viewModelScope, hasSession = { mutableState.value.profile != null }, onExpired = { reason ->
            requestJob?.cancel()
            signOut()
            mutableState.value = IdentityUiState(errors = listOf(IdentityError("SESSION_EXPIRED", when (reason) {
                SessionExpiryReason.INACTIVITY -> "Tu sesión se cerró por inactividad. Inicia sesión para continuar."
                SessionExpiryReason.ABSOLUTE -> "Tu sesión alcanzó su duración máxima. Inicia sesión de nuevo para continuar."
            })))
        })
        // Renueva el token y comprueba el perfil del backend mientras existe una sesión.
        if (observeInvalidations != null) viewModelScope.launch {
            while (true) {
                delay(60_000)
                if (mutableState.value.profile != null) checkSession(userInitiated = false)
            }
        }
    }

    fun restore() {
        val restore = restoreSession ?: return
        if (mutableState.value.busy || mutableState.value.profile != null) return
        mutableState.value = IdentityUiState(busy = true, restoring = true)
        requestJob = viewModelScope.launch {
            val result = restore()
            currentCoroutineContext().ensureActive()
            if (result is IdentityResult.Failure && result.errors.all { it.code == "UNAUTHENTICATED" }) {
                mutableState.value = IdentityUiState()
            } else {
                applyResult(result)
                if (result is IdentityResult.Failure) mutableState.value = mutableState.value.copy(canRetryRestore = result.errors.all { it.permitsRestoreRetry })
            }
        }
    }

    fun login(email: String, password: String) {
        if (mutableState.value.busy) return
        mutableState.value = IdentityUiState(busy = true)
        requestJob = viewModelScope.launch {
            applyResult(signIn(email, password), verificationEmail = email.trim())
        }
    }

    fun verifySession() = checkSession(userInitiated = true)

    private fun checkSession(userInitiated: Boolean) {
        if (mutableState.value.busy || mutableState.value.profile == null) return
        val previousProfile = mutableState.value.profile
        val previousMessage = mutableState.value.verificationMessage
        mutableState.value = mutableState.value.copy(busy = true, errors = emptyList(),
            verificationMessage = if (userInitiated) null else previousMessage)
        requestJob = viewModelScope.launch {
            val result = getCurrentProfile()
            applyResult(result, keepProfileOnTransientFailure = true)
            if (result is IdentityResult.Success) {
                val message = when {
                    userInitiated && result.value.organizations.isEmpty() && result.value.platformRole != "SUPER_ADMIN" ->
                        "Sesión verificada. Tu cuenta aún no tiene una organización asignada."
                    userInitiated -> "Sesión verificada. Tus permisos están actualizados."
                    result.value == previousProfile -> previousMessage
                    else -> null
                }
                mutableState.value = mutableState.value.copy(verificationMessage = message)
            }
        }
    }

    fun loginWithGoogle() {
        if (mutableState.value.busy) return
        mutableState.value = IdentityUiState(busy = true)
        // UNDISPATCHED conserva el gesto del usuario al abrir el popup en web.
        requestJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            applyResult(signInWithGoogle())
        }
    }

    fun logout() {
        requestJob?.cancel()
        sessionMonitor?.reset()
        resendCooldownJob?.cancel()
        mutableState.value = IdentityUiState()
        val end = endSession
        if (end == null) signOut()
        else requestJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val result = end()
            currentCoroutineContext().ensureActive()
            if (result is IdentityResult.Failure) mutableState.value = IdentityUiState(notice =
                "Saliste en este dispositivo. No pudimos confirmar el cierre en el servidor; evita compartirlo hasta recuperar la conexión.")
        }
    }

    fun userActivity() { sessionMonitor?.userActivity() }
    fun onForeground() { sessionMonitor?.recheck() }
    fun continueSession() { sessionMonitor?.continueSession() }
    val sessionStatus = sessionMonitor?.state ?: MutableStateFlow(SessionStatus()).asStateFlow()

    fun changeMode(mode: AuthMode) {
        if (mutableState.value.busy || mutableState.value.profile != null) return
        resendCooldownJob?.cancel()
        // Salir de verificación descarta sus credenciales temporales, sin repetir el alta.
        if (mutableState.value.mode == AuthMode.Verification) signOut()
        mutableState.value = IdentityUiState(mode = mode)
    }

    fun register(firstName: String, lastName: String, email: String, password: String, confirmation: String) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(verificationEmail = email.trim())
        runAction(AuthMode.Verification, "Enlace enviado. Revisa tu bandeja de entrada y la carpeta de spam.") {
            registerAccount(firstName, lastName, email, password, confirmation)
        }
    }

    fun recover(email: String) = runAction(AuthMode.Recovery,
        "Si existe una cuenta con ese correo, recibirás instrucciones para recuperar el acceso.") { resetPassword(email) }

    fun resend() {
        if (!mutableState.value.canResendVerification || mutableState.value.resendCooldownSeconds > 0) return
        runAction(AuthMode.Verification, "Enlace enviado. Revisa tu bandeja de entrada y la carpeta de spam.") { resendVerification() }
    }

    fun confirmEmail() {
        if (mutableState.value.busy || mutableState.value.mode != AuthMode.Verification) return
        mutableState.value = mutableState.value.copy(busy = true, errors = emptyList(), notice = null, confirmingEmail = true)
        requestJob = viewModelScope.launch {
            val result = confirmEmailVerification()
            currentCoroutineContext().ensureActive()
            when (result) {
                is IdentityResult.Success -> { resendCooldownJob?.cancel(); applyResult(result) }
                is IdentityResult.Failure -> {
                    if (result.errors.any { it.requiresNewAuthentication }) {
                        resendCooldownJob?.cancel()
                        signOut()
                        mutableState.value = IdentityUiState(notice = "Para continuar, inicia sesión de nuevo con tu correo y contraseña.")
                    } else mutableState.value = mutableState.value.copy(busy = false, confirmingEmail = false, errors = result.errors)
                }
            }
        }
    }

    private fun startResendCooldown() {
        resendCooldownJob?.cancel()
        mutableState.value = mutableState.value.copy(resendCooldownSeconds = 60)
        resendCooldownJob = viewModelScope.launch {
            repeat(60) {
                delay(1_000)
                mutableState.value = mutableState.value.copy(resendCooldownSeconds = (mutableState.value.resendCooldownSeconds - 1).coerceAtLeast(0))
            }
        }
    }

    private fun runAction(successMode: AuthMode, notice: String, action: suspend () -> IdentityResult<Unit>) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, errors = emptyList(), notice = null)
        requestJob = viewModelScope.launch {
            val result = action()
            currentCoroutineContext().ensureActive()
            if (mutableState.value.mode == AuthMode.Verification && result is IdentityResult.Failure &&
                result.errors.any { it.code == "UNAUTHENTICATED" }) {
                resendCooldownJob?.cancel()
                signOut()
                mutableState.value = IdentityUiState(notice = "Para continuar, inicia sesión de nuevo con tu correo y contraseña.")
                return@launch
            }
            mutableState.value = when (result) {
                is IdentityResult.Success -> IdentityUiState(mode = successMode, notice = notice,
                    verificationEmail = mutableState.value.verificationEmail,
                    verificationEmailSent = successMode == AuthMode.Verification,
                    canResendVerification = successMode == AuthMode.Verification)
                is IdentityResult.Failure -> {
                    val pending = result.errors.any { it.hasPendingRegistration }
                    mutableState.value.copy(busy = false, errors = result.errors,
                        mode = if (pending) AuthMode.Verification else mutableState.value.mode,
                        verificationDeliveryFailed = pending || mutableState.value.mode == AuthMode.Verification,
                        canResendVerification = pending || mutableState.value.canResendVerification)
                }
            }
            if (mutableState.value.mode == AuthMode.Verification && (result is IdentityResult.Success ||
                result is IdentityResult.Failure && result.errors.any { it.requiresDeliveryWait })) startResendCooldown()
        }
    }

    private suspend fun applyResult(result: IdentityResult<Profile>, keepProfileOnTransientFailure: Boolean = false, verificationEmail: String? = null) {
        currentCoroutineContext().ensureActive()
        mutableState.value = when (result) {
            is IdentityResult.Success -> IdentityUiState(profile = result.value)
            is IdentityResult.Failure -> {
                val sessionDenied = result.errors.any { it.deniesSession }
                val pending = result.errors.any { it.code == "EMAIL_NOT_VERIFIED" }
                IdentityUiState(profile = mutableState.value.profile.takeIf { keepProfileOnTransientFailure && !sessionDenied },
                    errors = result.errors, canResendVerification = pending,
                    mode = if (pending) AuthMode.Verification else AuthMode.Login,
                    verificationEmail = verificationEmail.takeIf { pending })
            }
        }
    }
}
