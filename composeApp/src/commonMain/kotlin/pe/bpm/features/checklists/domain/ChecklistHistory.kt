package pe.bpm.features.checklists.domain

import pe.bpm.core.domain.PageRequest

data class ChecklistRunSummary(val id: String, val templateName: String, val locationId: String, val locationName: String,
    val performedBy: String, val performedByName: String, val status: String, val createdAt: String,
    val submittedAt: String? = null, val compliancePercent: Double? = null, val answerCount: Int, val questionCount: Int)
enum class HistoryScope(val parameter: String) { Mine("mine"), Team("team") }
sealed interface ChecklistHistoryResult {
    data class Success(val items: List<ChecklistRunSummary>, val nextToken: String) : ChecklistHistoryResult
    data class Failure(val reason: ChecklistFailure) : ChecklistHistoryResult
}
interface ChecklistHistoryReader {
    suspend fun history(organizationId: String, request: PageRequest, scope: HistoryScope, status: String): ChecklistHistoryResult
}
class LoadChecklistHistory(private val repository: ChecklistHistoryReader) {
    suspend operator fun invoke(organizationId: String, request: PageRequest, scope: HistoryScope, status: String) = repository.history(organizationId, request, scope, status)
}
