package pe.bpm.features.support.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.core.designsystem.*
import pe.bpm.features.support.domain.*

data class MyWhatsappState(val userId: String = "", val value: String = "", val error: String? = null, val saving: Boolean = false, val notice: String? = null)

/** Mi WhatsApp (opcional): lo usa el equipo para escribirme desde «Avisar a mi jefe». */
class MyWhatsappViewModel(private val repository: TeamRepository) : ViewModel() {
    private val _state = MutableStateFlow(MyWhatsappState())
    val state = _state.asStateFlow()

    fun open(userId: String, current: String?) {
        if (_state.value.userId != userId) _state.value = MyWhatsappState(userId, current.orEmpty())
    }

    fun edit(value: String) = _state.update { it.copy(value = value.take(20), error = null, notice = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        StaffMessageRules.myWhatsappError(s.value)?.let { e -> _state.update { it.copy(error = e) }; return }
        _state.update { it.copy(saving = true, notice = null) }
        viewModelScope.launch {
            when (val r = repository.saveMyWhatsapp(s.value)) {
                is SupportResult.Success -> _state.update { it.copy(saving = false, value = r.value.orEmpty(), notice = if (r.value == null) "Quitamos tu WhatsApp." else "WhatsApp guardado.") }
                is SupportResult.Failure -> _state.update { it.copy(saving = false, error = if (r.reason == SupportFailure.INVALID) StaffMessageRules.myWhatsappError("x") else null,
                    notice = if (r.reason == SupportFailure.CONNECTION) "Sin conexión. Inténtalo cuando tengas internet." else "No pudimos guardar. Inténtalo otra vez.") }
            }
        }
    }
}

/** Ajustes › «Mi WhatsApp». */
@Composable
fun MyWhatsappSection(viewModel: MyWhatsappViewModel) {
    val s by viewModel.state.collectAsState()
    BpmSettingsSection("Mi WhatsApp", description = "Opcional. Si eres jefe o administrador, tu equipo podrá escribirte desde «Avisar a mi jefe».") {
        BpmTextField(s.value, viewModel::edit, "Celular", Modifier.fillMaxWidth(), error = s.error, enabled = !s.saving, inputKind = BpmInputKind.Phone,
            placeholder = "987654321", supportingText = "Déjalo vacío si no quieres compartirlo.")
        BpmChunkyButton(if (s.saving) "Guardando…" else "Guardar WhatsApp", viewModel::save, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !s.saving)
        s.notice?.let { BpmInlineNotice(it) }
    }
}
