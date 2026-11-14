package pe.bpm.features.research.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.research.domain.*

/**
 * Adaptador Ktor del estudio CAP. Implementa los cuatro puertos del dominio sobre el mismo
 * transporte autenticado. Los errores del servidor se reducen a [ResearchFailure]; nunca se
 * propagan mensajes, cuerpos de respuesta ni respuestas de otros participantes.
 */
class RemoteResearchRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : ParticipationRepository, ResearchAccessRepository, ResearchCatalogRepository, ResearchAnalysisRepository {

    // ---- Participante ------------------------------------------------------------------------

    override suspend fun participation(org: String): ResearchResult<Participation> = guarded {
        participationOf(api(participantPath(org, "participation")).asObj().data())
    }

    override suspend fun recordConsent(org: String, documentVersion: String, accepted: Boolean): ResearchResult<Participation> = guarded {
        require(ResearchLimits.versionPattern.matches(documentVersion))
        participationOf(api(participantPath(org, "consent"), HttpMethod.Post, buildJsonObject {
            put("documentVersion", documentVersion); put("accepted", accepted)
        }).asObj().data())
    }

    override suspend fun submit(org: String, submission: SurveySubmission): ResearchResult<SubmissionReceipt> = guarded {
        require(submission.answers.isNotEmpty() && submission.answers.size <= ResearchLimits.MAX_QUESTIONS)
        val body = buildJsonObject {
            put("id", researchId(submission.id))
            put("clientOperationId", researchId(submission.clientOperationId))
            put("instrumentVersionId", researchId(submission.instrumentVersionId))
            put("moment", submission.moment.name)
            put("clientCreatedAt", submission.clientCreatedAt)
            putJsonArray("answers") {
                submission.answers.forEach { add(buildJsonObject { put("questionId", researchId(it.questionId)); put("value", it.value) }) }
            }
        }
        receiptOf(api(participantPath(org, "submissions"), HttpMethod.Post, body).asObj().data(), submission)
    }

    // ---- Acceso ------------------------------------------------------------------------------

    override suspend fun access(): ResearchResult<ResearchAccess> = guarded { accessOf(api(researchPath("access")).asObj().data()) }

    override suspend fun grants(): ResearchResult<List<AccessGrant>> = guarded { rows(researchPath("access-grants")).map(::grantOf) }

    override suspend fun grant(email: String): ResearchResult<AccessGrant> = guarded {
        val grant = grantOf(api(researchPath("access-grants"), HttpMethod.Post, buildJsonObject { put("email", email) }).asObj().data())
        require(grant.email.equals(email, ignoreCase = true))
        grant
    }

    override suspend fun revoke(userId: String): ResearchResult<Unit> = guarded {
        api(researchPath("access-grants/${researchId(userId)}"), HttpMethod.Delete)
        Unit
    }

    // ---- Consentimiento e instrumentos -------------------------------------------------------

    override suspend fun consentDocuments(): ResearchResult<List<ConsentDocumentItem>> = guarded { rows(researchPath("consent-documents")).map(::consentItemOf) }

    override suspend fun publishConsent(document: NewConsentDocument): ResearchResult<ConsentDocumentItem> = guarded {
        val created = consentItemOf(api(researchPath("consent-documents"), HttpMethod.Post, buildJsonObject {
            put("version", document.version); put("title", document.title); put("text", document.text)
            put("responsibleInvestigator", document.responsibleInvestigator)
        }).asObj().data())
        require(created.version == document.version)
        created
    }

    override suspend fun instruments(): ResearchResult<List<InstrumentSummary>> = guarded { rows(researchPath("instruments")).map(::instrumentOf) }

    override suspend fun createInstrument(instrument: NewInstrument, imported: Boolean): ResearchResult<InstrumentDraft> = guarded {
        val body = buildJsonObject {
            put("code", instrument.code); put("name", instrument.name)
            if (instrument.description.isNotBlank()) put("description", instrument.description)
            put("isCap", instrument.isCap)
            putJsonArray("questions") { instrument.questions.forEach { add(questionBody(it)) } }
        }
        draftOf(api(researchPath(if (imported) "instruments/import" else "instruments"), HttpMethod.Post, body).asObj().data())
    }

    override suspend fun instrument(id: String): ResearchResult<InstrumentDetail> = guarded {
        detailOf(api(researchPath("instruments/${researchId(id)}")).asObj().data()).also { require(it.instrument.id.equals(id, ignoreCase = true)) }
    }

    override suspend fun draft(id: String): ResearchResult<InstrumentDraft> = guarded {
        draftOf(api(researchPath("instruments/${researchId(id)}/draft")).asObj().data()).also { require(it.instrumentId.equals(id, ignoreCase = true)) }
    }

    override suspend fun saveDraft(id: String, rowVersion: String, questions: List<DraftQuestion>): ResearchResult<InstrumentDraft> = guarded {
        val body = buildJsonObject {
            put("rowVersion", rowVersionBody(rowVersion))
            putJsonArray("questions") { questions.forEach { add(questionBody(it)) } }
        }
        draftOf(api(researchPath("instruments/${researchId(id)}/draft"), HttpMethod.Put, body).asObj().data()).also { require(it.instrumentId.equals(id, ignoreCase = true)) }
    }

