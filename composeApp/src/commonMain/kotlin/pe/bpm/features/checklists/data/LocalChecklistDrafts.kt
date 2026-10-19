package pe.bpm.features.checklists.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.DraftStorage
import pe.bpm.core.storage.JsonDraftDocument
import pe.bpm.features.checklists.domain.*

/** Copia recuperable de un control en curso por cuenta y empresa; no es una autorización offline. */
class LocalChecklistRunDraftRepository(storage: DraftStorage, origin: String) : ChecklistRunDraftRepository {
    private val document = JsonDraftDocument(storage, origin, "checklist-run", RunDraftDto.serializer())
    override suspend fun read(owner: DraftOwner): RunDraft? = document.read(owner)?.domain()?.also { validate(owner, it) }
    override suspend fun write(owner: DraftOwner, draft: RunDraft?) {
        if (draft == null) document.remove(owner)
        else { validate(owner, draft); document.write(owner, draft.dto()) }
    }
    private fun validate(owner: DraftOwner, draft: RunDraft) {
        val run = draft.run
        require(run != null || draft.operation is RunOperation.Start)
        listOfNotNull(run, when (val action = draft.operation) {
            is RunOperation.Save -> action.run
            is RunOperation.Submit -> action.run
            else -> null
        }).forEach { require(it.organizationId == owner.organizationId && it.performedBy == owner.userId && it.status == "DRAFT") }
        when (val operation = draft.operation) {
            is RunOperation.Start -> require(run == null && draft.inputs.isEmpty() && listOf(operation.input.id,
                operation.input.locationId, operation.input.templateVersionId, operation.input.clientOperationId,
                operation.input.clientCreatedAt).all { it.isNotBlank() })
            is RunOperation.Save -> require(operation.run == run && operation.key.length in 8..100 &&
                validRunAnswers(operation.run.items, operation.answers, complete = false))
            is RunOperation.Submit -> require(operation.run == run && operation.key.length in 8..100 &&
                validRunAnswers(operation.run.items, operation.run.answers, complete = true))
            null -> Unit
        }
        require(draft.inputs.size <= 100 && draft.inputs.all { (id, input) ->
            run?.items?.any { it.id == id } == true && input.value.length <= 2000 && input.observation.length <= 2000
        })
    }
}

class LocalChecklistTemplateDraftRepository(storage: DraftStorage, origin: String) : ChecklistTemplateDraftRepository {
    private val document = JsonDraftDocument(storage, origin, "checklist-template", TemplateDraftDto.serializer())
    override suspend fun read(owner: DraftOwner) = document.read(owner)?.domain()
    override suspend fun write(owner: DraftOwner, draft: TemplateDraft?) {
        if (draft == null) document.remove(owner) else document.write(owner, draft.dto())
    }
}

@Serializable private data class InputDto(val value: String, val observation: String)
@Serializable private data class RunDraftDto(val run: RunDto?, val inputs: Map<String, InputDto>, val operation: OperationDto?) {
    fun domain() = RunDraft(run?.domain(), inputs.mapValues { RunAnswerInput(it.value.value, it.value.observation) }, operation?.domain())
}
@Serializable private data class OperationDto(val kind: String, val run: RunDto? = null, val key: String = "",
    val answers: List<AnswerDto> = emptyList(), val start: StartDraftDto? = null) {
    fun domain(): RunOperation = when (kind) {
        "start" -> RunOperation.Start(requireNotNull(start).domain())
        "save" -> RunOperation.Save(requireNotNull(run).domain(), key.also { require(it.isNotBlank()) }, answers.map { it.domain() })
        "submit" -> RunOperation.Submit(requireNotNull(run).domain(), key.also { require(it.isNotBlank()) })
        else -> error("Operación local desconocida")
    }
}
@Serializable private data class StartDraftDto(val id: String, val locationId: String, val templateVersionId: String,
    val clientOperationId: String, val clientCreatedAt: String) {
    fun domain() = StartChecklistInput(id, locationId, templateVersionId, clientOperationId, clientCreatedAt)
}
private fun RunDraft.dto() = RunDraftDto(run?.dto(), inputs.mapValues { InputDto(it.value.value, it.value.observation) }, when (val action = operation) {
    is RunOperation.Start -> OperationDto("start", start = action.input.let { StartDraftDto(it.id, it.locationId, it.templateVersionId, it.clientOperationId, it.clientCreatedAt) })
    is RunOperation.Save -> OperationDto("save", action.run.dto(), action.key, action.answers.map { it.dto() })
    is RunOperation.Submit -> OperationDto("submit", action.run.dto(), action.key)
    null -> null
})
@Serializable private data class QuestionDraftDto(val id: String, val section: String, val label: String, val type: String,
    val required: Boolean, val unit: String, val minimum: String, val maximum: String) {
    fun domain(): TemplateQuestionInput {
        require(id.isNotBlank() && listOf(section, label, unit, minimum, maximum).all { it.length <= 2000 })
        return TemplateQuestionInput(id, section, label, ChecklistResponseType.valueOf(type), required, unit, minimum, maximum)
    }
}
@Serializable private data class TemplateDraftDto(val id: String, val versionId: String, val name: String, val area: String,
    val description: String, val questions: List<QuestionDraftDto>, val publicationPending: Boolean) {
    fun domain(): TemplateDraft {
        require(id.isNotBlank() && versionId.isNotBlank() && questions.size <= 100 &&
            questions.map { it.id }.distinct().size == questions.size && listOf(name, area, description).all { it.length <= 2000 })
        return TemplateDraft(id, versionId, name, area, description, questions.map { it.domain() }, publicationPending)
    }
}
private fun TemplateDraft.dto() = TemplateDraftDto(id, versionId, name, area, description,
    questions.map { QuestionDraftDto(it.id, it.section, it.label, it.type.name, it.required, it.unit, it.minimum, it.maximum) }, publicationPending)
