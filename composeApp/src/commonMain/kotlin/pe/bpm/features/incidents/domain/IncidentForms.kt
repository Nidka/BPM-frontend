package pe.bpm.features.incidents.domain

import kotlin.time.Instant
import pe.bpm.core.domain.*

/** El UUID de formulario y el cuerpo pendiente sobreviven al cierre y a respuestas perdidas. */
data class IncidentForm(
    val id: String,
    val createdAt: String,
    val kind: String,
    val values: Map<String, String> = emptyMap(),
    val incidentId: String? = null,
    val catalogId: String? = null,
    val rowVersion: Long = 1,
    val expectedVersion: Int? = null,
)

data class PendingIncident(val path: String, val id: String, val key: String, val body: String)

data class IncidentDraft(
    val site: String,
    val form: IncidentForm,
    val pending: PendingIncident? = null,
)

interface IncidentDraftRepository {
    suspend fun read(owner: DraftOwner, site: String): IncidentDraft?

    suspend fun write(owner: DraftOwner, site: String, draft: IncidentDraft?)
}

sealed interface IncidentSaved {
    data class Catalog(val value: IncidentCatalog) : IncidentSaved

    data class Detail(val value: IncidentDetail) : IncidentSaved
}

interface IncidentTransport {
    fun prepare(form: IncidentForm, site: String, key: String): PendingIncident

    suspend fun submit(
        org: String,
        site: String,
        form: IncidentForm,
        pending: PendingIncident,
    ): IncidentsResult<IncidentSaved>
}

object IncidentsRules {
    fun errors(form: IncidentForm, now: String): Map<String, String> = buildMap {
        fun text(key: String, min: Int, max: Int) {
            InputRules.text(min, max).error(form.values[key].orEmpty())?.let { put(key, it) }
        }
        when (form.kind) {
            "CATALOG" -> {
                if (
                    form.catalogId == null &&
                        !form.values["code"].orEmpty().matches(Regex("[A-Z0-9_-]{1,40}"))
                )
                    put("code", "Usa mayúsculas, números, guion o guion bajo (hasta 40).")
                text("name", 2, 200)
                text("area", 2, 100)
                text("normativeCriterion", 1, 4000)
                if (form.values["riskLevel"] !in IncidentRisk.entries.map { it.name })
                    put("riskLevel", "Selecciona el nivel de riesgo.")
                if (form.values["evidenceRequired"] !in setOf("true", "false"))
                    put("evidenceRequired", "Indica si requiere fotos.")
            }
            "REPORT" -> {
                text("description", 1, 4000)
                if (form.values["catalogId"].isNullOrEmpty())
                    put("catalogId", "Selecciona un criterio del catálogo.")
            }
            "ASSIGN" -> {
                text("description", 1, 2000)
                if (form.values["responsibleUserId"].isNullOrEmpty())
                    put("responsibleUserId", "Selecciona al responsable.")
                val due = runCatching { Instant.parse(form.values["dueAt"].orEmpty()) }.getOrNull()
                if (due == null || due <= Instant.parse(now))
                    put("dueAt", "Indica una fecha y hora futuras en la zona de la sede.")
            }
            "RESOLVE",
            "VERIFY",
            "RETURN" -> text("note", 1, 2000)
            "START" -> Unit
            else -> put("form", "Operación no disponible.")
        }
    }
}

class ManageIncidents(
    private val repository: IncidentsRepository,
    private val transport: IncidentTransport,
    private val drafts: IncidentDraftRepository,
) {
    suspend fun authorize(org: String, site: String, role: String) =
        repository.authorize(org, site, role)

    suspend fun catalog(org: String, page: PageRequest) = repository.catalog(org, page)

    suspend fun incidents(org: String, site: String, status: IncidentStatus?, page: PageRequest) =
        repository.incidents(org, site, status, page)

    suspend fun detail(org: String, id: String) = repository.detail(org, id)

    suspend fun assignees(org: String, id: String, page: PageRequest) =
        repository.assignees(org, id, page)

    suspend fun history(org: String, id: String, page: PageRequest) =
        repository.history(org, id, page)

    fun errors(form: IncidentForm, now: String) = IncidentsRules.errors(form, now)

    fun prepare(form: IncidentForm, site: String, key: String) = transport.prepare(form, site, key)

    suspend fun submit(
        org: String,
        site: String,
        form: IncidentForm,
        pending: PendingIncident,
        role: String,
    ): IncidentsResult<IncidentSaved> {
        if (
            (form.kind == "REPORT" && !IncidentsAccess.canReport(role)) ||
                (form.kind == "CATALOG" && !IncidentsAccess.canAuthor(role))
        )
            return IncidentsResult.Failure(IncidentsFailure.DENIED)
        return transport.submit(org, site, form, pending)
    }

    suspend fun readDraft(owner: DraftOwner, site: String) = drafts.read(owner, site)

    suspend fun writeDraft(owner: DraftOwner, site: String, draft: IncidentDraft?) =
        drafts.write(owner, site, draft)
}
