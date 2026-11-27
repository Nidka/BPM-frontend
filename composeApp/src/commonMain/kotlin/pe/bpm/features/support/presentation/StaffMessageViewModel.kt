package pe.bpm.features.support.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.support.domain.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class StaffMessageState(
    val org: String = "",
    val locationId: String = "",
    val kind: StaffMessageKind? = null,
    val text: String = "",
    val sending: Boolean = false,
    val sent: Boolean = false,
    val error: String? = null,
    val contact: SupervisorContact? = null,
)

/** «Avisar a mi jefe»: elige el motivo, escribe (opcional) y envía; también resuelve el WhatsApp del jefe de la sede. */
class StaffMessageViewModel(private val repository: TeamRepository, private val newId: () -> String = { randomId() }) : ViewModel() {
    private val _state = MutableStateFlow(StaffMessageState())
    val state: StateFlow<StaffMessageState> = _state.asStateFlow()
    private var pendingId: String? = null

    fun open(org: String, locationId: String) {
        if (_state.value.org == org && _state.value.locationId == locationId) return
        _state.value = StaffMessageState(org = org, locationId = locationId)
        pendingId = null
        viewModelScope.launch {
            val r = repository.supervisorContact(org, locationId)
            if (r is SupportResult.Success && _state.value.locationId == locationId) _state.update { it.copy(contact = r.value) }
        }
    }

    fun pick(kind: StaffMessageKind) = _state.update { it.copy(kind = kind, error = null, sent = false) }
    fun edit(text: String) = _state.update { it.copy(text = text.take(StaffMessageRules.TEXT_MAX), error = null, sent = false) }

    fun send() {
        val s = _state.value
        val kind = s.kind ?: run { _state.update { it.copy(error = "Elige qué pasa.") }; return }
        if (s.sending || s.org.isEmpty() || s.locationId.isEmpty()) return
        if (kind == StaffMessageKind.OTHER && s.text.isBlank()) { _state.update { it.copy(error = "Escribe qué pasa.") }; return }
        // El mismo id y la misma clave al reintentar: el servidor no duplica el aviso.
        val id = pendingId ?: newId().also { pendingId = it }
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.sendStaffMessage(s.org, id, s.locationId, kind, s.text, "staff-$id")) {
                is SupportResult.Success -> { pendingId = null; _state.update { it.copy(sending = false, sent = true, kind = null, text = "") } }
                is SupportResult.Failure -> _state.update { it.copy(sending = false, error = when (r.reason) {
                    SupportFailure.CONNECTION -> "Sin conexión. Inténtalo cuando tengas internet."
                    SupportFailure.SESSION -> "Vuelve a entrar a tu cuenta e inténtalo otra vez."
                    SupportFailure.DENIED -> "No puedes avisar desde esta sede."
                    else -> "No pudimos enviar el aviso. Inténtalo otra vez."
                }) }
            }
        }
    }

    fun reset() { _state.value = StaffMessageState(); pendingId = null }
}

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()
