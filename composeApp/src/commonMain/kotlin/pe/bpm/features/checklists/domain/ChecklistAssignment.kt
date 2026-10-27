package pe.bpm.features.checklists.domain

import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.domain.InputRule
import pe.bpm.core.domain.PageRequest

enum class ChecklistFrequency {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY,
}

data class AssignedChecklistRun(
    val id: String,
    val status: String,
    val performedBy: String,
    val performedByName: String,
    val createdAt: String,
    val submittedAt: String? = null,
)

data class ChecklistAssignment(
    val id: String,
    val templateId: String,
    val templateName: String,
    val templateVersionId: String,
    val locationId: String,
    val locationName: String,
    val frequency: ChecklistFrequency,
    val dueTime: String,
    val timezone: String,
    val active: Boolean,
    val createdAt: String,
    val periodStartsAt: String? = null,
    val periodEndsAt: String? = null,
    val run: AssignedChecklistRun? = null,
)

data class AssignmentInput(
    val id: String,
    val templateId: String,
    val locationId: String = "",
    val frequency: ChecklistFrequency? = null,
    val dueTime: String = "",
)

enum class AssignmentFailure {
    SESSION,
    DENIED,
    NOT_FOUND,
    INVALID,
    CONFLICT,
    RATE_LIMITED,
    CONNECTION,
    UNAVAILABLE,
    INVALID_RESPONSE,
    INVALID_PAGE,
}

sealed interface AssignmentResult {
    data class Success(val value: ChecklistAssignment) : AssignmentResult

    data class Failure(
        val reason: AssignmentFailure,
        val fields: Map<String, String> = emptyMap(),
    ) : AssignmentResult
}

sealed interface AssignmentPageResult {
    data class Success(val items: List<ChecklistAssignment>, val nextToken: String) :
        AssignmentPageResult

    data class Failure(val reason: AssignmentFailure) : AssignmentPageResult
}

interface ChecklistAssignmentReader {
    suspend fun list(organizationId: String, page: PageRequest): AssignmentPageResult

    suspend fun get(organizationId: String, id: String): AssignmentResult
}

interface ChecklistAssignmentWriter {
    suspend fun create(organizationId: String, input: AssignmentInput): AssignmentResult

    suspend fun deactivate(organizationId: String, id: String): AssignmentResult
}

class LoadChecklistAssignments(private val reader: ChecklistAssignmentReader) {
    suspend operator fun invoke(org: String, page: PageRequest) = reader.list(org, page)
}

class LoadChecklistAssignment(private val reader: ChecklistAssignmentReader) {
    suspend operator fun invoke(org: String, id: String) = reader.get(org, id)
}

object AssignmentInputRules {
    val time = InputRule {
        if (it.isEmpty() || Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it)) null
        else "Usa una hora como 08:30 o deja este campo vacío."
    }

    fun errors(input: AssignmentInput) = buildMap {
        if (input.locationId.isBlank())
            put("locationId", "Selecciona la sede donde se realizará el control.")
        if (input.frequency == null) put("frequency", "Selecciona cuándo se repetirá el control.")
        time.error(input.dueTime)?.let { put("dueTime", it) }
    }
}

class CreateChecklistAssignment(
    private val reader: ChecklistAssignmentReader,
    private val writer: ChecklistAssignmentWriter,
) {
    suspend operator fun invoke(org: String, input: AssignmentInput): AssignmentResult {
        val errors = AssignmentInputRules.errors(input)
        if (errors.isNotEmpty()) return AssignmentResult.Failure(AssignmentFailure.INVALID, errors)
        return when (val found = reader.get(org, input.id)) {
            is AssignmentResult.Success ->
                if (found.value.matches(input) && found.value.active) found
                else AssignmentResult.Failure(AssignmentFailure.CONFLICT)
            is AssignmentResult.Failure ->
                if (found.reason == AssignmentFailure.NOT_FOUND) writer.create(org, input)
                else found
        }
    }
}

fun ChecklistAssignment.matches(input: AssignmentInput) =
    id == input.id &&
        templateId == input.templateId &&
        locationId == input.locationId &&
        frequency == input.frequency &&
        dueTime == input.dueTime

class DeactivateChecklistAssignment(private val writer: ChecklistAssignmentWriter) {
    suspend operator fun invoke(org: String, id: String) = writer.deactivate(org, id)
}

data class AssignmentDraft(
    val input: AssignmentInput,
    val pending: Boolean = false,
    val locationName: String = "",
)

interface ChecklistAssignmentDraftRepository {
    suspend fun read(owner: DraftOwner, templateId: String): AssignmentDraft?

    suspend fun write(owner: DraftOwner, templateId: String, value: AssignmentDraft?)
}

class ManageAssignmentDraft(private val repository: ChecklistAssignmentDraftRepository) {
    suspend fun read(owner: DraftOwner, templateId: String) = repository.read(owner, templateId)

    suspend fun write(owner: DraftOwner, templateId: String, value: AssignmentDraft?) =
        repository.write(owner, templateId, value)
}
