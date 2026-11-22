package pe.bpm.features.normative.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.normative.domain.*

/** Editor de un documento de normativa; [id] nulo = documento nuevo. */
data class DocumentEditor(val id: String?, val input: NormativeDocumentInput, val fields: Map<String, String> = emptyMap(), val loading: Boolean = false)

data class NormativeState(
    val userId: String = "",
    val status: NormativeStatus? = null,
    val loading: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    /** Búsqueda cuyos resultados se muestran (null = aún no se busca). */
    val searchedQuery: String? = null,
    val results: List<NormativeMatch> = emptyList(),
    val failure: NormativeFailure? = null,
    val fields: Map<String, String> = emptyMap(),
    // Administración (solo SUPER_ADMIN; el servidor lo vuelve a validar).
    val documents: List<NormativeDocument> = emptyList(),
    val archived: Boolean = false,
    val documentsLoading: Boolean = false,
    val editor: DocumentEditor? = null,
    val saving: Boolean = false,
    val archiving: NormativeDocument? = null,
    val adminFailure: NormativeFailure? = null,
    val notice: String? = null,
)

/** Buscador de normativa y gestión de documentos. La UI observa [state] y emite acciones. */
class NormativeViewModel(private val useCases: NormativeUseCases) : ViewModel() {
    private val _state = MutableStateFlow(NormativeState())
    val state: StateFlow<NormativeState> = _state.asStateFlow()
    private var searching: Job? = null

    /** Cambiar de persona descarta las consultas de la sesión anterior. */
    fun open(userId: String) {
        if (_state.value.userId != userId) _state.value = NormativeState(userId = userId)
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            when (val result = useCases.status()) {
                is NormativeResult.Success -> _state.update { it.copy(loading = false, status = result.value) }
                is NormativeResult.Failure -> _state.update { it.copy(loading = false, failure = result.reason) }
            }
        }
    }

    /** Busca mientras se escribe, tras una pausa breve; una búsqueda nueva cancela la anterior. */
    fun editQuery(text: String) {
        _state.update { it.copy(query = text.take(NormativeRules.QUERY_MAX), fields = it.fields - "q") }
        searching?.cancel()
        if (NormativeRules.queryIssue(text) != null) { _state.update { it.copy(searching = false, searchedQuery = null, results = emptyList()) }; return }
        searching = viewModelScope.launch { delay(350); runSearch(text) }
    }

    /** Buscar de inmediato (Enter o botón). */
    fun search() {
        searching?.cancel()
        val query = _state.value.query
        searching = viewModelScope.launch { runSearch(query) }
    }

    private suspend fun runSearch(query: String) {
        _state.update { it.copy(searching = true, failure = null) }
        when (val result = useCases.search(query)) {
            is NormativeResult.Success -> _state.update { it.copy(searching = false, searchedQuery = query.trim(), results = result.value) }
            is NormativeResult.Failure -> _state.update { it.copy(searching = false, failure = result.reason.takeIf { r -> r != NormativeFailure.INVALID || result.fields.isEmpty() }, fields = result.fields) }
        }
    }

    // ---- Administración ------------------------------------------------------------------------

    fun loadDocuments(archived: Boolean = _state.value.archived) {
        _state.update { it.copy(archived = archived, documentsLoading = true, adminFailure = null) }
        viewModelScope.launch {
            when (val result = useCases.documents(archived)) {
                is NormativeResult.Success -> _state.update { if (it.archived == archived) it.copy(documentsLoading = false, documents = result.value) else it }
                is NormativeResult.Failure -> _state.update { it.copy(documentsLoading = false, adminFailure = result.reason) }
            }
        }
    }

    fun newDocument() = _state.update { it.copy(editor = DocumentEditor(null, NormativeDocumentInput("", "", "")), notice = null) }

    fun editDocument(document: NormativeDocument) {
        _state.update { it.copy(editor = DocumentEditor(document.id, NormativeDocumentInput(document.title, document.source, ""), loading = true), notice = null) }
        viewModelScope.launch {
            when (val result = useCases.document(document.id)) {
                is NormativeResult.Success -> _state.update { s ->
                    if (s.editor?.id != document.id) s else s.copy(editor = s.editor.copy(input = NormativeDocumentInput(result.value.title, result.value.source, result.value.content), loading = false))
                }
                is NormativeResult.Failure -> _state.update { it.copy(editor = null, adminFailure = result.reason) }
            }
        }
    }

    fun editInput(change: (NormativeDocumentInput) -> NormativeDocumentInput) = _state.update { s ->
        s.editor?.let { s.copy(editor = it.copy(input = change(it.input), fields = emptyMap())) } ?: s
    }

    fun cancelEditor() = _state.update { if (it.saving) it else it.copy(editor = null) }

    fun saveEditor() {
        val editor = _state.value.editor ?: return
        if (_state.value.saving || editor.loading) return
        _state.update { it.copy(saving = true, adminFailure = null) }
        viewModelScope.launch {
            when (val result = useCases.save(editor.id, editor.input)) {
                is NormativeResult.Success -> {
                    _state.update { it.copy(saving = false, editor = null, notice = if (editor.id == null) "Normativa cargada. Ya aparece en las búsquedas." else "Cambios guardados.") }
                    loadDocuments(); refresh()
                }
                is NormativeResult.Failure -> _state.update { s ->
                    s.copy(saving = false, editor = s.editor?.copy(fields = result.fields), adminFailure = result.reason.takeIf { result.fields.isEmpty() })
                }
            }
        }
    }

    fun askArchive(document: NormativeDocument) = _state.update { it.copy(archiving = document, notice = null) }
    fun cancelArchive() = _state.update { if (it.saving) it else it.copy(archiving = null) }

    /** Archiva un documento activo o reactiva uno archivado. */
    fun confirmArchive() {
        val document = _state.value.archiving ?: return
        val archive = document.status == "ACTIVE"
        _state.update { it.copy(saving = true, adminFailure = null) }
        viewModelScope.launch {
            when (val result = useCases.setArchived(document.id, archive)) {
                is NormativeResult.Success -> {
                    _state.update { it.copy(saving = false, archiving = null, notice = if (archive) "Documento archivado: ya no aparece en las búsquedas." else "Documento reactivado.") }
                    loadDocuments(); refresh()
                }
                is NormativeResult.Failure -> _state.update { it.copy(saving = false, adminFailure = result.reason) }
            }
        }
    }
}
