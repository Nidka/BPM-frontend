package pe.bpm.features.checklists.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.*
import pe.bpm.features.checklists.domain.*

class RemoteChecklistAssignmentRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : ChecklistAssignmentReader, ChecklistAssignmentWriter {
    override suspend fun list(organizationId: String, page: PageRequest): AssignmentPageResult =
        try {
            val response = request(organizationId, "", HttpMethod.Get, page = page)
            val result = response.body<AssignmentListDto>()
            val checked =
                checkedPage(
                    result.data.map { it.domain(requirePeriod = true) },
                    result.pagination,
                    page,
                )
            require(checked.items.map { it.id }.distinct().size == checked.items.size)
            AssignmentPageResult.Success(checked.items, checked.nextToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssignmentRequestFailure) {
            AssignmentPageResult.Failure(e.reason)
        } catch (e: SessionAccessException) {
            AssignmentPageResult.Failure(
                if (e.expired) AssignmentFailure.SESSION else AssignmentFailure.CONNECTION
            )
        } catch (_: kotlinx.serialization.SerializationException) {
            AssignmentPageResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: io.ktor.serialization.JsonConvertException) {
            AssignmentPageResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            AssignmentPageResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: Exception) {
            AssignmentPageResult.Failure(AssignmentFailure.CONNECTION)
        }

    override suspend fun get(organizationId: String, id: String) =
        single(organizationId, id, HttpMethod.Get)

    override suspend fun create(organizationId: String, input: AssignmentInput) =
        single(
            organizationId,
            "",
            HttpMethod.Post,
            CreateAssignmentDto(
                input.id,
                input.templateId,
                input.locationId,
                input.frequency?.name.orEmpty(),
                input.dueTime,
            ),
            input,
        )

    override suspend fun deactivate(organizationId: String, id: String) =
        single(organizationId, "$id/deactivate", HttpMethod.Post)

    private suspend fun single(
        org: String,
        path: String,
        method: HttpMethod,
        input: CreateAssignmentDto? = null,
        expected: AssignmentInput? = null,
    ): AssignmentResult =
        try {
            val result =
                request(org, path, method, input)
                    .body<AssignmentEnvelopeDto>()
                    .data
                    .domain(requirePeriod = method == HttpMethod.Get)
            if (expected != null) require(result.matches(expected) && result.active)
            else require(result.id == path.substringBefore('/'))
            if (path.endsWith("/deactivate")) require(!result.active)
            AssignmentResult.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssignmentRequestFailure) {
            AssignmentResult.Failure(e.reason, e.fields)
        } catch (e: SessionAccessException) {
            AssignmentResult.Failure(
                if (e.expired) AssignmentFailure.SESSION else AssignmentFailure.CONNECTION
            )
        } catch (_: kotlinx.serialization.SerializationException) {
            AssignmentResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: io.ktor.serialization.JsonConvertException) {
            AssignmentResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            AssignmentResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: Exception) {
            AssignmentResult.Failure(AssignmentFailure.CONNECTION)
        }

    private suspend fun request(
        org: String,
        path: String,
        method: HttpMethod,
        input: CreateAssignmentDto? = null,
        page: PageRequest? = null,
    ): HttpResponse {
        require(
            org.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
                path.matches(Regex("(?:[A-Za-z0-9_-]{1,64}(?:/deactivate)?)?"))
        )
        val access =
            token()?.takeIf { it.isNotBlank() }
                ?: throw AssignmentRequestFailure(AssignmentFailure.SESSION)
        val response =
            client.request(
                "${config.apiBaseUrl}/api/v1/organizations/$org/checklist-assignments" +
                    if (path.isEmpty()) "" else "/$path"
            ) {
                this.method = method
                bearerAuth(access)
                page?.let { pageParameters(it) }
                if (method == HttpMethod.Post) {
                    contentType(ContentType.Application.Json)
                    if (input != null) setBody(input) else setBody("{}")
                }
            }
        onSessionResponse(response)
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) onSessionRejected(access)
            val reason =
                when (response.status.value) {
                    400 -> AssignmentFailure.INVALID_PAGE
                    401 -> AssignmentFailure.SESSION
                    403 -> AssignmentFailure.DENIED
                    404 -> AssignmentFailure.NOT_FOUND
                    409 -> AssignmentFailure.CONFLICT
                    422 -> AssignmentFailure.INVALID
                    429 -> AssignmentFailure.RATE_LIMITED
                    else -> AssignmentFailure.UNAVAILABLE
                }
            val fields =
                if (response.status.value == 422)
                    response
                        .body<AssignmentErrorsDto>()
                        .errors
                        .flatMap { it.fields.keys }
                        .filter { it in setOf("locationId", "frequency", "dueTime") }
                        .associateWith {
                            when (it) {
                                "locationId" -> "Selecciona una sede disponible."
                                "frequency" -> "Selecciona una frecuencia válida."
                                else -> "Usa una hora como 08:30."
                            }
                        }
                else emptyMap()
            throw AssignmentRequestFailure(reason, fields)
        }
        return response
    }
}

