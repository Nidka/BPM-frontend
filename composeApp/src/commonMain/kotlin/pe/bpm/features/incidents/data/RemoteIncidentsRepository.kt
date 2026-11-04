package pe.bpm.features.incidents.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.*
import pe.bpm.core.network.*
import pe.bpm.features.incidents.domain.*

class RemoteIncidentsRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : IncidentsRepository, IncidentTransport {
    override fun prepare(form: IncidentForm, site: String, key: String) =
        prepareIncident(form, site, key)

    private fun path(org: String, resource: String): String {
        require(org.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return "${config.apiBaseUrl}/api/v1/organizations/$org/$resource"
    }

    private suspend inline fun <reified T> get(
        org: String,
        resource: String,
        page: PageRequest? = null,
        site: String? = null,
        status: IncidentStatus? = null,
    ): T {
        val access =
            token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(IncidentsFailure.SESSION)
        val response =
            client.get(path(org, resource)) {
                bearerAuth(access)
                page?.let {
                    require(it.size in 1..100 && it.query.length <= 200)
                    validatePageToken(it.token)
                    pageParameters(it)
                }
                site?.let {
                    incidentId(it)
                    parameter("locationId", it)
                }
                status?.let { parameter("status", it.name) }
            }
        checkResponse(response, access)
        return response.body()
    }

    private suspend fun checkResponse(response: HttpResponse, access: String) {
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (response.status.isSuccess()) return
        if (response.status.value == 401) onSessionRejected(access)
        val reason =
            when (response.status.value) {
                400 -> IncidentsFailure.INVALID_PAGE
                401 -> IncidentsFailure.SESSION
                403 -> IncidentsFailure.DENIED
                404 -> IncidentsFailure.NOT_FOUND
                409 -> IncidentsFailure.CONFLICT
                422 -> IncidentsFailure.INVALID
                429 -> IncidentsFailure.RATE_LIMITED
                else -> IncidentsFailure.UNAVAILABLE
            }
        throw TransportFailure(reason)
    }

    override suspend fun authorize(org: String, site: String, role: String): IncidentsResult<Unit> =
        guarded {
            require(IncidentsAccess.canRead(role))
            incidentId(site)
            val workspace =
                get<JsonObject>(org, "workspace")["data"]?.jsonObject ?: error("workspace")
            require(workspace["organization"]?.jsonObject?.get("id")?.jsonPrimitive?.content == org)
            if (workspace["role"]?.jsonPrimitive?.content != role)
                throw TransportFailure(IncidentsFailure.DENIED)
            val proof = get<ListEnvelope<SummaryDto>>(org, "incidents", PageRequest(size = 1), site)
            require(proof.data.size <= 1 && proof.data.all { it.domain().locationId == site })
        }

    override suspend fun catalog(
        org: String,
        page: PageRequest,
    ): IncidentsResult<Page<IncidentCatalog>> = guarded {
        val dto = get<ListEnvelope<CatalogDto>>(org, "incident-catalog", page)
        checkedPage(
            dto.data
                .map { it.domain() }
                .also { require(it.distinctBy { x -> x.code }.size == it.size) },
            dto.pagination,
            page,
        )
    }

    override suspend fun incidents(
        org: String,
        site: String,
        status: IncidentStatus?,
        page: PageRequest,
    ): IncidentsResult<Page<IncidentSummary>> = guarded {
        val dto = get<ListEnvelope<SummaryDto>>(org, "incidents", page, site, status)
        checkedPage(
            dto.data.map {
                it.domain().also { x ->
                    require(x.locationId == site && (status == null || x.status == status))
                }
            },
            dto.pagination,
            page,
        )
    }

    override suspend fun detail(org: String, id: String): IncidentsResult<IncidentDetail> =
        guarded {
            incidentId(id)
            get<Envelope<DetailDto>>(org, "incidents/$id").data.domain().also {
                require(it.summary.id == id)
            }
        }

    override suspend fun assignees(
        org: String,
        id: String,
        page: PageRequest,
    ): IncidentsResult<Page<IncidentAssignee>> = guarded {
        incidentId(id)
        val dto = get<ListEnvelope<AssigneeDto>>(org, "incidents/$id/assignees", page)
        checkedPage(dto.data.map { it.domain() }, dto.pagination, page)
    }

    override suspend fun history(
        org: String,
        id: String,
        page: PageRequest,
    ): IncidentsResult<Page<IncidentEvent>> = guarded {
        incidentId(id)
        val dto = get<ListEnvelope<EventDto>>(org, "incidents/$id/history", page)
        checkedPage(dto.data.map { it.domain() }, dto.pagination, page)
    }

    override suspend fun submit(
        org: String,
        site: String,
        form: IncidentForm,
        pending: PendingIncident,
    ): IncidentsResult<IncidentSaved> = guarded {
        require(pending == prepareIncident(form, site, pending.key))
        incidentId(form.id)
        incidentId(site)
        form.incidentId?.let(::incidentId)
        form.catalogId?.let(::incidentId)
        val access =
            token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(IncidentsFailure.SESSION)
        val response =
            client.post(path(org, pending.path)) {
                bearerAuth(access)
                header("Idempotency-Key", pending.key)
                contentType(ContentType.Application.Json)
                setBody(pending.body)
            }
        checkResponse(response, access)
        if (form.kind == "CATALOG") {
            val result = response.body<Envelope<CatalogDto>>().data.domain()
            val v = form.values
            require(
                result.id == form.id && result.versionNumber == (form.expectedVersion?.plus(1) ?: 1)
            )
            require(
                result.name == v["name"].orEmpty().trim() &&
                    result.area == v["area"].orEmpty().trim() &&
                    result.normativeCriterion == v["normativeCriterion"].orEmpty().trim()
            )
            require(
                result.riskLevel.name == v["riskLevel"] &&
                    result.evidenceRequired == (v["evidenceRequired"] == "true")
            )
            require(result.code == v["code"].orEmpty().trim())
            IncidentSaved.Catalog(result)
        } else {
            val result = response.body<Envelope<DetailDto>>().data.domain()
            require(result.summary.locationId == site)
            if (form.kind == "REPORT") {
                require(
                    result.summary.id == form.id &&
                        result.summary.catalogId == form.values["catalogId"] &&
                        result.summary.description == form.values["description"].orEmpty().trim() &&
                        result.summary.status == IncidentStatus.OPEN &&
                        result.summary.rowVersion == 1L &&
                        result.actions.isEmpty()
                )
            } else {
                require(
                    result.summary.id == form.incidentId &&
                        result.summary.rowVersion == form.rowVersion + 1
                )
                require(result.actions.size == 1)
                val action = result.actions.single()
                val expected =
                    when (IncidentActionType.valueOf(form.kind)) {
                        IncidentActionType.ASSIGN -> IncidentStatus.ASSIGNED
                        IncidentActionType.START,
                        IncidentActionType.RETURN -> IncidentStatus.IN_PROGRESS
                        IncidentActionType.RESOLVE -> IncidentStatus.RESOLVED
                        IncidentActionType.VERIFY -> IncidentStatus.CLOSED
                    }
                require(result.summary.status == expected)
                if (form.kind == "ASSIGN")
                    require(
                        action.id == form.values["actionId"] &&
                            action.responsibleUserId == form.values["responsibleUserId"] &&
                            action.description == form.values["description"].orEmpty().trim() &&
                            sameIncidentInstant(action.dueAt, form.values.getValue("dueAt"))
                    )
                if (form.kind == "RESOLVE")
                    require(action.resolutionNote == form.values["note"].orEmpty().trim())
            }
            IncidentSaved.Detail(result)
        }
    }
}

private class TransportFailure(val reason: IncidentsFailure) : Exception()

private suspend inline fun <T> guarded(block: () -> T): IncidentsResult<T> =
    try {
        val result = block()
        currentCoroutineContext().ensureActive()
        IncidentsResult.Success(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransportFailure) {
        IncidentsResult.Failure(e.reason)
    } catch (e: SessionAccessException) {
        IncidentsResult.Failure(
            if (e.expired) IncidentsFailure.SESSION else IncidentsFailure.CONNECTION
        )
    } catch (_: SerializationException) {
        IncidentsResult.Failure(IncidentsFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) {
        IncidentsResult.Failure(IncidentsFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        IncidentsResult.Failure(IncidentsFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        IncidentsResult.Failure(IncidentsFailure.CONNECTION)
    }

@Serializable private data class Envelope<T>(val data: T)

@Serializable private data class ListEnvelope<T>(val data: List<T>, val pagination: PageMetadata)