    override suspend fun newVersion(id: String): ResearchResult<InstrumentDraft> = guarded {
        draftOf(api(researchPath("instruments/${researchId(id)}/versions"), HttpMethod.Post, buildJsonObject {}).asObj().data())
    }

    override suspend fun publish(id: String): ResearchResult<InstrumentVersion> = guarded {
        versionOf(api(researchPath("instruments/${researchId(id)}/publish"), HttpMethod.Post, buildJsonObject {}).asObj().data().obj("version"))
    }

    // ---- Resultados, exportación y necesidades -----------------------------------------------

    override suspend fun organizations(): ResearchResult<List<ResearchOrganization>> = guarded { rows(researchPath("organizations")).map(::organizationOf) }

    override suspend fun results(versionId: String?, request: PageRequest): ResearchResult<ResearchResults> = guarded {
        val root = api(researchPath("results")) {
            versionId?.let { parameter("instrumentVersionId", researchId(it)) }
            pageParameters(request)
        }.asObj()
        val version = root.obj("version")
        val items = root.arr("data").map { resultOf(it.asObj()) }
        require(items.size <= 100 && items.map { it.organizationId.lowercase() }.distinct().size == items.size)
        ResearchResults(ResultsVersion(researchId(version.str("id")), version.int("versionNumber")), Page(items, nextToken(root, request)))
    }

