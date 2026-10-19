package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.checklists.domain.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.DraftAutosave
import pe.bpm.core.presentation.DraftSaveStatus

data class RemovedTemplateQuestion(val input: TemplateQuestionInput, val index: Int)

enum class TemplateEditorStep { Edit, Review, Discard, Published }
data class ChecklistTemplateEditorState(
    val userId: String? = null, val organizationId: String? = null,
    val id: String = "", val versionId: String = "", val name: String = "", val area: String = "", val description: String = "",
    val questions: List<TemplateQuestionInput> = emptyList(), val step: TemplateEditorStep = TemplateEditorStep.Edit,
    val validationRevision: Int = 0, val fields: Map<String, String> = emptyMap(), val failure: PublicationFailure? = null,
    val removed: RemovedTemplateQuestion? = null,
    val restoring: Boolean = false, val localStatus: DraftSaveStatus = DraftSaveStatus.IDLE, val recovered: Boolean = false,
    val publishing: Boolean = false, val uncertain: Boolean = false, val published: ChecklistTemplate? = null,
) {
    val editable get() = !restoring && !publishing && !uncertain && published == null && failure !in setOf(PublicationFailure.CONFLICT, PublicationFailure.SESSION_REQUIRED, PublicationFailure.ACCESS_DENIED)
    val canUseSuggestion get() = editable && name.isBlank() && area.isBlank() && description.isBlank() &&
        questions.size == 1 && questions.single() == TemplateQuestionInput(questions.single().id)
    fun draft() = ChecklistTemplateDraft(id, versionId, name.trim(), description.trim(), area.trim(), questions.mapIndexed { i, item -> item.domain(i) })
}

