package pe.bpm.features.workspace.domain

import pe.bpm.core.domain.DraftOwner

/** Datos de edición; roles y sedes se vuelven a autorizar al enviar, nunca desde esta copia. */
data class WorkspaceFormDraft(val id: String, val name: String = "", val trade: String = "", val tax: String = "",
    val contactName: String = "", val address: String = "", val district: String = "", val phone: String = "",
    val email: String = "", val role: String = "WORKER", val business: String = "RESTAURANT", val locations: Set<String> = emptySet())
interface WorkspaceFormDraftRepository {
    suspend fun read(owner: DraftOwner, kind: String): WorkspaceFormDraft?
    suspend fun write(owner: DraftOwner, kind: String, draft: WorkspaceFormDraft?)
}
class ManageWorkspaceFormDraft(private val repository: WorkspaceFormDraftRepository) {
    suspend fun read(owner: DraftOwner, kind: String) = repository.read(owner, kind)
    suspend fun write(owner: DraftOwner, kind: String, draft: WorkspaceFormDraft?) = repository.write(owner, kind, draft)
}
