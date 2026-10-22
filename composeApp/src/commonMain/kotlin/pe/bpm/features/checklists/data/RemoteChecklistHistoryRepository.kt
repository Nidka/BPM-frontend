package pe.bpm.features.checklists.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.*
import pe.bpm.features.checklists.domain.*
import kotlin.time.Instant

class RemoteChecklistHistoryRepository(private val client: HttpClient, private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {}, private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?) : ChecklistHistoryReader {
    override suspend fun history(organizationId: String, request: PageRequest, scope: HistoryScope, status: String): ChecklistHistoryResult {
        return try {
            require(organizationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            require(status in setOf("", "DRAFT", "SUBMITTED"))
            val access = token()?.takeIf { it.isNotBlank() } ?: return ChecklistHistoryResult.Failure(ChecklistFailure.SESSION_REQUIRED)
            val response = client.get("${config.apiBaseUrl}/api/v1/organizations/$organizationId/checklist-runs") {
                bearerAuth(access); pageParameters(request); parameter("scope", scope.parameter)
                if (status.isNotEmpty()) parameter("status", status)
            }
            onSessionResponse(response)
            if (!response.status.isSuccess()) {
                if (response.status.value == 401) onSessionRejected(access)
                return ChecklistHistoryResult.Failure(when (response.status.value) {
                    400 -> ChecklistFailure.INVALID_PAGE; 401 -> ChecklistFailure.SESSION_REQUIRED; 403 -> ChecklistFailure.ACCESS_DENIED
                    429 -> ChecklistFailure.RATE_LIMITED; else -> ChecklistFailure.UNAVAILABLE
                })
            }
            val value = response.body<HistoryEnvelope>()
            val page = checkedPage(value.data.map { it.domain() }, value.pagination, request)
            require(page.items.map { it.id }.distinct().size == page.items.size)
            ChecklistHistoryResult.Success(page.items, page.nextToken)
        } catch (e: CancellationException) { throw e
        } catch (e: SessionAccessException) { ChecklistHistoryResult.Failure(if (e.expired) ChecklistFailure.SESSION_REQUIRED else ChecklistFailure.CONNECTION)
        } catch (_: kotlinx.serialization.SerializationException) { ChecklistHistoryResult.Failure(ChecklistFailure.INVALID_RESPONSE)
        } catch (_: io.ktor.serialization.JsonConvertException) { ChecklistHistoryResult.Failure(ChecklistFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) { ChecklistHistoryResult.Failure(ChecklistFailure.INVALID_RESPONSE)
        } catch (_: Throwable) { ChecklistHistoryResult.Failure(ChecklistFailure.CONNECTION) }
    }
}
@Serializable private data class HistoryEnvelope(val data: List<SummaryDto>, val pagination: PageMetadata)
@Serializable private data class SummaryDto(val id: String, val templateName: String, val locationId: String, val locationName: String,
    val performedBy: String, val performedByName: String, val status: String, val createdAt: String,
    val submittedAt: String? = null, val compliancePercent: Double? = null, val answerCount: Int, val questionCount: Int) {
    fun domain(): ChecklistRunSummary {
        require(listOf(id, templateName, locationId, locationName, performedBy, performedByName).all { it.isNotBlank() })
        require(status in setOf("DRAFT", "SUBMITTED") && questionCount in 1..100 && answerCount in 0..questionCount)
        Instant.parse(createdAt); submittedAt?.let(Instant::parse)
        require(compliancePercent == null || compliancePercent.isFinite() && compliancePercent in 0.0..100.0)
        require(status != "SUBMITTED" || compliancePercent != null && submittedAt != null)
        return ChecklistRunSummary(id, templateName, locationId, locationName, performedBy, performedByName, status, createdAt,
            submittedAt, compliancePercent, answerCount, questionCount)
    }
}