    override suspend fun export(versionId: String?, organizationId: String?): ResearchResult<CsvExport> = guarded {
        val access = token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(ResearchFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/research/exports/responses.csv") {
            method = HttpMethod.Get
            bearerAuth(access)
            accept(ContentType.Text.CSV)
            versionId?.let { parameter("instrumentVersionId", researchId(it)) }
            organizationId?.let { parameter("organizationId", researchId(it)) }
            timeout { requestTimeoutMillis = 90_000; socketTimeoutMillis = 60_000 }
        }
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) throw TransportFailure(failureOf(response, access))
        require(response.contentType()?.match(ContentType.Text.CSV) == true)
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > ResearchExportLimits.MAX_BYTES) throw TransportFailure(ResearchFailure.TOO_LARGE)
        val bytes: ByteArray = response.body()
        if (bytes.size > ResearchExportLimits.MAX_BYTES) throw TransportFailure(ResearchFailure.TOO_LARGE)
        CsvExport(fileNameOf(response.headers[HttpHeaders.ContentDisposition]), bytes)
    }

    override suspend fun needs(organizationId: String?, request: PageRequest): ResearchResult<Page<DetectedNeed>> = guarded {
        val root = api(researchPath("needs")) {
            organizationId?.let { parameter("organizationId", researchId(it)) }
            pageParameters(request)
        }.asObj()
        val items = root.arr("data").map { needOf(it.asObj()) }
        require(items.size <= 100)
        Page(items, nextToken(root, request))
    }

    override suspend fun createNeed(need: NewNeed): ResearchResult<DetectedNeed> = guarded {
        val created = needOf(api(researchPath("needs"), HttpMethod.Post, buildJsonObject {
            put("organizationId", researchId(need.organizationId)); put("category", need.category.name); put("description", need.description)
            need.detectedAt?.let { put("detectedAt", it) }
        }).asObj().data())
        require(created.organizationId.equals(need.organizationId, ignoreCase = true) && created.category == need.category)
        created
    }

    // ---- Transporte --------------------------------------------------------------------------

    private fun participantPath(org: String, resource: String): String {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(org))
        return "organizations/$org/research/$resource"
    }

    private fun researchPath(resource: String) = "research/$resource"

    private fun JsonObject.data(): JsonObject = obj("data")

    private fun nextToken(root: JsonObject, request: PageRequest): String {
        val token = root.objOrNull("pagination")?.strOrNull("nextPageToken").orEmpty()
        require(token.length <= 2048 && token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' })
        require(token.isEmpty() || token != request.token)
        return token
    }

    /** Recorre todas las páginas de un listado acotado (selectores y catálogos pequeños). */
    private suspend fun rows(path: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var token = ""
        repeat(MAX_PAGES) {
            val root = api(path) { parameter("pageSize", 100); if (token.isNotEmpty()) parameter("pageToken", token) }.asObj()
            all += root.arr("data").map { it.asObj() }
            val next = nextToken(root, PageRequest(token))
            if (next.isEmpty()) return all
            token = next
        }
        throw IllegalStateException("Listado demasiado largo")
    }

    private fun HttpRequestBuilder.pageParameters(request: PageRequest) {
        require(request.size in 1..100)
        parameter("pageSize", request.size)
        if (request.token.isNotEmpty()) {
            require(request.token.length <= 2048 && request.token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' })
            parameter("pageToken", request.token)
        }
    }

    private suspend fun api(resource: String, verb: HttpMethod = HttpMethod.Get, body: JsonElement? = null, configure: HttpRequestBuilder.() -> Unit = {}): JsonElement {
        val access = token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(ResearchFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/$resource") {
            method = verb
            bearerAuth(access)
            accept(ContentType.Application.Json)
            configure()
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) throw TransportFailure(failureOf(response, access))
        if (response.status == HttpStatusCode.NoContent) return JsonNull
        return response.body()
    }

    private suspend fun failureOf(response: HttpResponse, access: String): ResearchFailure {
        if (response.status.value == 401) onSessionRejected(access)
        val code = if (response.status.value in setOf(403, 404, 409, 422)) {
            try { response.body<ResearchErrorsEnvelope>().errors.firstOrNull()?.code.orEmpty() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { "" }
        } else ""
        return failureFor(response.status.value, code)
    }

    private companion object { const val MAX_PAGES = 10 }
}

/** Traduce estado HTTP y código de dominio a un motivo público; el mensaje del servidor se descarta. */
internal fun failureFor(status: Int, code: String): ResearchFailure = when (status) {
    400 -> ResearchFailure.INVALID
    401 -> ResearchFailure.SESSION
    403 -> when (code) {
        "RESEARCH_ACCESS_DENIED" -> ResearchFailure.RESEARCH_DENIED
        "RESEARCH_CONSENT_REQUIRED" -> ResearchFailure.CONSENT_REQUIRED
        else -> ResearchFailure.DENIED
    }
    404 -> when (code) {
        "RESEARCH_SURVEY_UNAVAILABLE" -> ResearchFailure.SURVEY_UNAVAILABLE
        "RESEARCH_USER_NOT_FOUND" -> ResearchFailure.USER_NOT_FOUND
        "RESEARCH_DRAFT_NOT_FOUND" -> ResearchFailure.DRAFT_MISSING
        else -> ResearchFailure.NOT_FOUND
    }
    409 -> when (code) {
        "RESEARCH_CONSENT_DOCUMENT_UNAVAILABLE" -> ResearchFailure.CONSENT_UNAVAILABLE
        "RESEARCH_CONSENT_VERSION_OUTDATED" -> ResearchFailure.CONSENT_OUTDATED
        "RESEARCH_VERSION_OUTDATED" -> ResearchFailure.VERSION_OUTDATED
        "RESEARCH_SUBMISSION_EXISTS" -> ResearchFailure.ALREADY_SUBMITTED
        "RESEARCH_CONSENT_VERSION_TAKEN" -> ResearchFailure.VERSION_TAKEN
        "RESEARCH_INSTRUMENT_CODE_TAKEN" -> ResearchFailure.CODE_TAKEN
        "RESEARCH_CAP_EXISTS" -> ResearchFailure.CAP_EXISTS
        "RESEARCH_DRAFT_CONFLICT" -> ResearchFailure.DRAFT_CONFLICT
        "RESEARCH_DRAFT_EXISTS" -> ResearchFailure.DRAFT_EXISTS
        else -> ResearchFailure.CONFLICT
    }
    413 -> ResearchFailure.TOO_LARGE
    422 -> when (code) {
        "RESEARCH_ANSWERS_INVALID" -> ResearchFailure.ANSWERS_INVALID
        "RESEARCH_INSTRUMENT_INCOMPLETE" -> ResearchFailure.INSTRUMENT_INCOMPLETE
        "RESEARCH_EXPORT_TOO_LARGE" -> ResearchFailure.EXPORT_TOO_LARGE
        else -> ResearchFailure.INVALID
    }
    429 -> ResearchFailure.RATE_LIMITED
    else -> ResearchFailure.UNAVAILABLE
}

/** Nombre de archivo seguro tomado de Content-Disposition; si no es válido se usa uno genérico. */
internal fun fileNameOf(disposition: String?): String {
    val name = disposition?.let { Regex("filename=\"([^\"]{1,80})\"").find(it)?.groupValues?.get(1) }
    return name?.takeIf { Regex("[A-Za-z0-9._-]{1,76}\\.csv").matches(it) } ?: "respuestas-cap.csv"
}

private class TransportFailure(val reason: ResearchFailure) : Exception()

private suspend inline fun <T> guarded(block: () -> T): ResearchResult<T> =
    try {
        val result = block()
        currentCoroutineContext().ensureActive()
        ResearchResult.Success(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TransportFailure) {
        ResearchResult.Failure(error.reason)
    } catch (_: pe.bpm.core.offline.OfflineQueuedException) {
        // La entrega quedó en la Outbox con su UUID estable: éxito local pendiente, no un fallo de conexión.
        ResearchResult.Failure(ResearchFailure.QUEUED)
    } catch (error: SessionAccessException) {
        ResearchResult.Failure(if (error.expired) ResearchFailure.SESSION else ResearchFailure.CONNECTION)
    } catch (_: SerializationException) {
        ResearchResult.Failure(ResearchFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) {
        ResearchResult.Failure(ResearchFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        ResearchResult.Failure(ResearchFailure.INVALID_RESPONSE)
    } catch (_: IllegalStateException) {
        ResearchResult.Failure(ResearchFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        ResearchResult.Failure(ResearchFailure.CONNECTION)
    }
