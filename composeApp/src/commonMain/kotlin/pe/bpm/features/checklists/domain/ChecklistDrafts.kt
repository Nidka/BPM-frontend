package pe.bpm.features.checklists.domain

import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.domain.DecimalInput

data class RunAnswerInput(val value: String = "", val observation: String = "")

/** La operación propuesta es inmutable: se conserva íntegra antes de enviarla al servidor. */
sealed interface RunOperation {
    data class Start(val input: StartChecklistInput) : RunOperation
    data class Save(val run: ChecklistRun, val key: String, val answers: List<ChecklistAnswer>) : RunOperation
    data class Submit(val run: ChecklistRun, val key: String) : RunOperation
}
data class RunDraft(val run: ChecklistRun?, val inputs: Map<String, RunAnswerInput>, val operation: RunOperation? = null)
interface ChecklistRunDraftRepository {
    suspend fun read(owner: DraftOwner): RunDraft?
    suspend fun write(owner: DraftOwner, draft: RunDraft?)
}
class ManageRunDraft(private val repository: ChecklistRunDraftRepository) {
    suspend fun read(owner: DraftOwner) = repository.read(owner)
    suspend fun write(owner: DraftOwner, draft: RunDraft?) = repository.write(owner, draft)
}

data class TemplateQuestionInput(
    val id: String, val section: String = "", val label: String = "",
    val type: ChecklistResponseType = ChecklistResponseType.COMPLIANCE, val required: Boolean = true,
    val unit: String = "", val minimum: String = "", val maximum: String = "",
) {
    fun domain(index: Int) = ChecklistItem(id, section.trim(), label.trim(), type, index + 1, required, unit.trim(),
        DecimalInput.parse(minimum), DecimalInput.parse(maximum), false)
}
data class TemplateDraft(val id: String, val versionId: String, val name: String, val area: String,
    val description: String, val questions: List<TemplateQuestionInput>, val publicationPending: Boolean = false)
interface ChecklistTemplateDraftRepository {
    suspend fun read(owner: DraftOwner): TemplateDraft?
    suspend fun write(owner: DraftOwner, draft: TemplateDraft?)
}
class ManageTemplateDraft(private val repository: ChecklistTemplateDraftRepository) {
    suspend fun read(owner: DraftOwner) = repository.read(owner)
    suspend fun write(owner: DraftOwner, draft: TemplateDraft?) = repository.write(owner, draft)
}
