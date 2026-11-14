package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.checklists.domain.*

enum class VersionMode {
    History,
    Edit,
    Review,
}

data class ChecklistVersionsState(
    val userId: String? = null,
    val organizationId: String? = null,
    val templateId: String? = null,
    val current: ChecklistTemplate? = null,
    val history: List<ChecklistVersionSummary> = emptyList(),
    val token: String = "",
    val previous: List<String> = emptyList(),
    val nextToken: String = "",
    val loading: Boolean = false,
    val failure: VersionFailure? = null,
    val mode: VersionMode = VersionMode.History,
    val questions: List<TemplateQuestionInput> = emptyList(),
    val changeNote: String = "",
    val versionId: String = "",
    val fields: Map<String, String> = emptyMap(),
    val busy: Boolean = false,
    /** El envío no se pudo confirmar: se conserva el mismo `versionId` para comprobarlo. */
    val uncertain: Boolean = false,
    val published: ChecklistTemplate? = null,
    val viewing: ChecklistTemplate? = null,
    val viewingLoading: Boolean = false,
    val viewingFailure: VersionFailure? = null,
    /** Otra persona publicó antes: hay que recargar y rehacer los cambios. */
    val conflict: Boolean = false,
) {
    val editable get() = mode == VersionMode.Edit && !busy && !uncertain && published == null
}

