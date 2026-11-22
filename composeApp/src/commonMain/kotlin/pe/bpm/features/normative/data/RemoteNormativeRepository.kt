package pe.bpm.features.normative.data

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.normative.domain.*

@Serializable internal data class Envelope<T>(val data: T)
@Serializable internal data class ErrorItem(val code: String = "", val fields: Map<String, String> = emptyMap())
@Serializable internal data class ErrorsEnvelope(val errors: List<ErrorItem> = emptyList())
@Serializable internal data class StatusDto(val documents: Int)
@Serializable internal data class MatchDto(val documentId: String, val documentTitle: String, val documentSource: String = "", val text: String)
@Serializable internal data class DocumentDto(
    val id: String, val title: String, val source: String = "", val status: String, val characters: Int,
    val createdAt: String = "", val updatedAt: String, val content: String = "",
) {
    fun domain(): NormativeDocument {
        require(Regex("[0-9a-f-]{36}").matches(id) && status in setOf("ACTIVE", "ARCHIVED") && title.length <= NormativeRules.TITLE_MAX)
        return NormativeDocument(id, title, source, status, characters, updatedAt, content)
    }
}

/** Adaptador Ktor del buscador de normativa. No registra búsquedas ni tokens. */
class RemoteNormativeRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : NormativeRepository {

    override suspend fun status(): NormativeResult<NormativeStatus> = guarded {
        val dto = request("status").body<Envelope<StatusDto>>().data
        require(dto.documents >= 0)
        NormativeStatus(dto.documents)
    }.let { result ->
        // Un servidor anterior a V17 no tiene la ruta: el buscador aún no está disponible.
        if (result is NormativeResult.Failure && result.reason == NormativeFailure.NOT_FOUND) NormativeResult.Success(NormativeStatus(0, available = false)) else result
    }

    override suspend fun search(query: String): NormativeResult<List<NormativeMatch>> = guarded {
        val items = request("search", query = listOf("q" to query.trim())).body<Envelope<List<MatchDto>>>().data
        require(items.size <= 50)
        items.map { NormativeMatch(uuid(it.documentId), it.documentTitle, it.documentSource, it.text) }
    }

    override suspend fun documents(archived: Boolean): NormativeResult<List<NormativeDocument>> = guarded {
        request("documents", query = listOf("status" to if (archived) "ARCHIVED" else "ACTIVE")).body<Envelope<List<DocumentDto>>>().data.map { it.domain() }
    }

    override suspend fun document(id: String): NormativeResult<NormativeDocument> = guarded {
        request("documents/${uuid(id)}").body<Envelope<DocumentDto>>().data.domain()
    }

    override suspend fun create(input: NormativeDocumentInput): NormativeResult<NormativeDocument> = guarded {
        request("documents", HttpMethod.Post, input.json()).body<Envelope<DocumentDto>>().data.domain()
    }

    override suspend fun update(id: String, input: NormativeDocumentInput): NormativeResult<NormativeDocument> = guarded {
        request("documents/${uuid(id)}", HttpMethod.Put, input.json()).body<Envelope<DocumentDto>>().data.domain()
    }

    override suspend fun setArchived(id: String, archived: Boolean): NormativeResult<NormativeDocument> = guarded {
        request("documents/${uuid(id)}/${if (archived) "archive" else "restore"}", HttpMethod.Post, buildJsonObject { })
            .body<Envelope<DocumentDto>>().data.domain()
    }

    private fun NormativeDocumentInput.json() = buildJsonObject { put("title", title.trim()); put("source", source.trim()); put("content", content.trim()) }

    private fun uuid(value: String): String { require(Regex("[0-9a-f-]{36}").matches(value)); return value }

    private suspend fun request(
        resource: String, verb: HttpMethod = HttpMethod.Get, body: JsonObject? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): HttpResponse {
        val access = token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(NormativeFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/normative/$resource") {
            method = verb
            bearerAuth(access)
            query.forEach { (name, value) -> parameter(name, value) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) onSessionRejected(access)
            val error = if (response.status.value in setOf(404, 422))
                try { response.body<ErrorsEnvelope>().errors.firstOrNull() } catch (c: CancellationException) { throw c } catch (_: Exception) { null } else null
            throw TransportFailure(failureFor(response.status.value), error?.fields.orEmpty().filterKeys { it in setOf("title", "source", "content", "q") })
        }
        return response
    }
}

/** Traduce estado HTTP a un motivo público. */
internal fun failureFor(status: Int): NormativeFailure = when (status) {
    401 -> NormativeFailure.SESSION
    403 -> NormativeFailure.DENIED
    404 -> NormativeFailure.NOT_FOUND
    400, 422 -> NormativeFailure.INVALID
    else -> NormativeFailure.UNAVAILABLE
}

private class TransportFailure(val reason: NormativeFailure, val fields: Map<String, String> = emptyMap()) : Exception()

private suspend inline fun <T> guarded(block: () -> T): NormativeResult<T> =
    try {
        val result = block()
        currentCoroutineContext().ensureActive()
        NormativeResult.Success(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TransportFailure) {
        NormativeResult.Failure(error.reason, error.fields)
    } catch (error: SessionAccessException) {
        NormativeResult.Failure(if (error.expired) NormativeFailure.SESSION else NormativeFailure.CONNECTION)
    } catch (_: SerializationException) {
        NormativeResult.Failure(NormativeFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) {
        NormativeResult.Failure(NormativeFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        NormativeResult.Failure(NormativeFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        NormativeResult.Failure(NormativeFailure.CONNECTION)
    }
