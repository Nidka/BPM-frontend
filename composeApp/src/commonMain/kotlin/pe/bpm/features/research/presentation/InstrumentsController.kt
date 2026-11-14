package pe.bpm.features.research.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.research.domain.*

/** Copia local editable del borrador. [questions] se reemplaza completa al guardar (el servidor reasigna ids). */
data class DraftEditorState(
    val draft: InstrumentDraft,
    val questions: List<DraftQuestion>,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val publishing: Boolean = false,
    /** Los problemas se muestran tras intentar publicar. */
    val showIssues: Boolean = false,
    val discarding: Boolean = false,
    val failure: ResearchFailure? = null,
) {
    val problems: List<String> get() = InstrumentRules.publishProblems(draft.isCap, questions)
    val busy: Boolean get() = saving || publishing
}

data class ImportForm(val text: String = "", val error: String? = null, val busy: Boolean = false, val failure: ResearchFailure? = null)

data class InstrumentsState(
    val items: List<InstrumentSummary> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val failure: ResearchFailure? = null,
    val import: ImportForm? = null,
    val editor: DraftEditorState? = null,
    val openingId: String? = null,
    val notice: String? = null,
)

/** Instrumentos del estudio: lista, importación del JSON, edición del borrador, nueva versión y publicación. */
class InstrumentsController(
    private val manage: ManageInstruments,
    private val parser: InstrumentImportParser,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(InstrumentsState())
    val state: StateFlow<InstrumentsState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun reset() {
        generation++
        job?.cancel()
        mutable.value = InstrumentsState()
    }

    fun load(force: Boolean = false) {
        val s = mutable.value
        if (s.loading || (s.loaded && !force)) return
        job?.cancel()
        val expected = generation
        mutable.value = s.copy(loading = true, failure = null)
        job = scope.launch {
            val result = manage.list()
            ensureActive(); if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(loading = false, loaded = true, items = result.value)
                is ResearchResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    // ---- Importación -------------------------------------------------------------------------

    fun startImport() { mutable.value = mutable.value.copy(import = ImportForm(), notice = null) }

    fun editImport(text: String) {
        val form = mutable.value.import ?: return
        if (!form.busy) mutable.value = mutable.value.copy(import = form.copy(text = text.take(ResearchLimits.IMPORT_JSON_MAX + 1), error = null, failure = null))
    }

    fun cancelImport() { if (mutable.value.import?.busy != true) mutable.value = mutable.value.copy(import = null) }

    fun confirmImport() {
        val form = mutable.value.import ?: return
        if (form.busy) return
        val instrument = parser.parse(form.text)
        if (instrument == null) {
            mutable.value = mutable.value.copy(import = form.copy(error = "El texto no tiene el formato del cuestionario. Pega el contenido completo del archivo JSON."))
            return
        }
        job?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(import = form.copy(busy = true, error = null, failure = null))
        job = scope.launch {
            val result = manage.create(instrument, imported = true)
            ensureActive(); if (expected != generation) return@launch
            val current = mutable.value
            when (result) {
                is ResearchResult.Success -> {
                    mutable.value = current.copy(import = null, editor = editorOf(result.value), notice = "Importamos el cuestionario como borrador. Elige las respuestas correctas y publícalo.")
                    refreshList()
                }
                is ResearchResult.Failure -> mutable.value = current.copy(import = form.copy(busy = false, failure = result.reason))
            }
        }
    }

    // ---- Borrador ----------------------------------------------------------------------------

    private fun editorOf(draft: InstrumentDraft) = DraftEditorState(draft, draft.questions)

    private fun refreshList() {
        val expected = generation
        scope.launch {
            val result = manage.list()
            if (expected == generation && result is ResearchResult.Success) mutable.value = mutable.value.copy(items = result.value, loaded = true)
        }
    }

    fun openDraft(instrumentId: String) {
        if (mutable.value.openingId != null) return
        job?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(openingId = instrumentId, failure = null, notice = null)
        job = scope.launch {
            val result = manage.draft(instrumentId)
            ensureActive(); if (expected != generation) return@launch
            val current = mutable.value.copy(openingId = null)
            mutable.value = when (result) {
                is ResearchResult.Success -> current.copy(editor = editorOf(result.value))
                is ResearchResult.Failure -> current.copy(failure = result.reason)
            }
        }
    }

    /** Abre un borrador nuevo copiando la última versión publicada; si ya existe uno, lo abre. */
    fun newVersion(instrumentId: String) {
        if (mutable.value.openingId != null) return
        job?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(openingId = instrumentId, failure = null, notice = null)
        job = scope.launch {
            var result = manage.newVersion(instrumentId)
            if (result is ResearchResult.Failure && result.reason == ResearchFailure.DRAFT_EXISTS) result = manage.draft(instrumentId)
            ensureActive(); if (expected != generation) return@launch
            val current = mutable.value.copy(openingId = null)
            when (result) {
                is ResearchResult.Success -> { mutable.value = current.copy(editor = editorOf(result.value)); refreshList() }
                is ResearchResult.Failure -> mutable.value = current.copy(failure = result.reason)
            }
        }
    }

    private fun edit(change: (List<DraftQuestion>) -> List<DraftQuestion>) {
        val editor = mutable.value.editor ?: return
        if (editor.busy) return
        val next = change(editor.questions)
        if (next != editor.questions) mutable.value = mutable.value.copy(editor = editor.copy(questions = next, dirty = true, failure = null))
    }

    private fun List<DraftQuestion>.change(index: Int, block: (DraftQuestion) -> DraftQuestion) =
        mapIndexed { i, question -> if (i == index) block(question) else question }

    fun setText(index: Int, text: String) = edit { it.change(index) { q -> q.copy(text = text.take(ResearchLimits.QUESTION_MAX)) } }

    fun setOption(index: Int, option: Int, text: String) = edit {
        it.change(index) { q -> q.copy(options = q.options.mapIndexed { i, old -> if (i == option) text.take(ResearchLimits.OPTION_MAX) else old }) }
    }

    fun addOption(index: Int) = edit {
        it.change(index) { q -> if (q.type == QuestionType.MULTIPLE_CHOICE && q.options.size < 6) q.copy(options = q.options + "") else q }
    }

    fun removeOption(index: Int, option: Int) = edit {
        it.change(index) { q ->
            if (q.type != QuestionType.MULTIPLE_CHOICE || q.options.size <= 2 || option !in q.options.indices) q
            else q.copy(options = q.options.filterIndexed { i, _ -> i != option }, correctOption = when {
                q.correctOption == null || q.correctOption == option -> null
                q.correctOption > option -> q.correctOption - 1
                else -> q.correctOption
            })
        }
    }

    fun setCorrect(index: Int, option: Int) = edit { it.change(index) { q -> if (q.type == QuestionType.MULTIPLE_CHOICE && option in q.options.indices) q.copy(correctOption = option) else q } }

    fun addQuestion(type: QuestionType) = edit {
        if (it.size >= ResearchLimits.MAX_QUESTIONS) it
        else it + DraftQuestion(type, "", if (type == QuestionType.MULTIPLE_CHOICE) listOf("", "") else emptyList(), null)
    }

    fun removeQuestion(index: Int) = edit { it.filterIndexed { i, _ -> i != index } }

    fun move(index: Int, delta: Int) = edit {
        val target = index + delta
        if (index !in it.indices || target !in it.indices) it
        else it.toMutableList().also { list -> list[index] = it[target]; list[target] = it[index] }
    }

    /** Guarda el borrador con el `rowVersion` vigente; un conflicto 409 pide recargar sin perder los cambios locales. */
    fun save() {
        val editor = mutable.value.editor ?: return
        if (editor.busy || !editor.dirty) return
        job?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(editor = editor.copy(saving = true, failure = null))
        job = scope.launch { persist(expected, editor, publishAfter = false) }
    }

    /** Publica el borrador. Solo se pide al servidor cuando la validación local no encuentra problemas. */
    fun publish() {
        val editor = mutable.value.editor ?: return
        if (editor.busy) return
        if (editor.problems.isNotEmpty()) {
            mutable.value = mutable.value.copy(editor = editor.copy(showIssues = true))
            return
        }
        job?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(editor = editor.copy(publishing = true, failure = null, showIssues = true))
        job = scope.launch { persist(expected, editor, publishAfter = true) }
    }

    private suspend fun persist(expected: Long, editor: DraftEditorState, publishAfter: Boolean) {
        var saved = editor
        if (editor.dirty) {
            val result = manage.save(editor.draft.instrumentId, editor.draft.rowVersion, editor.questions)
            currentCoroutineContext().ensureActive(); if (expected != generation) return
            when (result) {
                is ResearchResult.Success -> saved = DraftEditorState(result.value, result.value.questions, showIssues = editor.showIssues, publishing = publishAfter)
                is ResearchResult.Failure -> {
                    mutable.value = mutable.value.copy(editor = editor.copy(saving = false, publishing = false, failure = result.reason))
                    return
                }
            }
            mutable.value = mutable.value.copy(editor = saved)
        }
        if (!publishAfter) {
            mutable.value = mutable.value.copy(editor = saved.copy(saving = false, publishing = false), notice = "Borrador guardado.")
            return
        }
        val published = manage.publish(saved.draft.instrumentId)
        currentCoroutineContext().ensureActive(); if (expected != generation) return
        when (published) {
            is ResearchResult.Success -> {
                mutable.value = mutable.value.copy(editor = null, notice = "Publicamos la versión ${published.value.versionNumber}.")
                load(force = true)
            }
            is ResearchResult.Failure -> mutable.value = mutable.value.copy(editor = saved.copy(saving = false, publishing = false, failure = published.reason))
        }
    }

    /** Recarga el borrador del servidor, descartando los cambios locales (tras un conflicto). */
    fun reloadDraft() {
        val editor = mutable.value.editor ?: return
        if (editor.busy) return
        job?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(editor = editor.copy(saving = true, failure = null))
        job = scope.launch {
            val result = manage.draft(editor.draft.instrumentId)
            ensureActive(); if (expected != generation) return@launch
            mutable.value = mutable.value.copy(editor = when (result) {
                is ResearchResult.Success -> editorOf(result.value)
                is ResearchResult.Failure -> editor.copy(saving = false, failure = result.reason)
            })
        }
    }

    /** Cierra el editor; con cambios sin guardar pide confirmación primero. Devuelve `true` si había editor. */
    fun closeEditor(): Boolean {
        val editor = mutable.value.editor ?: return false
        if (editor.busy) return true
        mutable.value = if (editor.dirty && !editor.discarding) mutable.value.copy(editor = editor.copy(discarding = true))
        else mutable.value.copy(editor = null)
        return true
    }

    fun keepEditing() {
        val editor = mutable.value.editor ?: return
        mutable.value = mutable.value.copy(editor = editor.copy(discarding = false))
    }

    fun discardEditor() { mutable.value = mutable.value.copy(editor = null) }
}
