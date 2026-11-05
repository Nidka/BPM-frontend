package pe.bpm.features.incidents.data

import kotlinx.serialization.json.*
import pe.bpm.features.incidents.domain.*

/** No serializa campos ajenos al comando; el JSON preparado nunca se reconstruye al reintentar. */
fun prepareIncident(form: IncidentForm, site: String, key: String): PendingIncident {
    require(key.length in 8..100 && key.all { it.code in 33..126 })
    val values = form.values
    val path =
        when (form.kind) {
            "CATALOG" ->
                form.catalogId?.let { "incident-catalog/$it/versions" } ?: "incident-catalog"
            "REPORT" -> "incidents"
            else -> "incidents/${requireNotNull(form.incidentId)}/actions"
        }
    val body =
        buildJsonObject {
                when (form.kind) {
                    "CATALOG" -> {
                        put("id", form.id)
                        if (form.catalogId == null) put("code", values["code"].orEmpty().trim())
                        else put("expectedVersion", requireNotNull(form.expectedVersion))
                        for (field in
                            listOf("name", "area", "normativeCriterion", "riskLevel")) put(
                            field,
                            values[field].orEmpty().trim(),
                        )
                        put("evidenceRequired", values["evidenceRequired"] == "true")
                    }
                    "REPORT" -> {
                        put("id", form.id)
                        put("locationId", site)
                        put("catalogId", values["catalogId"].orEmpty())
                        put("description", values["description"].orEmpty().trim())
                        put("clientOperationId", form.id)
                        put("clientCreatedAt", form.createdAt)
                    }
                    else -> {
                        val type = IncidentActionType.valueOf(form.kind)
                        put("operationId", form.id)
                        put("rowVersion", form.rowVersion)
                        put("actionType", type.name)
                        if (type == IncidentActionType.ASSIGN) {
                            put("actionId", values["actionId"].orEmpty())
                            put("responsibleUserId", values["responsibleUserId"].orEmpty())
                            put("description", values["description"].orEmpty().trim())
                            put("dueAt", values["dueAt"].orEmpty())
                        } else if (type != IncidentActionType.START)
                            put("note", values["note"].orEmpty().trim())
                    }
                }
            }
            .toString()
    require(body.encodeToByteArray().size <= 512 * 1024)
    return PendingIncident(path, form.id, key, body)
}
