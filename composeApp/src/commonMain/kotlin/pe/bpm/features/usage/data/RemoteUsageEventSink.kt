package pe.bpm.features.usage.data

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.features.usage.domain.*

/**
 * Envía lotes de uso a `POST /organizations/{org}/usage/events`. El cuerpo solo contiene los campos del
 * contrato (sin metadatos). Ningún error técnico se propaga: se clasifica en reintentar o descartar.
 */
class RemoteUsageEventSink(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : UsageEventSink {

    override suspend fun send(organizationId: String, events: List<UsageEvent>): UsageSendResult {
        if (events.isEmpty()) return UsageSendResult.Accepted
        if (events.size > UsageBatcher.MAX_BATCH || !Regex("[A-Za-z0-9_-]{1,64}").matches(organizationId)) return UsageSendResult.Rejected
        return try {
            val access = token()?.takeIf { it.isNotBlank() } ?: return UsageSendResult.RetryLater
            val response = client.post("${config.apiBaseUrl}/api/v1/organizations/$organizationId/usage/events") {
                bearerAuth(access)
                contentType(ContentType.Application.Json)
                setBody(body(events))
            }
            onSessionResponse(response)
            classify(response.status.value).also { if (response.status.value == 401) onSessionRejected(access) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            UsageSendResult.RetryLater
        }
    }

    internal fun body(events: List<UsageEvent>): JsonObject = buildJsonObject {
        putJsonArray("events") {
            events.forEach { event ->
                add(buildJsonObject {
                    put("id", event.id)
                    put("sessionId", event.sessionId)
                    put("type", event.type.name)
                    event.module?.let { put("module", it.name) }
                    put("occurredAt", event.occurredAt)
                    event.durationSeconds?.let { put("durationSeconds", it) }
                })
            }
        }
    }

    companion object {
        /**
         * 2xx acepta; 401, 408, 429 y 5xx se reintentan; el resto de 4xx (incluido 403 de quien no tiene
         * membresía y 409 de conflictos) es definitivo y el lote se descarta.
         */
        internal fun classify(status: Int): UsageSendResult = when {
            status in 200..299 -> UsageSendResult.Accepted
            status == 401 || status == 408 || status == 429 || status >= 500 -> UsageSendResult.RetryLater
            else -> UsageSendResult.Rejected
        }
    }
}
