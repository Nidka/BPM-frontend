package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.checklists.domain.*

data class TemplateQuestionInput(
    val id: String, val section: String = "", val label: String = "",
    val type: ChecklistResponseType = ChecklistResponseType.COMPLIANCE, val required: Boolean = true,
    val unit: String = "", val minimum: String = "", val maximum: String = "",
) {
    fun domain(index: Int) = ChecklistItem(id, section.trim(), label.trim(), type, index + 1, required, unit.trim(),
        minimum.trim().replace(',', '.').toDoubleOrNull(), maximum.trim().replace(',', '.').toDoubleOrNull(), false)
}
data class RemovedTemplateQuestion(val input: TemplateQuestionInput, val index: Int)

enum class TemplateEditorStep { Edit, Review, Discard, Published }
data class ChecklistTemplateEditorState(
    val userId: String? = null, val organizationId: String? = null,
    val id: String = "", val versionId: String = "", val name: String = "", val area: String = "", val description: String = "",
    val questions: List<TemplateQuestionInput> = emptyList(), val step: TemplateEditorStep = TemplateEditorStep.Edit,
    val validationRevision: Int = 0, val fields: Map<String, String> = emptyMap(), val failure: PublicationFailure? = null,
    val removed: RemovedTemplateQuestion? = null,
    val publishing: Boolean = false, val uncertain: Boolean = false, val published: ChecklistTemplate? = null,
) {
    val editable get() = !publishing && !uncertain && published == null && failure !in setOf(PublicationFailure.CONFLICT, PublicationFailure.SESSION_REQUIRED, PublicationFailure.ACCESS_DENIED)
    fun draft() = ChecklistTemplateDraft(id, versionId, name.trim(), description.trim(), area.trim(), questions.mapIndexed { i, item -> item.domain(i) })
}

/** Estado de autoría en memoria, separado del catálogo y de la sesión. No es una cola offline. */
class ChecklistTemplateEditorViewModel(private val publish: PublishChecklistTemplate, private val newId: () -> String) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistTemplateEditorState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var submitted: ChecklistTemplateDraft? = null

    fun open(userId: String, org: String) {
        if (mutable.value.userId == userId && mutable.value.organizationId == org && mutable.value.id.isNotEmpty()) return
        reset()
        mutable.value = ChecklistTemplateEditorState(userId, org, id = newId(), versionId = newId(), questions = listOf(TemplateQuestionInput(newId())))
    }
    fun reset() { job?.cancel(); submitted = null; mutable.value = ChecklistTemplateEditorState() }
    private fun edit(change: (ChecklistTemplateEditorState) -> ChecklistTemplateEditorState) {
        if (mutable.value.editable) mutable.value = change(mutable.value).copy(fields = emptyMap(), failure = null)
    }
    fun name(value: String) = edit { it.copy(name = value) }
    fun area(value: String) = edit { it.copy(area = value) }
    fun description(value: String) = edit { it.copy(description = value) }
    fun question(id: String, change: (TemplateQuestionInput) -> TemplateQuestionInput) = edit { s -> s.copy(questions = s.questions.map { if (it.id == id) change(it).copy(id = id) else it }) }
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
        }
    }
}
