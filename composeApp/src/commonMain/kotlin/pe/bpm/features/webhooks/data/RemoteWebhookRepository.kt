package pe.bpm.features.webhooks.data

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
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.webhooks.domain.*

@Serializable internal data class Envelope<T>(val data: T)
@Serializable internal data class ErrorItem(val code: String = "", val fields: Map<String, String> = emptyMap())
@Serializable internal data class ErrorsEnvelope(val errors: List<ErrorItem> = emptyList())
@Serializable internal data class WebhookDto(
    val id: String, val url: String, val eventTypes: List<String>, val active: Boolean,
    val lastStatus: Int? = null, val lastError: String = "", val lastDeliveryAt: String? = null, val secret: String? = null,
) {
    fun domain(): Webhook {
        require(Regex("[0-9a-f-]{36}").matches(id) && url.startsWith("https://") && eventTypes.isNotEmpty())
        require(secret == null || secret.startsWith("whsec_"))
        return Webhook(id, url, eventTypes, active, lastStatus, lastError, lastDeliveryAt, secret)
    }
}
@Serializable internal data class ListDto(val items: List<WebhookDto>, val eventTypes: List<String>, val maxEndpoints: Int)
@Serializable internal data class TestDto(val delivered: Boolean, val status: Int)

/** Adaptador Ktor de webhooks. El secreto se entrega una sola vez a la pantalla y no se registra ni se guarda. */
class RemoteWebhookRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : WebhookRepository {
    override suspend fun list(org: String): WebhookResult<WebhookList> = guarded {
        val dto = request(org, "").body<Envelope<ListDto>>().data
        WebhookList(dto.items.map { it.domain() }, dto.eventTypes, dto.maxEndpoints)
    }.let { result ->
        // Un servidor anterior a V18 no tiene la ruta: la función aún no está disponible.
        if (result is WebhookResult.Failure && result.reason == WebhookFailure.NOT_FOUND) WebhookResult.Failure(WebhookFailure.NOT_DEPLOYED) else result
    }
    override suspend fun create(org: String, input: WebhookInput) = guarded { request(org, "", HttpMethod.Post, input.json()).body<Envelope<WebhookDto>>().data.domain() }
    override suspend fun update(org: String, id: String, input: WebhookInput) = guarded { request(org, "/${uuid(id)}", HttpMethod.Put, input.json()).body<Envelope<WebhookDto>>().data.domain() }
    override suspend fun delete(org: String, id: String) = guarded { request(org, "/${uuid(id)}", HttpMethod.Delete); Unit }
    override suspend fun rotate(org: String, id: String) = guarded { request(org, "/${uuid(id)}/rotate-secret", HttpMethod.Post, buildJsonObject { }).body<Envelope<WebhookDto>>().data.domain() }
    override suspend fun test(org: String, id: String) = guarded {
        val dto = request(org, "/${uuid(id)}/test", HttpMethod.Post, buildJsonObject { }).body<Envelope<TestDto>>().data
        WebhookTest(dto.delivered, dto.status)
    }

    private fun WebhookInput.json() = buildJsonObject {
        put("url", url.trim()); put("active", active); putJsonArray("eventTypes") { eventTypes.sorted().forEach { add(it) } }
    }

    private fun uuid(value: String): String { require(Regex("[0-9a-f-]{36}").matches(value)); return value }

    private suspend fun request(org: String, path: String, verb: HttpMethod = HttpMethod.Get, body: JsonObject? = null): HttpResponse {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(org))
        val access = token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(WebhookFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/organizations/$org/webhooks$path") {
            method = verb
            bearerAuth(access)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) onSessionRejected(access)
            val error = if (response.status.value in setOf(404, 409, 422))
                try { response.body<ErrorsEnvelope>().errors.firstOrNull() } catch (c: CancellationException) { throw c } catch (_: Exception) { null } else null
            throw TransportFailure(when (response.status.value) {
                401 -> WebhookFailure.SESSION; 403 -> WebhookFailure.DENIED; 404 -> WebhookFailure.NOT_FOUND
                409 -> WebhookFailure.LIMIT; 400, 422 -> WebhookFailure.INVALID; else -> WebhookFailure.UNAVAILABLE
            }, error?.fields.orEmpty().filterKeys { it == "url" || it == "eventTypes" })
        }
        return response
    }
}

private class TransportFailure(val reason: WebhookFailure, val fields: Map<String, String> = emptyMap()) : Exception()

private suspend inline fun <T> guarded(block: () -> T): WebhookResult<T> =
    try {
        val result = block()
        currentCoroutineContext().ensureActive()
        WebhookResult.Success(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TransportFailure) {
        WebhookResult.Failure(error.reason, error.fields)
    } catch (error: SessionAccessException) {
        WebhookResult.Failure(if (error.expired) WebhookFailure.SESSION else WebhookFailure.CONNECTION)
    } catch (_: SerializationException) {
        WebhookResult.Failure(WebhookFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) {
        WebhookResult.Failure(WebhookFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        WebhookResult.Failure(WebhookFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        WebhookResult.Failure(WebhookFailure.CONNECTION)
    }
