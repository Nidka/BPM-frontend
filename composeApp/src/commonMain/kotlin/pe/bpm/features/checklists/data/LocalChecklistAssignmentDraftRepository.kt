package pe.bpm.features.checklists.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.DraftStorage
import pe.bpm.core.storage.JsonDraftDocument
import pe.bpm.features.checklists.domain.*

class LocalChecklistAssignmentDraftRepository(
    private val storage: DraftStorage,
    private val origin: String,
) : ChecklistAssignmentDraftRepository {
    private fun document(template: String): JsonDraftDocument<AssignmentDraftDto> {
        require(template.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return JsonDraftDocument(
            storage,
            origin,
            "checklist-assignment-$template",
            AssignmentDraftDto.serializer(),
        )
    }

    override suspend fun read(owner: DraftOwner, templateId: String) =
        document(templateId).read(owner)?.domain()?.also {
            require(it.input.templateId == templateId)
        }

    override suspend fun write(owner: DraftOwner, templateId: String, value: AssignmentDraft?) {
        val doc = document(templateId)
        if (value == null) doc.remove(owner)
        else {
            val v = value.input
            require(
                v.templateId == templateId &&
                    v.id.isNotBlank() &&
                    v.id.length <= 64 &&
                    v.locationId.length <= 64 &&
                    v.dueTime.length <= 32
            )
            require(value.locationName.length <= 160)
            doc.write(
                owner,
                AssignmentDraftDto(
                    v.id,
                    v.templateId,
                    v.locationId,
                    v.frequency?.name,
                    v.dueTime,
                    value.pending,
                    value.locationName,
                ),
            )
        }
    }
}

@Serializable
private data class AssignmentDraftDto(
    val id: String,
    val templateId: String,
    val locationId: String,
    val frequency: String?,
    val dueTime: String,
    val pending: Boolean,
    val locationName: String = "",
) {
    fun domain(): AssignmentDraft {
        require(
            id.isNotBlank() &&
                listOf(id, templateId, locationId).all { it.length <= 64 } &&
                dueTime.length <= 32 &&
                locationName.length <= 160
        )
        return AssignmentDraft(
            AssignmentInput(
                id,
                templateId,
                locationId,
                frequency?.let(ChecklistFrequency::valueOf),
                dueTime,
            ),
            pending,
            locationName,
        )
    }
}
