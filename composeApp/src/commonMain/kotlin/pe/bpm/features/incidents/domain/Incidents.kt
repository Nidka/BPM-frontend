package pe.bpm.features.incidents.domain

import pe.bpm.core.domain.*

enum class IncidentRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class IncidentStatus {
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    VERIFIED,
}

enum class IncidentActionType {
    ASSIGN,
    START,
    RESOLVE,
    VERIFY,
    RETURN,
}

data class IncidentCatalog(
    val id: String,
    val code: String,
    val name: String,
    val area: String,
    val normativeCriterion: String,
    val riskLevel: IncidentRisk,
    val evidenceRequired: Boolean,
    val versionNumber: Int,
)

data class IncidentSummary(
    val id: String,
    val locationId: String,
    val catalogId: String,
    val name: String,
    val area: String,
    val riskLevel: IncidentRisk,
    val status: IncidentStatus,
    val description: String,
    val reportedByName: String,
    val createdAt: String,
    val rowVersion: Long,
)

data class CorrectiveAction(
    val id: String,
    val responsibleUserId: String,
    val responsibleName: String,
    val description: String,
    val dueAt: String,
    val status: String,
    val resolutionNote: String?,
    val resolvedAt: String? = null,
    val verifiedAt: String? = null,
    val verifiedBy: String? = null,
)

data class IncidentDetail(
    val summary: IncidentSummary,
    val reportedBy: String,
    val catalogVersion: Int,
    val normativeCriterion: String,
    val evidenceRequired: Boolean,
    val closedAt: String?,
    val actions: List<CorrectiveAction>,
    val allowedActions: Set<IncidentActionType>,
    val beforeCount: Int,
    val afterCount: Int,
)

data class IncidentAssignee(val id: String, val displayName: String, val role: String)

data class IncidentEvent(
    val id: String,
    val operationId: String,
    val fromStatus: String,
    val toStatus: String,
    val actorName: String,
    val note: String?,
    val createdAt: String,
)

object IncidentsAccess {
    fun canRead(role: String) = role in setOf("ADMIN", "SUPER_ADMIN", "CHEF", "WORKER")

    fun canAuthor(role: String) = role in setOf("ADMIN", "SUPER_ADMIN")

    fun canReport(role: String) = role in setOf("CHEF", "WORKER")
}

enum class IncidentsFailure {
    SESSION,
    DENIED,
    NOT_FOUND,
    INVALID,
    CONFLICT,
    INVALID_PAGE,
    CONNECTION,
    UNAVAILABLE,
    INVALID_RESPONSE,
    RATE_LIMITED,
    LOCAL,
}

sealed interface IncidentsResult<out T> {
    data class Success<T>(val value: T) : IncidentsResult<T>

    data class Failure(val reason: IncidentsFailure, val fields: Map<String, String> = emptyMap()) :
        IncidentsResult<Nothing>
}

interface IncidentsRepository {
    suspend fun authorize(org: String, site: String, role: String): IncidentsResult<Unit>

    suspend fun catalog(org: String, page: PageRequest): IncidentsResult<Page<IncidentCatalog>>

    suspend fun incidents(
        org: String,
        site: String,
        status: IncidentStatus?,
        page: PageRequest,
    ): IncidentsResult<Page<IncidentSummary>>

    suspend fun detail(org: String, id: String): IncidentsResult<IncidentDetail>

    suspend fun assignees(
        org: String,
        id: String,
        page: PageRequest,
    ): IncidentsResult<Page<IncidentAssignee>>

    suspend fun history(
        org: String,
        id: String,
        page: PageRequest,
    ): IncidentsResult<Page<IncidentEvent>>
}
