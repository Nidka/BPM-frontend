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
    /** El responsable validó su propia corrección por ser el único validador posible. */
    val selfValidated: Boolean = false,
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

    /** Reportan y atienden CHEF, WORKER y el ADMIN miembro; SUPER_ADMIN sin membresía no opera. */
    fun canReport(role: String) = role in setOf("ADMIN", "CHEF", "WORKER")

    fun canAttend(role: String) = canReport(role)

    /**
     * Acciones que se ofrecen en el detalle. El responsable de una acción también ve «Validar», rotulado
     * «Validar sin otro validador» (ver [verifyLabel]): el servidor solo lo permite si no existe otra persona
     * elegible, exige nota y marca la acción como autovalidada; si hay otra persona, responde 403/409.
     */
    fun visibleActions(
        allowed: Collection<IncidentActionType>,
        status: IncidentStatus,
        actions: List<CorrectiveAction>,
        userId: String?,
    ): List<IncidentActionType> =
        if (status == IncidentStatus.VERIFIED) emptyList() else allowed.toList()

    /** Quien es responsable de la corrección valida «sin otro validador»; el resto, «Validar y cerrar». */
    fun isSelfVerification(actions: List<CorrectiveAction>, userId: String?): Boolean =
        userId != null && actions.any { it.responsibleUserId == userId }

    const val SELF_VALIDATION_DENIED = "Otra persona de tu equipo debe validar esta corrección"
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
    /** El registro quedó guardado en este dispositivo y se enviará al recuperar la conexión (éxito local pendiente). */
    QUEUED,
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
