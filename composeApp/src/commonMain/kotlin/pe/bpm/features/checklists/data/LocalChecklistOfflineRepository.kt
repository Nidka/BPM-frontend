package pe.bpm.features.checklists.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.DraftStorage
import pe.bpm.core.storage.JsonDraftDocument
import pe.bpm.features.checklists.domain.*

/** Colección acotada y atómica por origen/cuenta/empresa; no almacena autorizaciones. */
class LocalChecklistOfflineRepository(storage: DraftStorage, origin: String) : ChecklistOfflineRepository {
    private val document = JsonDraftDocument(storage, origin, "checklist-offline-queue", OfflineQueueDto.serializer())
    override suspend fun read(owner: DraftOwner) = decode(owner, document.read(owner))
    override suspend fun update(owner: DraftOwner, transform: (List<OfflineChecklist>) -> List<OfflineChecklist>): List<OfflineChecklist> {
        val result = document.update(owner) { previous ->
            val entries = transform(decode(owner, previous))
            validate(owner, entries)
            OfflineQueueDto(entries.map { it.dto() })
        }
        return decode(owner, result)
    }
    private fun decode(owner: DraftOwner, value: OfflineQueueDto?) = value?.entries.orEmpty().map { it.domain() }.also { validate(owner, it) }
    private fun validate(owner: DraftOwner, entries: List<OfflineChecklist>) {
        require(entries.size <= 20 && entries.map { it.run.id }.distinct().size == entries.size)
        entries.forEach { entry ->
            val run = entry.run
            require(run.organizationId == owner.organizationId && run.performedBy == owner.userId && entry.attempts >= 0)
            require(entry.inputs.size <= 100 && entry.inputs.all { (id, input) ->
                run.items.any { it.id == id } && input.value.length <= 2000 && input.observation.length <= 2000
            })
            val upload = entry.upload
            require((upload != null) == (entry.status in setOf(OfflineRunStatus.PENDING, OfflineRunStatus.SENDING, OfflineRunStatus.ATTENTION)))
            if (upload != null) {
                require(run.status == "DRAFT" && upload.saveKey.length in 8..100 && upload.submitKey.length in 8..100 &&
                    validRunAnswers(run.items, upload.answers, complete = upload.finalize, evidenceCounts = run.evidenceCounts))
                upload.savedRun?.let { saved ->
                    require(saved.id == run.id && saved.organizationId == owner.organizationId && saved.performedBy == owner.userId &&
                        saved.locationId == run.locationId && saved.templateVersionId == run.templateVersionId && saved.items == run.items &&
                        saved.status == "DRAFT" && saved.rowVersion > run.rowVersion && saved.answers.toSet() == upload.answers.toSet())
                }
            }
        }
    }
}

@Serializable private data class OfflineQueueDto(val entries: List<OfflineEntryDto>)
@Serializable private data class OfflineInputDto(val value: String, val observation: String)
@Serializable private data class OfflineEntryDto(
    val run: RunDto, val inputs: Map<String, OfflineInputDto>, val status: String,
    val upload: OfflineUploadDto?, val failure: String?, val attempts: Int,
) {
    fun domain() = OfflineChecklist(run.domain(), inputs.mapValues { RunAnswerInput(it.value.value, it.value.observation) },
        OfflineRunStatus.valueOf(status), upload?.domain(), failure?.let(RunFailure::valueOf), attempts)
}
@Serializable private data class OfflineUploadDto(
    val answers: List<AnswerDto>, val saveKey: String, val finalize: Boolean, val submitKey: String, val savedRun: RunDto?,
) {
    fun domain() = OfflineRunUpload(answers.map { it.domain() }, saveKey, finalize, submitKey, savedRun?.domain())
}
private fun OfflineChecklist.dto() = OfflineEntryDto(run.dto(), inputs.mapValues { OfflineInputDto(it.value.value, it.value.observation) },
    status.name, upload?.let { OfflineUploadDto(it.answers.map { answer -> answer.dto() }, it.saveKey, it.finalize, it.submitKey, it.savedRun?.dto()) }, failure?.name, attempts)