/** Historial de versiones y edición de preguntas de una plantilla publicada (solo ADMIN en el servidor). */
class ChecklistVersionsViewModel(
    private val loadTemplate: LoadChecklistTemplate,
    private val loadVersions: LoadChecklistVersions,
    private val loadVersion: LoadChecklistVersion,
    private val publish: PublishChecklistVersion,
    private val newId: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistVersionsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var viewJob: Job? = null

    fun reset() {
        job?.cancel()
        viewJob?.cancel()
        mutable.value = ChecklistVersionsState()
    }

    fun open(userId: String, organizationId: String, templateId: String) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.templateId == templateId) return
        reset()
        mutable.value = ChecklistVersionsState(userId, organizationId, templateId)
        reload()
    }

    /** Recarga la versión vigente y el historial; descarta una edición en conflicto. */
    fun reload() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val template = s.templateId ?: return
        job?.cancel()
        mutable.value =
            s.copy(
                loading = true,
                failure = null,
                mode = VersionMode.History,
                token = "",
                previous = emptyList(),
                fields = emptyMap(),
                uncertain = false,
                busy = false,
                conflict = false,
            )
        job =
            viewModelScope.launch {
                val current = loadTemplate(org, template)
                currentCoroutineContext().ensureActive()
                if (current is ChecklistTemplateResult.Failure) {
                    mutable.value = mutable.value.copy(loading = false, failure = current.reason.version())
                    return@launch
                }
                val history = loadVersions(org, template, PageRequest())
                currentCoroutineContext().ensureActive()
                mutable.value =
                    when (history) {
                        is VersionResult.Success ->
                            mutable.value.copy(
                                loading = false,
                                current = (current as ChecklistTemplateResult.Success).template,
                                history = history.value.items,
                                nextToken = history.value.nextToken,
                            )
                        is VersionResult.Failure ->
                            mutable.value.copy(
                                loading = false,
                                current = (current as ChecklistTemplateResult.Success).template,
                                failure = history.reason,
                            )
                    }
            }
    }

    fun next() = page(mutable.value.let { it.copy(token = it.nextToken, previous = it.previous + it.token) }, mutable.value.nextToken.isNotEmpty())

    fun previousPage() =
        page(mutable.value.let { it.copy(token = it.previous.lastOrNull().orEmpty(), previous = it.previous.dropLast(1)) }, mutable.value.previous.isNotEmpty())

    private fun page(target: ChecklistVersionsState, allowed: Boolean) {
        val org = target.organizationId ?: return
        val template = target.templateId ?: return
        if (!allowed || mutable.value.loading) return
        job?.cancel()
        mutable.value = target.copy(loading = true, failure = null)
        job =
            viewModelScope.launch {
                val result = loadVersions(org, template, PageRequest(target.token))
                currentCoroutineContext().ensureActive()
                mutable.value =
                    when (result) {
                        is VersionResult.Success ->
                            mutable.value.copy(loading = false, history = result.value.items, nextToken = result.value.nextToken)
                        is VersionResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
                    }
            }
    }

    fun view(versionId: String) {
        val s = mutable.value
        val org = s.organizationId ?: return
        val template = s.templateId ?: return
        viewJob?.cancel()
        mutable.value = s.copy(viewing = null, viewingLoading = true, viewingFailure = null)
        viewJob =
            viewModelScope.launch {
                val result = loadVersion(org, template, versionId)
                currentCoroutineContext().ensureActive()
                mutable.value =
                    when (result) {
                        is VersionResult.Success -> mutable.value.copy(viewing = result.value, viewingLoading = false)
                        is VersionResult.Failure -> mutable.value.copy(viewingLoading = false, viewingFailure = result.reason)
                    }
            }
    }

    fun closeViewing() {
        viewJob?.cancel()
        mutable.value = mutable.value.copy(viewing = null, viewingLoading = false, viewingFailure = null)
    }

    /** Copia las preguntas vigentes con identificadores nuevos para crear la versión siguiente. */
    fun startEdit() {
        val s = mutable.value
        val current = s.current ?: return
        if (s.loading || s.busy) return
        mutable.value =
            s.copy(
                mode = VersionMode.Edit,
                questions = ChecklistVersionRules.editable(current.items, newId),
                changeNote = "",
                versionId = newId(),
                fields = emptyMap(),
                failure = null,
                conflict = false,
                uncertain = false,
                published = null,
            )
    }

    private fun edit(change: (ChecklistVersionsState) -> ChecklistVersionsState) {
        val s = mutable.value
        if (!s.editable) return
        mutable.value = change(s).copy(failure = null, fields = emptyMap())
    }

    fun changeNote(value: String) = edit { it.copy(changeNote = value.take(2000)) }

    fun question(id: String, change: (TemplateQuestionInput) -> TemplateQuestionInput) = edit { s ->
        s.copy(
            questions =
                s.questions.map {
                    if (it.id == id)
                        change(it).let { q ->
                            q.copy(
                                id = id,
                                section = q.section.take(2000),
                                label = q.label.take(2000),
                                unit = q.unit.take(2000),
                                minimum = q.minimum.take(2000),
                                maximum = q.maximum.take(2000),
                            )
                        }
                    else it
                }
        )
    }

    fun type(id: String, type: ChecklistResponseType) = question(id) {
        if (it.type == type) it
        else
            it.copy(
                type = type,
                minimum = "",
                maximum = "",
                unit = "",
                evidenceRequired = it.evidenceRequired && type != ChecklistResponseType.TEXT,
            )
    }

    fun addQuestion() = edit { if (it.questions.size < 100) it.copy(questions = it.questions + TemplateQuestionInput(newId())) else it }

    fun removeQuestion(id: String) = edit { s -> s.copy(questions = s.questions.filterNot { it.id == id }) }

    fun move(id: String, offset: Int) = edit { s ->
        val source = s.questions.indexOfFirst { it.id == id }
        val target = source + offset
        if (source < 0 || target !in s.questions.indices) s
        else s.copy(questions = s.questions.toMutableList().apply { add(target, removeAt(source)) })
    }

    private fun draft(s: ChecklistVersionsState) =
        ChecklistVersionDraft(
            s.versionId,
            s.current?.versionNumber ?: 0,
            s.changeNote.trim(),
            s.questions.mapIndexed { index, input -> input.domain(index) },
        )

    fun review() {
        val s = mutable.value
        val current = s.current ?: return
        if (!s.editable) return
        val fields = publish.validate(draft(s), current)
        mutable.value =
            s.copy(fields = fields, mode = if (fields.isEmpty()) VersionMode.Review else VersionMode.Edit)
    }

    fun backToEdit() {
        val s = mutable.value
        if (s.mode == VersionMode.Review && !s.busy && !s.uncertain) mutable.value = s.copy(mode = VersionMode.Edit)
    }

    fun cancelEdit() {
        val s = mutable.value
        if (!s.busy && !s.uncertain) mutable.value = s.copy(mode = VersionMode.History, fields = emptyMap(), failure = null, conflict = false)
    }

    fun confirmPublication() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val template = s.templateId ?: return
        val current = s.current ?: return
        if (s.mode != VersionMode.Review || s.busy) return
        mutable.value = s.copy(busy = true, failure = null, fields = emptyMap())
        job =
            viewModelScope.launch {
                val result = publish(org, template, current, draft(s))
                currentCoroutineContext().ensureActive()
                when (result) {
                    is VersionResult.Success -> {
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                uncertain = false,
                                published = result.value,
                                current = result.value,
                                mode = VersionMode.History,
                            )
                        reloadHistory()
                    }
                    is VersionResult.Failure -> {
                        val uncertain =
                            result.reason in
                                setOf(
                                    VersionFailure.CONNECTION,
                                    VersionFailure.UNAVAILABLE,
                                    VersionFailure.INVALID_RESPONSE,
                                    VersionFailure.RATE_LIMITED,
                                )
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                uncertain = uncertain,
                                failure = result.reason,
                                fields = result.fields,
                                conflict = result.reason == VersionFailure.VERSION_CONFLICT,
                                // La misma versión con otro contenido es un conflicto de identidad:
                                // se renuevan los UUID para poder reintentar el contenido actual.
                                versionId = if (result.reason == VersionFailure.CONFLICT) newId() else s.versionId,
                                questions =
                                    if (result.reason == VersionFailure.CONFLICT) mutable.value.questions.map { it.copy(id = newId()) }
                                    else mutable.value.questions,
                                mode = if (result.reason == VersionFailure.VALIDATION) VersionMode.Edit else VersionMode.Review,
                            )
                    }
                }
            }
    }

    private fun reloadHistory() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val template = s.templateId ?: return
        viewModelScope.launch {
            val result = loadVersions(org, template, PageRequest())
            if (result is VersionResult.Success)
                mutable.value = mutable.value.copy(history = result.value.items, nextToken = result.value.nextToken, token = "", previous = emptyList())
        }
    }

    private fun ChecklistFailure.version() =
        when (this) {
            ChecklistFailure.SESSION_REQUIRED -> VersionFailure.SESSION
            ChecklistFailure.ACCESS_DENIED -> VersionFailure.DENIED
            ChecklistFailure.NOT_FOUND -> VersionFailure.NOT_FOUND
            ChecklistFailure.RATE_LIMITED -> VersionFailure.RATE_LIMITED
            ChecklistFailure.CONNECTION -> VersionFailure.CONNECTION
            ChecklistFailure.INVALID_PAGE -> VersionFailure.INVALID_PAGE
            ChecklistFailure.INVALID_RESPONSE -> VersionFailure.INVALID_RESPONSE
            ChecklistFailure.UNAVAILABLE -> VersionFailure.UNAVAILABLE
        }
}

internal fun versionFailureMessage(failure: VersionFailure) =
    when (failure) {
        VersionFailure.SESSION -> "Tu sesión terminó. Vuelve a iniciar sesión."
        VersionFailure.DENIED -> "Solo un administrador puede publicar versiones de una plantilla."
        VersionFailure.NOT_FOUND -> "La plantilla o la versión ya no está disponible."
        VersionFailure.VALIDATION -> "Revisa los campos indicados antes de publicar."
        VersionFailure.VERSION_CONFLICT ->
            "Otra persona publicó una versión nueva mientras editabas. Recarga la plantilla y vuelve a aplicar tus cambios."
        VersionFailure.CONFLICT ->
            "Esta versión choca con otra ya publicada. Preparamos identificadores nuevos: vuelve a publicar."
        VersionFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentar."
        VersionFailure.CONNECTION,
        VersionFailure.UNAVAILABLE,
        VersionFailure.INVALID_RESPONSE ->
            "No pudimos confirmar la publicación. Conservamos los cambios: reintenta para comprobar la misma versión."
        VersionFailure.INVALID_PAGE -> "La lista cambió. Actualízala para volver a empezar."
    }
