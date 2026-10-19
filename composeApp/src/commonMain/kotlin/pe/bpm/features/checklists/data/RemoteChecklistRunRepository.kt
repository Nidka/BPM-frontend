package pe.bpm.features.checklists.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.checklists.domain.*

/** Transporte autorizado por sesión; no deduce éxitos cuando se pierde una respuesta. */
class RemoteChecklistRunRepository(private val client: HttpClient, private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {}, private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?) : ChecklistRunReader, ChecklistRunWriter {
    override suspend fun read(organizationId: String, id: String) = request(organizationId, id, HttpMethod.Get)
    override suspend fun start(organizationId: String, input: StartChecklistInput) = request(organizationId, "", HttpMethod.Post,
        body = StartRunDto(input.id, input.locationId, input.templateVersionId, input.clientOperationId, input.clientCreatedAt))
    override suspend fun save(organizationId: String, id: String, version: Int, key: String, answers: List<ChecklistAnswer>) =
        request(organizationId, "$id/answers", HttpMethod.Put, key, SaveRunDto(version, answers.map { AnswerDto(it.itemId, it.complianceResult, it.textValue, it.numericValue, it.observation) }))
    override suspend fun submit(organizationId: String, id: String, version: Int, key: String) =
        request(organizationId, "$id/submit", HttpMethod.Post, key, SubmitRunDto(version))

    private suspend fun request(org: String, path: String, verb: HttpMethod, key: String? = null, body: Any? = null): RunResult {
        if (!org.matches(Regex("[A-Za-z0-9_-]{1,64}")) || !path.matches(Regex("(?:[A-Za-z0-9_-]{1,64}(?:/(?:answers|submit))?)?")))
            return RunResult.Failure(RunFailure.NOT_FOUND)
        return try {
            val access = token()?.takeIf { it.isNotBlank() } ?: return RunResult.Failure(RunFailure.SESSION)
            val response = client.request("${config.apiBaseUrl}/api/v1/organizations/$org/checklist-runs" + if (path.isEmpty()) "" else "/$path") {
                method = verb; bearerAuth(access)
                if (key != null) header("Idempotency-Key", key)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    when (body) {
                        is StartRunDto -> setBody(body)
                        is SaveRunDto -> setBody(body)
                        is SubmitRunDto -> setBody(body)
                    }
                }
            }
            onSessionResponse(response)
            if (response.status.isSuccess()) {
                val run = response.body<RunEnvelopeDto>().data.domain()
                require(run.organizationId == org)
                val requestedId = if (body is StartRunDto) body.id else path.substringBefore('/')
                require(run.id == requestedId)
                if (body is StartRunDto) require(run.locationId == body.locationId && run.templateVersionId == body.templateVersionId)
                if (body is SaveRunDto) require(run.status == "DRAFT" && run.rowVersion > body.rowVersion &&
                    run.answers.sortedBy { it.itemId } == body.answers.map { it.domain() }.sortedBy { it.itemId })
                if (body is SubmitRunDto) require(run.status == "SUBMITTED" && run.rowVersion > body.rowVersion)
                RunResult.Success(run)
            } else {
                if (response.status.value == 401) onSessionRejected(access)
                RunResult.Failure(when (response.status.value) {
                    400, 422 -> RunFailure.INVALID
                    401 -> RunFailure.SESSION
                    403 -> RunFailure.DENIED
                    404 -> RunFailure.NOT_FOUND
                    409 -> RunFailure.CONFLICT
                    429 -> RunFailure.RATE_LIMITED
                    else -> RunFailure.UNAVAILABLE
                })
            }
        } catch (e: CancellationException) { throw e
        } catch (e: SessionAccessException) { RunResult.Failure(if (e.expired) RunFailure.SESSION else RunFailure.CONNECTION)
        } catch (_: SerializationException) { RunResult.Failure(RunFailure.INVALID_RESPONSE)
        } catch (_: JsonConvertException) { RunResult.Failure(RunFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) { RunResult.Failure(RunFailure.INVALID_RESPONSE)
        } catch (_: Throwable) { RunResult.Failure(RunFailure.CONNECTION) }
    }
}
@Serializable private data class StartRunDto(val id: String, val locationId: String, val templateVersionId: String, val clientOperationId: String, val clientCreatedAt: String)
@Serializable private data class SaveRunDto(val rowVersion: Int, val answers: List<AnswerDto>)
@Serializable private data class SubmitRunDto(val rowVersion: Int)
@Serializable internal data class AnswerDto(val itemId: String, val complianceResult: String = "", val textValue: String = "", val numericValue: Double? = null, val observation: String = "") {
    fun domain() = ChecklistAnswer(itemId, complianceResult, textValue, numericValue, observation)
}
@Serializable private data class RunEnvelopeDto(val data: RunDto)
@Serializable internal data class RunDto(val id: String, val organizationId: String, val locationId: String, val templateVersionId: String,
    val performedBy: String, val status: String, val rowVersion: Int, val items: List<ItemDto>, val answers: List<AnswerDto>, val compliancePercent: Double? = null) {
    fun domain(): ChecklistRun {
        require(listOf(id, organizationId, locationId, templateVersionId, performedBy).all { it.isNotBlank() })
        require(status in setOf("DRAFT", "SUBMITTED") && rowVersion > 0 && items.size in 1..100)
        require(items.map { it.id }.distinct().size == items.size && items.map { it.displayOrder }.distinct().size == items.size)
        val questions = items.sortedBy { it.displayOrder }.map { it.domain() }
        val values = answers.map { it.domain() }
        require(validRunAnswers(questions, values, complete = status == "SUBMITTED"))
        require(compliancePercent == null || compliancePercent.isFinite() && compliancePercent in 0.0..100.0)
        require(status != "SUBMITTED" || compliancePercent != null)
        return ChecklistRun(id, organizationId, locationId, templateVersionId, performedBy, status, rowVersion, questions, values, compliancePercent)
    }
}

internal fun ChecklistAnswer.dto() = AnswerDto(itemId, complianceResult, textValue, numericValue, observation)
internal fun ChecklistRun.dto() = RunDto(id, organizationId, locationId, templateVersionId, performedBy, status, rowVersion,
    items.map { it.dto() }, answers.map { it.dto() }, compliancePercent)
