package pe.bpm.features.incidents.data

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import pe.bpm.features.incidents.domain.*

internal fun incidentId(value: String) {
    require(
        value.matches(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        )
    )
}

internal fun incidentText(value: String, max: Int, min: Int = 0) {
    require(
        value.count { !it.isLowSurrogate() } in min..max &&
            value.none { it.isISOControl() && it != '\n' && it != '\t' }
    )
}

internal fun incidentUserId(value: String) {
    require(value.count { !it.isLowSurrogate() } in 1..128 && value.isNotBlank() && value.none { it.isISOControl() })
}

internal fun incidentInstant(value: String) {
    Instant.parse(value)
}

internal fun sameIncidentInstant(a: String, b: String): Boolean {
    val x = Instant.parse(a)
    val y = Instant.parse(b)
    return x.epochSeconds == y.epochSeconds &&
        x.nanosecondsOfSecond / 1000 == y.nanosecondsOfSecond / 1000
}

@Serializable
internal data class CatalogDto(
    val id: String,
    val code: String,
    val name: String,
    val area: String,
    val normativeCriterion: String,
    val riskLevel: String,
    val evidenceRequired: Boolean,
    val versionNumber: Int,
) {
    fun domain() =
        IncidentCatalog(
                id,
                code,
                name,
                area,
                normativeCriterion,
                IncidentRisk.valueOf(riskLevel),
                evidenceRequired,
                versionNumber,
            )
            .also {
                incidentId(id)
                require(code.matches(Regex("[A-Z0-9_-]{1,40}")))
                incidentText(name, 200, 2)
                incidentText(area, 100, 2)
                incidentText(normativeCriterion, 4000, 1)
                require(versionNumber > 0)
            }
}

@Serializable
internal data class SummaryDto(
    val id: String,
    val locationId: String,
    val catalogId: String,
    val name: String,
    val area: String,
    val riskLevel: String,
    val status: String,
    val description: String,
    val reportedByName: String,
    val createdAt: String,
    val rowVersion: Long,
) {
    fun domain() =
        IncidentSummary(
                id,
                locationId,
                catalogId,
                name,
                area,
                IncidentRisk.valueOf(riskLevel),
                IncidentStatus.valueOf(status),
                description,
                reportedByName,
                createdAt,
                rowVersion,
            )
            .also {
                listOf(id, locationId, catalogId).forEach(::incidentId)
                incidentText(name, 200, 2)
                incidentText(area, 100, 2)
                incidentText(description, 4000, 1)
                incidentText(reportedByName, 200)
                incidentInstant(createdAt)
                require(rowVersion > 0)
            }
}

@Serializable
internal data class ActionDto(
    val id: String,
    val responsibleUserId: String,
    val responsibleName: String,
    val description: String,
    val dueAt: String,
    val status: String,
    val resolutionNote: String? = null,
    val resolvedAt: String? = null,
    val verifiedAt: String? = null,
    val verifiedBy: String? = null,
) {
    fun domain() =
        CorrectiveAction(
                id,
                responsibleUserId,
                responsibleName,
                description,
                dueAt,
                status,
                resolutionNote,
                resolvedAt,
                verifiedAt,
                verifiedBy,
            )
            .also {
                incidentId(id)
                incidentUserId(responsibleUserId)
                incidentText(responsibleName, 200)
                incidentText(description, 2000, 1)
                incidentInstant(dueAt)
                require(
                    status in
                        setOf(
                            "PENDING",
                            "ASSIGNED",
                            "IN_PROGRESS",
                            "RESOLVED",
                            "VERIFIED",
                            "CANCELLED",
                        )
                )
                resolutionNote?.let { incidentText(it, 2000) }
                listOfNotNull(resolvedAt, verifiedAt).forEach(::incidentInstant)
                verifiedBy?.let(::incidentUserId)
            }
}

@Serializable
internal data class DetailDto(
    val id: String,
    val locationId: String,
    val catalogId: String,
    val name: String,
    val area: String,
    val riskLevel: String,
    val status: String,
    val description: String,
    val reportedByName: String,
    val createdAt: String,
    val rowVersion: Long,
    val reportedBy: String,
    val catalogVersion: Int,
    val normativeCriterion: String,
    val evidenceRequired: Boolean,
    val closedAt: String? = null,
    val actions: List<ActionDto>,
    val allowedActions: List<String>,
    val beforeCount: Int,
    val afterCount: Int,
) {
    fun domain() =
        IncidentDetail(
                SummaryDto(
                        id,
                        locationId,
                        catalogId,
                        name,
                        area,
                        riskLevel,
                        status,
                        description,
                        reportedByName,
                        createdAt,
                        rowVersion,
                    )
                    .domain(),
                reportedBy,
                catalogVersion,
                normativeCriterion,
                evidenceRequired,
                closedAt,
                actions.map { it.domain() },
                allowedActions.map(IncidentActionType::valueOf).toSet(),
                beforeCount,
                afterCount,
            )
            .also {
                incidentUserId(reportedBy)
                require(catalogVersion > 0 && beforeCount >= 0 && afterCount >= 0)
                require(
                    actions.distinctBy { it.id }.size == actions.size &&
                        allowedActions.distinct().size == allowedActions.size
                )
                incidentText(normativeCriterion, 4000, 1)
                closedAt?.let(::incidentInstant)
            }
}

@Serializable
internal data class AssigneeDto(val id: String, val displayName: String, val role: String) {
    fun domain() =
        IncidentAssignee(id, displayName, role).also {
            incidentUserId(id)
            require(role in setOf("CHEF", "WORKER"))
            incidentText(displayName, 200, 1)
        }
}

@Serializable
internal data class EventDto(
    val id: String,
    val operationId: String,
    val fromStatus: String,
    val toStatus: String,
    val actorName: String,
    val note: String? = null,
    val createdAt: String,
) {
    fun domain() =
        IncidentEvent(id, operationId, fromStatus, toStatus, actorName, note, createdAt).also {
            incidentId(id)
            incidentId(operationId)
            require(fromStatus.isEmpty() || fromStatus in IncidentStatus.entries.map { it.name })
            IncidentStatus.valueOf(toStatus)
            incidentText(actorName, 200)
            note?.let { incidentText(it, 4000) }
            incidentInstant(createdAt)
        }
}
