package pe.bpm.features.incidents.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.*
import pe.bpm.features.incidents.domain.*

/** Aislamiento por cuenta, empresa, origen API y sede; el lector primero reautoriza. */
class LocalIncidentDraftRepository(private val storage: DraftStorage, private val origin: String) :
    IncidentDraftRepository {
    private fun document(site: String): JsonDraftDocument<DraftDto> {
        incidentId(site)
        return JsonDraftDocument(storage, origin, "incidents-$site", DraftDto.serializer())
    }

    override suspend fun read(owner: DraftOwner, site: String): IncidentDraft? =
        document(site).read(owner)?.domain(site)

    override suspend fun write(owner: DraftOwner, site: String, draft: IncidentDraft?) {
        val doc = document(site)
        if (draft == null) doc.remove(owner)
        else {
            require(draft.site == site)
            doc.write(owner, DraftDto.from(draft).also { it.domain(site) })
        }
    }
}

@Serializable
private data class PendingDto(val path: String, val id: String, val key: String, val body: String)

@Serializable
private data class DraftDto(
    val site: String,
    val id: String,
    val createdAt: String,
    val kind: String,
    val values: Map<String, String>,
    val incidentId: String?,
    val catalogId: String?,
    val rowVersion: Long,
    val expectedVersion: Int?,
    val pending: PendingDto?,
) {
    fun domain(expectedSite: String): IncidentDraft {
        require(site == expectedSite)
        incidentId(id)
        incidentInstant(createdAt)
        incidentId?.let(::incidentId)
        catalogId?.let(::incidentId)
        require(
            values.size <= 24 &&
                values.all { it.key.length <= 40 && it.value.length <= 4000 } &&
                rowVersion > 0
        )
        require(
            kind in setOf("CATALOG", "REPORT", "ASSIGN", "START", "RESOLVE", "VERIFY", "RETURN")
        )
        val form =
            IncidentForm(
                id,
                createdAt,
                kind,
                values,
                incidentId,
                catalogId,
                rowVersion,
                expectedVersion,
            )
        val p = pending?.let { PendingIncident(it.path, it.id, it.key, it.body) }
        if (p != null) require(p == prepareIncident(form, site, p.key))
        return IncidentDraft(site, form, p)
    }

    companion object {
        fun from(v: IncidentDraft) =
            v.form.let { f ->
                DraftDto(
                    v.site,
                    f.id,
                    f.createdAt,
                    f.kind,
                    f.values,
                    f.incidentId,
                    f.catalogId,
                    f.rowVersion,
                    f.expectedVersion,
                    v.pending?.let { PendingDto(it.path, it.id, it.key, it.body) },
                )
            }
    }
}
