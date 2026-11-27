package pe.bpm.features.support.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.support.domain.*

data class SupportState(
    /** Contacto vigente; mientras no llega del servidor (o sin conexión) se usa el correo por defecto. */
    val contact: SupportContact = SupportContact(SupportRules.DEFAULT_EMAIL),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    // Edición (solo SUPER_ADMIN en Ajustes).
    val email: String = "",
    val whatsapp: String = "",
    val emailError: String? = null,
    val whatsappError: String? = null,
    val saving: Boolean = false,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
)

/** Contacto de soporte: lectura para Ayuda y edición para el administrador general. */
class SupportViewModel(private val repository: SupportRepository) : ViewModel() {
    private val _state = MutableStateFlow(SupportState())
    val state: StateFlow<SupportState> = _state.asStateFlow()

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = repository.contact()) {
                is SupportResult.Success -> _state.update { it.copy(contact = result.value, loaded = true, loading = false, email = result.value.email, whatsapp = result.value.whatsapp.orEmpty()) }
                is SupportResult.Failure -> _state.update { it.copy(loading = false, email = it.email.ifEmpty { it.contact.email }, whatsapp = it.whatsapp.ifEmpty { it.contact.whatsapp.orEmpty() }) }
            }
        }
    }

    fun editEmail(value: String) = _state.update { it.copy(email = value.take(254), emailError = null, notice = null) }
    fun editWhatsapp(value: String) = _state.update { it.copy(whatsapp = value.take(30), whatsappError = null, notice = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun save() {
        val current = _state.value
        if (current.saving) return
        val emailError = SupportRules.emailError(current.email)
        val whatsappError = SupportRules.whatsappError(current.whatsapp)
        if (emailError != null || whatsappError != null) {
            _state.update { it.copy(emailError = emailError, whatsappError = whatsappError, notice = null) }
            return
        }
        _state.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            when (val result = repository.save(current.email.trim(), SupportRules.whatsappDigits(current.whatsapp).ifEmpty { null })) {
                is SupportResult.Success -> _state.update { it.copy(saving = false, contact = result.value, loaded = true, email = result.value.email,
                    whatsapp = result.value.whatsapp.orEmpty(), notice = "Contacto de soporte guardado.", noticeIsError = false) }
                is SupportResult.Failure -> _state.update { it.copy(saving = false, noticeIsError = true,
                    emailError = if ("supportEmail" in result.fields) SupportRules.emailError("") else null,
                    whatsappError = if ("supportWhatsapp" in result.fields) "Escribe el número con código de país, por ejemplo 51987654321." else null,
                    notice = when (result.reason) {
                        SupportFailure.DENIED -> "Solo la administración general puede cambiar el contacto de soporte."
                        SupportFailure.INVALID -> "Revisa los datos e inténtalo otra vez."
                        SupportFailure.SESSION -> "Tu sesión venció. Vuelve a entrar e inténtalo otra vez."
                        SupportFailure.CONNECTION -> "Sin conexión. Inténtalo cuando tengas internet."
                        else -> "No pudimos guardar. Inténtalo otra vez."
                    }) }
            }
        }
    }
}
