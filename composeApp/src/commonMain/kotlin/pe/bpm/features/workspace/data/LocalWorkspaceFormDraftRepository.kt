package pe.bpm.features.workspace.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.*
import pe.bpm.features.workspace.domain.*

class LocalWorkspaceFormDraftRepository(private val storage: DraftStorage, private val origin: String) : WorkspaceFormDraftRepository {
    private fun document(kind: String): JsonDraftDocument<FormDraftDto> {
        require(kind in setOf("Organization", "Location", "Member"))
        return JsonDraftDocument(storage, origin, "workspace-$kind", FormDraftDto.serializer())
    }
    override suspend fun read(owner: DraftOwner, kind: String) = document(kind).read(owner)?.domain()
    override suspend fun write(owner: DraftOwner, kind: String, draft: WorkspaceFormDraft?) {
        if (draft == null) document(kind).remove(owner)
        else document(kind).write(owner, FormDraftDto(draft.id, draft.name, draft.trade, draft.tax, draft.contactName,
            draft.address, draft.district, draft.phone, draft.email, draft.role, draft.business, draft.locations.toList()))
    }
}
@Serializable private data class FormDraftDto(val id: String, val name: String, val trade: String, val tax: String,
    val contactName: String, val address: String, val district: String, val phone: String, val email: String,
    val role: String, val business: String, val locations: List<String>) {
    fun domain(): WorkspaceFormDraft {
        require(id.isNotBlank() && locations.size <= 100 && listOf(name, trade, tax, contactName, address, district, phone, email).all { it.length <= 2000 })
        require(role in setOf("ADMIN", "CHEF", "WORKER") && business in setOf("RESTAURANT", "CAFETERIA", "CATERING", "RELATED_SERVICE"))
        return WorkspaceFormDraft(id, name, trade, tax, contactName, address, district, phone, email, role, business, locations.toSet())
    }
}
