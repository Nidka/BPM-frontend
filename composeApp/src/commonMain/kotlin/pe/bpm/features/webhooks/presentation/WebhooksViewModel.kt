package pe.bpm.features.webhooks.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.webhooks.domain.*

data class WebhookEditor(val id: String?, val input: WebhookInput, val fields: Map<String, String> = emptyMap())

/** [revealed] es el secreto recién creado o rotado: vive solo en memoria y se descarta al cerrarlo. */
data class WebhooksState(
    val organizationId: String = "",
    val list: WebhookList? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val editor: WebhookEditor? = null,
    val deleting: Webhook? = null,
    val revealed: Webhook? = null,
    val failure: WebhookFailure? = null,
    val notice: String? = null,
)

class WebhooksViewModel(private val useCases: WebhookUseCases) : ViewModel() {
    private val _state = MutableStateFlow(WebhooksState())
    val state: StateFlow<WebhooksState> = _state.asStateFlow()

    fun open(org: String) {
        if (_state.value.organizationId != org) _state.value = WebhooksState(organizationId = org)
        refresh()
    }

    fun refresh() {
        val org = _state.value.organizationId.ifEmpty { return }
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            when (val result = useCases.list(org)) {
                is WebhookResult.Success -> _state.update { if (it.organizationId == org) it.copy(loading = false, list = result.value) else it }
                is WebhookResult.Failure -> _state.update { it.copy(loading = false, failure = result.reason) }
            }
        }
    }

    fun create() = _state.update { it.copy(editor = WebhookEditor(null, WebhookInput("", it.list?.eventTypes?.toSet().orEmpty())), notice = null, revealed = null) }
    fun edit(webhook: Webhook) = _state.update { it.copy(editor = WebhookEditor(webhook.id, WebhookInput(webhook.url, webhook.eventTypes.toSet(), webhook.active)), notice = null) }
    fun editUrl(url: String) = _state.update { s -> s.editor?.let { s.copy(editor = it.copy(input = it.input.copy(url = url), fields = it.fields - "url")) } ?: s }
    fun toggleEvent(type: String) = _state.update { s ->
        s.editor?.let { e -> s.copy(editor = e.copy(input = e.input.copy(eventTypes = if (type in e.input.eventTypes) e.input.eventTypes - type else e.input.eventTypes + type), fields = e.fields - "eventTypes")) } ?: s
    }
    fun setActive(active: Boolean) = _state.update { s -> s.editor?.let { s.copy(editor = it.copy(input = it.input.copy(active = active))) } ?: s }
    fun cancelEditor() = _state.update { if (it.busy) it else it.copy(editor = null) }

    fun save() {
        val editor = _state.value.editor ?: return
        val org = _state.value.organizationId
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            when (val result = useCases.save(org, editor.id, editor.input)) {
                is WebhookResult.Success -> {
                    _state.update { it.copy(busy = false, editor = null, revealed = result.value.takeIf { w -> w.secret != null },
                        notice = if (editor.id == null) null else "Cambios guardados.") }
                    refresh()
                }
                is WebhookResult.Failure -> _state.update { s -> s.copy(busy = false, editor = s.editor?.copy(fields = result.fields), failure = result.reason.takeIf { result.fields.isEmpty() }) }
            }
        }
    }

    fun rotate(webhook: Webhook) = run(webhook) { org ->
        when (val result = useCases.rotate(org, webhook.id)) {
            is WebhookResult.Success -> _state.update { it.copy(busy = false, revealed = result.value) }
            is WebhookResult.Failure -> _state.update { it.copy(busy = false, failure = result.reason) }
        }
    }

    fun test(webhook: Webhook) = run(webhook) { org ->
        when (val result = useCases.test(org, webhook.id)) {
            is WebhookResult.Success -> {
                _state.update { it.copy(busy = false, notice = if (result.value.delivered) "Prueba entregada (respuesta ${result.value.status})." else
                    "La prueba no se entregó${if (result.value.status > 0) " (respuesta ${result.value.status})" else ""}. Revisa la dirección y que n8n esté activo.") }
                refresh()
            }
            is WebhookResult.Failure -> _state.update { it.copy(busy = false, failure = result.reason) }
        }
    }

    fun askDelete(webhook: Webhook) = _state.update { it.copy(deleting = webhook, notice = null) }
    fun cancelDelete() = _state.update { if (it.busy) it else it.copy(deleting = null) }
    fun confirmDelete() {
        val webhook = _state.value.deleting ?: return
        run(webhook) { org ->
            when (val result = useCases.delete(org, webhook.id)) {
                is WebhookResult.Success -> { _state.update { it.copy(busy = false, deleting = null, notice = "Webhook eliminado.") }; refresh() }
                is WebhookResult.Failure -> _state.update { it.copy(busy = false, failure = result.reason) }
            }
        }
    }

    fun closeSecret() = _state.update { it.copy(revealed = null) }

    private fun run(@Suppress("UNUSED_PARAMETER") webhook: Webhook, block: suspend (String) -> Unit) {
        if (_state.value.busy) return
        val org = _state.value.organizationId
        _state.update { it.copy(busy = true, failure = null, notice = null) }
        viewModelScope.launch { block(org) }
    }
}
