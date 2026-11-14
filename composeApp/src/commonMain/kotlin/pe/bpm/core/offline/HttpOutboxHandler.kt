package pe.bpm.core.offline

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.network.SessionAccessException

/**
 * Reenvía operaciones HTTP guardadas en la Outbox con un token vigente. Usa un cliente sin la capa
 * offline para no volver a encolar lo que ya se está sincronizando.
 */
class HttpOutboxHandler(
    private val client: HttpClient,
    private val apiBase: String,
    private val token: suspend () -> String?,
    private val onSessionRejected: (String) -> Unit = {},
    private val store: LocalStore? = null,
    /**
     * Se llama con la respuesta correcta de una operación enviada (ruta, cuerpo enviado, estado y cuerpo
     * recibido). Sirve para guardar resultados que el servidor calcula, como la calificación de una
     * evaluación. Un fallo aquí nunca cambia el resultado del envío.
     */
    private val onResponse: suspend (path: String, requestBody: String, status: Int, responseBody: String) -> Unit = { _, _, _, _ -> },
) : OutboxHandler {
    override suspend fun send(operation: OutboxOperation): SendOutcome {
        val payload = try {
            Json.decodeFromString(HttpOperationPayload.serializer(), operation.payload)
        } catch (_: Exception) {
            return SendOutcome.Rejected("La operación guardada está dañada y no puede enviarse.", 0)
        }
        if (!OfflineHttpPolicy.safeApiPath(payload.path) || payload.method.uppercase() !in setOf("POST", "PUT", "PATCH")) {
            return SendOutcome.Rejected("La operación guardada no es válida.", 0)
        }
        return try {
            val access = token()?.takeIf { it.isNotBlank() }
                ?: return SendOutcome.Retry("Falta renovar la sesión.", 401, network = true, countAttempt = false)
            var body = payload.body
            if (payload.refreshVersionFrom.isNotEmpty()) {
                if (!OfflineHttpPolicy.safeApiPath(payload.refreshVersionFrom)) return SendOutcome.Rejected("La operación guardada no es válida.", 0)
                val current = client.request(apiBase + payload.refreshVersionFrom) { bearerAuth(access) }
                if (current.status.value == 401) onSessionRejected(access)
                if (!current.status.isSuccess()) return classify(current, payload.refreshVersionFrom)
                val version = try {
                    (Json.parseToJsonElement(current.bodyAsText()) as JsonObject)["data"]!!.jsonObject["rowVersion"]!!.jsonPrimitive.content.toLong()
                } catch (_: Exception) { return SendOutcome.Retry("No se pudo leer la versión actual del registro.") }
                body = buildJsonObject {
                    (Json.parseToJsonElement(body) as JsonObject).forEach { (key, value) -> if (key != "rowVersion") put(key, value) }
                    put("rowVersion", version)
                }.toString()
            }
            val response = client.request(apiBase + payload.path) {
                method = HttpMethod.parse(payload.method.uppercase())
                bearerAuth(access)
                if (payload.keyHeader) header("Idempotency-Key", operation.idempotencyKey)
                if (body.isNotEmpty()) {
                    contentType(payload.contentType.takeIf { it.isNotBlank() }?.let(ContentType::parse) ?: ContentType.Application.Json)
                    setBody(body)
                }
            }
            if (response.status.value == 401) onSessionRejected(access)
            if (response.status.isSuccess()) {
                try { onResponse(payload.path, body, response.status.value, response.bodyAsText()) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* mejor esfuerzo */ }
                SendOutcome.Sent(response.status.value)
            } else classify(response, payload.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SessionAccessException) {
            if (error.expired) SendOutcome.Retry("Falta renovar la sesión.", 401, network = true, countAttempt = false)
            else SendOutcome.Retry("Sin conexión con el servidor.", network = true)
        } catch (_: Throwable) {
            SendOutcome.Retry("Sin conexión con el servidor.", network = true)
        }
    }

    override suspend fun onSent(operation: OutboxOperation) {
        val target = store ?: return
        val payload = try { Json.decodeFromString(HttpOperationPayload.serializer(), operation.payload) } catch (_: Exception) { return }
        OfflineCacheInvalidation.invalidate(target, payload.path)
    }

    private suspend fun classify(response: HttpResponse, path: String = ""): SendOutcome {
        val status = response.status.value
        if (response.status.isSuccess()) return SendOutcome.Sent(status)
        return when {
            status == 401 -> SendOutcome.Retry("Falta renovar la sesión.", 401, network = true, countAttempt = false)
            status == 409 -> when (errorCode(response)) {
                // Entrega repetida del mismo momento: el servidor ya la tiene, cuenta como enviada.
                "RESEARCH_SUBMISSION_EXISTS" -> if (path.endsWith("/research/submissions")) SendOutcome.Sent(status) else SendOutcome.Conflict(CONFLICT_MESSAGE, status)
                "TRAINING_VERSION_OUTDATED" -> SendOutcome.Conflict("El curso se actualizó mientras estabas sin conexión. Descarta esta evaluación y respóndela de nuevo.", status)
                else -> SendOutcome.Conflict(CONFLICT_MESSAGE, status)
            }
            status == 408 || status == 425 -> SendOutcome.Retry("El servidor tardó demasiado.", status)
            status == 429 -> SendOutcome.Retry("El servidor pidió esperar un momento.", status,
                retryAfterMs = response.headers["Retry-After"]?.toLongOrNull()?.takeIf { it in 0..900 }?.times(1000))
            status >= 500 -> SendOutcome.Retry("El servidor no está disponible.", status)
            status == 403 -> SendOutcome.Rejected("No tienes permiso para registrar este cambio.", status)
            status == 404 -> SendOutcome.Rejected("El registro relacionado ya no existe en el servidor.", status)
            else -> SendOutcome.Rejected(rejectionMessage(response), status)
        }
    }

    private suspend fun errorCode(response: HttpResponse): String = try {
        (Json.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("errors")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
    } catch (_: Exception) { "" }

    /** Solo se usa el código de negocio conocido; nunca se muestra texto interno del servidor. */
    private suspend fun rejectionMessage(response: HttpResponse): String {
        val code = try {
            (Json.parseToJsonElement(response.bodyAsText()) as? JsonObject)?.get("errors")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
        } catch (_: Exception) { "" }
        return when (code) {
            "SHIFT_OUT_OF_HOURS" -> "El control se hizo fuera del horario del turno."
            else -> "El servidor no aceptó los datos de este cambio. Revísalo o descártalo."
        }
    }

    companion object {
        const val KIND = "HTTP"
        const val CONFLICT_MESSAGE = "Otra persona o dispositivo cambió este registro. Descarta este cambio y vuelve a cargarlo."
    }
}

/** Construye operaciones HTTP diferidas para features que registran una escritura de forma explícita. */
object HttpOperations {
    fun draft(
        id: String, organizationId: String, method: String, path: String, body: String, idempotencyKey: String,
        entityId: String, clientCreatedAt: Long, keyHeader: Boolean = true, refreshVersionFrom: String = "",
    ): OutboxDraft {
        require(OfflineHttpPolicy.safeApiPath(path))
        val payload = HttpOperationPayload(method.uppercase(), path, if (body.isEmpty()) "" else "application/json", body, keyHeader, refreshVersionFrom)
        return OutboxDraft(id, HttpOutboxHandler.KIND, organizationId, entityId, idempotencyKey,
            Json.encodeToString(HttpOperationPayload.serializer(), payload), clientCreatedAt)
    }
}
