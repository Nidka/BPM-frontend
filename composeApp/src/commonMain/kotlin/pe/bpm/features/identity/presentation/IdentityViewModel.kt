package pe.bpm.features.identity.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.identity.domain.*

enum class AuthMode { Login, Register, Recovery, Verification }

data class IdentityUiState(
    val busy: Boolean = false,
    val profile: Profile? = null,
    val errors: List<IdentityError> = emptyList(),
    val mode: AuthMode = AuthMode.Login,
    val notice: String? = null,
    val canResendVerification: Boolean = false,
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(IdentityUiState())
    val state = mutableState.asStateFlow()
    private var requestJob: Job? = null

    fun login(email: String, password: String) {
        if (mutableState.value.busy) return
        mutableState.value = IdentityUiState(busy = true)
        requestJob = viewModelScope.launch {
            applyResult(signIn(email, password))
        }
    }

    fun verifySession() {
        if (mutableState.value.busy || mutableState.value.profile == null) return
        mutableState.value = mutableState.value.copy(busy = true, errors = emptyList())
        requestJob = viewModelScope.launch {
            applyResult(getCurrentProfile(), keepProfileOnTransientFailure = true)
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
        signOut()
        mutableState.value = IdentityUiState()
    }

    fun changeMode(mode: AuthMode) {
        if (mutableState.value.busy || mutableState.value.profile != null) return
        mutableState.value = IdentityUiState(mode = mode)
    }

    fun register(name: String, email: String, password: String, confirmation: String) = runAction(
        AuthMode.Verification, "Te enviamos un enlace. Verifica tu correo y luego inicia sesión para activar tu perfil BPM."
    ) { registerAccount(name, email, password, confirmation) }

    fun recover(email: String) = runAction(AuthMode.Recovery,
        "Si existe una cuenta con ese correo, recibirás instrucciones para recuperar el acceso.") { resetPassword(email) }

    fun resend() = runAction(AuthMode.Verification,
        "Enviamos nuevamente el enlace de verificación. Revisa también la carpeta de spam.") { resendVerification() }

    private fun runAction(successMode: AuthMode, notice: String, action: suspend () -> IdentityResult<Unit>) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, errors = emptyList(), notice = null)
        requestJob = viewModelScope.launch {
            mutableState.value = when (val result = action()) {
                is IdentityResult.Success -> IdentityUiState(mode = successMode, notice = notice,
                    canResendVerification = successMode == AuthMode.Verification)
                is IdentityResult.Failure -> mutableState.value.copy(busy = false, errors = result.errors)
            }
        }
    }

    private fun applyResult(result: IdentityResult<Profile>, keepProfileOnTransientFailure: Boolean = false) {
        mutableState.value = when (result) {
            is IdentityResult.Success -> IdentityUiState(profile = result.value)
            is IdentityResult.Failure -> {
                val sessionDenied = result.errors.any {
                    it.code in setOf("UNAUTHENTICATED", "USER_INACTIVE", "USER_NOT_REGISTERED")
                }
                IdentityUiState(profile = mutableState.value.profile.takeIf { keepProfileOnTransientFailure && !sessionDenied },
                    errors = result.errors, canResendVerification = result.errors.any { it.code == "EMAIL_NOT_VERIFIED" })
            }
        }
    }
}