/** Autoría y recuperación local separadas del catálogo; publicar siempre requiere confirmación. */
class ChecklistTemplateEditorViewModel(private val publish: PublishChecklistTemplate, private val drafts: ManageTemplateDraft? = null, private val newId: () -> String) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistTemplateEditorState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var submitted: ChecklistTemplateDraft? = null

    private val draining = mutableListOf<Job>()
    private var autosave: DraftAutosave<TemplateDraft?>? = null
    fun open(userId: String, org: String) {
        if (mutable.value.userId == userId && mutable.value.organizationId == org && mutable.value.id.isNotEmpty()) return
        reset()
        mutable.value = ChecklistTemplateEditorState(userId, org, id = newId(), versionId = newId(), questions = listOf(TemplateQuestionInput(newId())), restoring = drafts != null)
        if (drafts == null) return
        val owner = DraftOwner(userId, org)
        job = viewModelScope.launch {
            try {
                draining.toList().joinAll()
                val draft = drafts.read(owner)
                currentCoroutineContext().ensureActive()
                if (draft != null) {
                    mutable.value = mutable.value.copy(id = draft.id, versionId = draft.versionId, name = draft.name, area = draft.area,
                        description = draft.description, questions = draft.questions, uncertain = draft.publicationPending, recovered = true,
                        localStatus = DraftSaveStatus.SAVED, step = if (draft.publicationPending) TemplateEditorStep.Review else TemplateEditorStep.Edit)
                    if (draft.publicationPending) submitted = mutable.value.draft()
                }
                var writer: DraftAutosave<TemplateDraft?>? = null
                writer = DraftAutosave(viewModelScope, { drafts.write(owner, it) }) { status ->
                    if (autosave === writer) mutable.value = mutable.value.copy(localStatus = status)
                }
                autosave = writer
                mutable.value = mutable.value.copy(restoring = false)
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { mutable.value = mutable.value.copy(localStatus = DraftSaveStatus.FAILED) }
        }
    }
    fun reset() { job?.cancel(); autosave?.finish()?.let { draining += it }; draining.removeAll { it.isCompleted }; autosave = null; submitted = null; mutable.value = ChecklistTemplateEditorState() }
    private fun snapshot(pending: Boolean = submitted != null) = mutable.value.let { s ->
        TemplateDraft(s.id, s.versionId, s.name, s.area, s.description, s.questions, pending)
    }
    fun protectDraft() {
        if (autosave == null && drafts != null) {
            val s = mutable.value; mutable.value = s.copy(id = "")
            if (s.userId != null && s.organizationId != null) open(s.userId, s.organizationId)
        } else autosave?.schedule(snapshot())
    }
    fun discard(onDiscarded: () -> Unit) {
        if (mutable.value.publishing || mutable.value.uncertain) return
        job = viewModelScope.launch {
            if (autosave?.flush(null) == false) return@launch
            reset(); onDiscarded()
        }
    }
    private fun edit(change: (ChecklistTemplateEditorState) -> ChecklistTemplateEditorState) {
        if (mutable.value.editable) {
            mutable.value = change(mutable.value).copy(fields = emptyMap(), failure = null)
            protectDraft()
        }
    }
    fun name(value: String) = edit { it.copy(name = value.take(2000)) }
    fun area(value: String) = edit { it.copy(area = value.take(2000)) }
    fun description(value: String) = edit { it.copy(description = value.take(2000)) }
    fun useSuggestion(id: String) {
        val suggestion = checklistSuggestions.firstOrNull { it.id == id } ?: return
        val s = mutable.value
        // La UI ofrece ejemplos solo al empezar; nunca sobrescribe trabajo existente.
        if (!s.canUseSuggestion) return
        edit { it.copy(name = suggestion.name, area = suggestion.area, description = suggestion.description,
            questions = suggestion.questions.map { (section, label) -> TemplateQuestionInput(newId(), section, label) }) }
    }
    fun question(id: String, change: (TemplateQuestionInput) -> TemplateQuestionInput) = edit { s -> s.copy(questions = s.questions.map { if (it.id == id) change(it).let { q -> q.copy(id = id, section = q.section.take(2000), label = q.label.take(2000), unit = q.unit.take(2000), minimum = q.minimum.take(2000), maximum = q.maximum.take(2000)) } else it }) }
    fun type(id: String, type: ChecklistResponseType) = question(id) { if (it.type == type) it else it.copy(type = type, minimum = "", maximum = "", unit = "") }
    fun addQuestion() = edit { if (it.questions.size < 100) it.copy(questions = it.questions + TemplateQuestionInput(newId()), removed = null) else it }
    fun removeQuestion(id: String) = edit { s ->
        val index = s.questions.indexOfFirst { it.id == id }
        if (index < 0) s else s.copy(removed = RemovedTemplateQuestion(s.questions[index], index), questions = s.questions.filterNot { it.id == id })
    }
    fun undoRemove() = edit { s ->
        val removed = s.removed
        if (removed == null || s.questions.size >= 100) s else s.copy(questions = s.questions.toMutableList().apply { add(removed.index.coerceAtMost(size), removed.input) }, removed = null)
    }
    fun clearUndo() { mutable.value = mutable.value.copy(removed = null) }
    fun move(id: String, offset: Int) = edit { s ->
        val source = s.questions.indexOfFirst { it.id == id }; val target = source + offset
        if (source < 0 || target !in s.questions.indices) s else s.copy(questions = s.questions.toMutableList().apply { add(target, removeAt(source)) })
    }
    fun review() {
        if (!mutable.value.editable) return
        val fields = publish.validate(mutable.value.draft())
        mutable.value = mutable.value.copy(validationRevision = mutable.value.validationRevision + 1, removed = null, fields = fields, step = if (fields.isEmpty()) TemplateEditorStep.Review else TemplateEditorStep.Edit)
    }
    fun backToEdit() { if (mutable.value.editable) mutable.value = mutable.value.copy(step = TemplateEditorStep.Edit) }
    fun requestDiscard() { if (!mutable.value.publishing && !mutable.value.uncertain && mutable.value.published == null) mutable.value = mutable.value.copy(removed = null, step = TemplateEditorStep.Discard) }
    fun keepEditing() { if (!mutable.value.publishing && !mutable.value.uncertain) mutable.value = mutable.value.copy(step = if (mutable.value.failure == PublicationFailure.CONFLICT) TemplateEditorStep.Review else TemplateEditorStep.Edit) }
    fun confirmPublication() {
        val s = mutable.value; val org = s.organizationId ?: return
        if (s.publishing || s.published != null || s.step != TemplateEditorStep.Review) return
        val draft = submitted ?: s.draft().also { submitted = it }
        mutable.value = s.copy(publishing = true, failure = null, fields = emptyMap())
        job = viewModelScope.launch {
            if (autosave?.flush(snapshot(pending = true)) == false) {
                if (!s.uncertain) submitted = null
                mutable.value = mutable.value.copy(publishing = false, uncertain = s.uncertain,
                    step = if (s.uncertain) TemplateEditorStep.Review else TemplateEditorStep.Edit)
                return@launch
            }
            val result = publish(org, draft)
            currentCoroutineContext().ensureActive()
            mutable.value = when (result) {
                is TemplatePublicationResult.Published -> mutable.value.copy(publishing = false, uncertain = false, published = result.template, step = TemplateEditorStep.Published)
                is TemplatePublicationResult.Rejected -> {
                    val uncertain = (s.uncertain && result.reason != PublicationFailure.CONFLICT) || result.reason in setOf(PublicationFailure.CONNECTION, PublicationFailure.UNAVAILABLE, PublicationFailure.INVALID_RESPONSE)
                    if (!uncertain) submitted = null
                    if (result.reason in setOf(PublicationFailure.ACCESS_DENIED, PublicationFailure.SESSION_REQUIRED)) {
                        ChecklistTemplateEditorState(userId = s.userId, organizationId = org, failure = result.reason)
                    } else mutable.value.copy(publishing = false, uncertain = uncertain, failure = result.reason, fields = result.fields,
                        step = if (result.reason == PublicationFailure.VALIDATION && !uncertain) TemplateEditorStep.Edit else TemplateEditorStep.Review)
                }
            }
            if (result is TemplatePublicationResult.Published) autosave?.flush(null)
            else if (mutable.value.id.isNotEmpty()) protectDraft()
        }
    }
}