private class AssignmentRequestFailure(
    val reason: AssignmentFailure,
    val fields: Map<String, String> = emptyMap(),
) : Exception()

@Serializable
private data class CreateAssignmentDto(
    val id: String,
    val templateId: String,
    val locationId: String,
    val frequency: String,
    val dueTime: String,
)

@Serializable
private data class AssignmentListDto(val data: List<AssignmentDto>, val pagination: PageMetadata)

@Serializable private data class AssignmentEnvelopeDto(val data: AssignmentDto)

@Serializable private data class AssignmentErrorsDto(val errors: List<AssignmentErrorDto>)

@Serializable private data class AssignmentErrorDto(val fields: Map<String, String> = emptyMap())

@Serializable
private data class AssignedRunDto(
    val id: String,
    val status: String,
    val performedBy: String,
    val performedByName: String,
    val createdAt: String,
    val submittedAt: String? = null,
) {
    fun domain(): AssignedChecklistRun {
        require(
            id.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
                performedBy.isNotBlank() &&
                performedBy.length <= 128 &&
                performedByName.length <= 200 &&
                status in setOf("DRAFT", "SUBMITTED")
        )
        Instant.parse(createdAt)
        submittedAt?.let(Instant::parse)
        require(status != "SUBMITTED" || submittedAt != null)
        return AssignedChecklistRun(
            id,
            status,
            performedBy,
            performedByName,
            createdAt,
            submittedAt,
        )
    }
}

@Serializable
private data class AssignmentDto(
    val id: String,
    val templateId: String,
    val templateName: String,
    val templateVersionId: String,
    val locationId: String,
    val locationName: String,
    val frequency: String,
    val dueTime: String,
    val timezone: String,
    val active: Boolean,
    val createdAt: String,
    val periodStartsAt: String? = null,
    val periodEndsAt: String? = null,
    val run: AssignedRunDto? = null,
) {
    fun domain(requirePeriod: Boolean): ChecklistAssignment {
        require(
            listOf(id, templateId, templateVersionId, locationId).all {
                it.matches(Regex("[A-Za-z0-9_-]{1,64}"))
            }
        )
        require(
            templateName.isNotBlank() &&
                templateName.length <= 180 &&
                locationName.isNotBlank() &&
                locationName.length <= 160 &&
                timezone.isNotBlank() &&
                timezone.length <= 100
        )
        require(AssignmentInputRules.time.error(dueTime) == null)
        Instant.parse(createdAt)
        periodStartsAt?.let(Instant::parse)
        periodEndsAt?.let(Instant::parse)
        require(!requirePeriod || periodStartsAt != null)
        require(
            periodEndsAt == null ||
                periodStartsAt != null &&
                    Instant.parse(periodEndsAt) > Instant.parse(periodStartsAt)
        )
        return ChecklistAssignment(
            id,
            templateId,
            templateName,
            templateVersionId,
            locationId,
            locationName,
            ChecklistFrequency.valueOf(frequency),
            dueTime,
            timezone,
            active,
            createdAt,
            periodStartsAt,
            periodEndsAt,
            run?.domain(),
        )
    }
}
