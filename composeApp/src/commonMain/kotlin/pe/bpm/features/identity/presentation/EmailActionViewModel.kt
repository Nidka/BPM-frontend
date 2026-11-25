package pe.bpm.features.identity.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.identity.domain.*

/** Estados de la página `/auth/accion`. Cada uno se corresponde con una pantalla; ninguno guarda contraseñas. */
sealed interface EmailActionState {
    /** Comprobando el enlace o aplicándolo (esqueleto). */
    data class Working(val mode: EmailActionMode) : EmailActionState
    data class EmailVerified(val email: String?) : EmailActionState
    data class EmailRecovered(val email: String?) : EmailActionState
    /** Enlace de recuperación válido: pide la contraseña nueva para [email]. */
    data class NewPasswordForm(val email: String, val submitting: Boolean = false, val error: String? = null) : EmailActionState
    data class PasswordChanged(val email: String) : EmailActionState
    data class Failed(val mode: EmailActionMode, val failure: EmailActionFailure) : EmailActionState
    /** La dirección no trae un enlace compatible (faltan datos o el modo no existe). */
    data object InvalidLink : EmailActionState
}

class EmailActionViewModel(
    private val request: EmailActionRequest?,
    private val verifyEmail: VerifyEmailLink,
    private val checkResetLink: CheckPasswordResetLink,
    private val confirmReset: ConfirmPasswordReset,
    private val recoverEmail: RecoverEmailLink,
    private val onClose: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow<EmailActionState>(
        request?.let { EmailActionState.Working(it.mode) } ?: EmailActionState.InvalidLink)
    val state: StateFlow<EmailActionState> = mutable.asStateFlow()
    private var job: Job? = null
    /** Una vez aplicado, el código de un solo uso no se vuelve a enviar aunque se recomponga la pantalla. */
    private var started = false

    /** Destino de «Continuar» validado: nunca un origen ajeno. */
    val continueUrl: String? get() = request?.continueUrl

    fun start() {
        if (started) return
        started = true
        run()
    }

    /** Repite la operación tras un fallo de conexión; un enlace vencido no se reintenta. */
    fun retry() {
        val current = mutable.value
        if (request == null || current !is EmailActionState.Failed || !current.failure.retryable) return
        // Si el cambio de contraseña falló por red, el enlace sigue vigente y se vuelve a mostrar el formulario tras comprobarlo.
        run()
    }

    private fun run() {
        val action = request ?: return
        mutable.value = EmailActionState.Working(action.mode)
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = when (action.mode) {
                EmailActionMode.VerifyEmail -> when (val result = verifyEmail(action.oobCode)) {
                    is EmailActionResult.Success -> EmailActionState.EmailVerified(result.value)
                    is EmailActionResult.Failure -> EmailActionState.Failed(action.mode, result.reason)
                }
                EmailActionMode.ResetPassword -> when (val result = checkResetLink(action.oobCode)) {
                    is EmailActionResult.Success -> EmailActionState.NewPasswordForm(result.value)
                    is EmailActionResult.Failure -> EmailActionState.Failed(action.mode, result.reason)
                }
                EmailActionMode.RecoverEmail -> when (val result = recoverEmail(action.oobCode)) {
                    is EmailActionResult.Success -> EmailActionState.EmailRecovered(result.value)
                    is EmailActionResult.Failure -> EmailActionState.Failed(action.mode, result.reason)
                }
            }
        }
    }

    fun submitPassword(password: String, confirmation: String) {
        val action = request ?: return
        val form = mutable.value as? EmailActionState.NewPasswordForm ?: return
        if (form.submitting) return
        NewPasswordRules.error(password, confirmation)?.let { mutable.value = form.copy(error = it); return }
        mutable.value = form.copy(submitting = true, error = null)
        job = viewModelScope.launch {
            mutable.value = when (val result = confirmReset(action.oobCode, password, confirmation)) {
                is EmailActionResult.Success -> EmailActionState.PasswordChanged(form.email)
                is EmailActionResult.Failure -> when (result.reason) {
                    // La contraseña se puede corregir; el enlace sigue siendo válido.
                    EmailActionFailure.WeakPassword -> form.copy(error = "Elige una contraseña más segura: combina letras, números y símbolos.")
                    EmailActionFailure.Network, EmailActionFailure.Unavailable, EmailActionFailure.RateLimited ->
                        form.copy(error = when (result.reason) {
                            EmailActionFailure.RateLimited -> "Demasiados intentos. Espera un momento y vuelve a intentarlo."
                            EmailActionFailure.Network -> "No pudimos conectar. Revisa tu internet y vuelve a intentarlo."
                            else -> "El servicio no está disponible por ahora. Inténtalo de nuevo en unos minutos."
                        })
                    else -> EmailActionState.Failed(action.mode, result.reason)
                }
            }
        }
    }

    override fun onCleared() { onClose() }
}
