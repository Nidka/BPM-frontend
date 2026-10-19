package pe.bpm.features.checklists.domain

data class ChecklistRun(
    val id: String, val organizationId: String, val locationId: String, val templateVersionId: String,
    val performedBy: String, val status: String, val rowVersion: Int, val items: List<ChecklistItem>,
    val answers: List<ChecklistAnswer>, val compliancePercent: Double? = null,
)
data class ChecklistAnswer(val itemId: String, val complianceResult: String = "", val textValue: String = "",
    val numericValue: Double? = null, val observation: String = "")
data class StartChecklistInput(val id: String, val locationId: String, val templateVersionId: String,
    val clientOperationId: String, val clientCreatedAt: String)
enum class RunFailure { SESSION, DENIED, NOT_FOUND, INVALID, CONFLICT, RATE_LIMITED, CONNECTION, UNAVAILABLE, INVALID_RESPONSE }
sealed interface RunResult {
    data class Success(val run: ChecklistRun) : RunResult
    data class Failure(val reason: RunFailure) : RunResult
}
interface ChecklistRunReader { suspend fun read(organizationId: String, id: String): RunResult }
interface ChecklistRunWriter {
    suspend fun start(organizationId: String, input: StartChecklistInput): RunResult
    suspend fun save(organizationId: String, id: String, version: Int, key: String, answers: List<ChecklistAnswer>): RunResult
    suspend fun submit(organizationId: String, id: String, version: Int, key: String): RunResult
}
class LoadChecklistRun(private val reader: ChecklistRunReader) {
    suspend operator fun invoke(organizationId: String, id: String) = reader.read(organizationId, id)
}
class StartChecklistRun(private val writer: ChecklistRunWriter) {
    suspend operator fun invoke(organizationId: String, input: StartChecklistInput) = writer.start(organizationId, input)
}
class SaveChecklistRun(private val writer: ChecklistRunWriter) {
    suspend operator fun invoke(run: ChecklistRun, key: String, answers: List<ChecklistAnswer>): RunResult {
        if (run.status != "DRAFT" || !validRunAnswers(run.items, answers, complete = false)) return RunResult.Failure(RunFailure.INVALID)
        return writer.save(run.organizationId, run.id, run.rowVersion, key, answers)
    }
}
class SubmitChecklistRun(private val writer: ChecklistRunWriter) {
    suspend operator fun invoke(run: ChecklistRun, key: String): RunResult {
        if (run.status != "DRAFT" || !validRunAnswers(run.items, run.answers, complete = true)) return RunResult.Failure(RunFailure.INVALID)
        return writer.submit(run.organizationId, run.id, run.rowVersion, key)
    }
}

/** Valida estructura; el API conserva permisos, concurrencia y cálculo definitivo. */
fun validRunAnswers(items: List<ChecklistItem>, answers: List<ChecklistAnswer>, complete: Boolean): Boolean {
    if (answers.size > 100 || answers.map { it.itemId }.distinct().size != answers.size) return false
    var scored = 0
    for (answer in answers) {
        val item = items.find { it.id == answer.itemId } ?: return false
        if (answer.textValue.length > 2000 || answer.observation.length > 2000 ||
            (answer.textValue + answer.observation).any { it.isISOControl() }) return false
        val numeric = answer.numericValue
        when (item.responseType) {
            ChecklistResponseType.COMPLIANCE -> {
                if (answer.complianceResult !in setOf("COMPLIANT", "NON_COMPLIANT", "NOT_APPLICABLE") || numeric != null || answer.textValue.isNotEmpty()) return false
                if (answer.complianceResult != "NOT_APPLICABLE") scored++
            }
            ChecklistResponseType.TEXT -> if (answer.textValue.isBlank() || numeric != null || answer.complianceResult.isNotEmpty()) return false
            else -> {
                if (numeric == null || item.minimumValue == null || item.maximumValue == null || !numeric.isFinite() || kotlin.math.abs(numeric) >= 100000000 ||
                    kotlin.math.abs(numeric * 100 - kotlin.math.round(numeric * 100)) >= 0.000001 ||
                    answer.textValue.isNotEmpty() || answer.complianceResult.isNotEmpty()) return false
                scored++
            }
        }
        if (complete && item.evidenceRequiredOnFailure && (answer.complianceResult == "NON_COMPLIANT" ||
                numeric != null && (numeric < item.minimumValue!! || numeric > item.maximumValue!!))) return false
    }
    return !complete || scored > 0 && items.filter { it.required }.all { item -> answers.any { it.itemId == item.id } }
}
