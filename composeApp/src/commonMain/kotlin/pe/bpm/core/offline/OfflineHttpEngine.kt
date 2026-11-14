package pe.bpm.core.offline

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.IOException
import kotlinx.serialization.json.Json

/** Cabecera sintética que indica que la respuesta salió de la caché local, con su marca de tiempo (ms). */
const val OFFLINE_CACHE_HEADER = "X-BPM-Offline-Cache"

/**
 * Cabecera interna que pide leer SOLO de la caché local (lectura local primero). La añade el plugin
 * [LocalFirstPlugin] cuando la llamada corre dentro de [LocalFirst]; el motor la retira y nunca llega a la red.
 */
const val CACHE_ONLY_HEADER = "X-BPM-Cache-Only"

/** No hay copia local de la lectura pedida con [CACHE_ONLY_HEADER]. */
class OfflineCacheMissException : IOException("Sin copia local")

/** Error que reciben los repositorios cuando una escritura quedó registrada para enviarse después. */
class OfflineQueuedException(val operationId: String) : IOException("Operación guardada para enviar con conexión")

/** Dependencias del motor; todas son puertos del núcleo offline. */
class OfflineHttpRuntime(
    val active: ActiveScope,
    val store: LocalStore,
    val outbox: Outbox,
    val connectivity: ConnectivityMonitor,
    val clock: () -> Long,
    val newId: () -> String,
) {
    private val stale = MutableStateFlow<Long?>(null)
    /** Marca de la caché más antigua mostrada desde la última lectura exitosa en línea; null si todo es fresco. */
    val staleSince: StateFlow<Long?> = stale.asStateFlow()
    internal fun served(syncedAt: Long) { stale.value = minOf(stale.value ?: syncedAt, syncedAt) }
    internal fun fresh() { stale.value = null }
}

/**
 * Motor HTTP que envuelve al de la plataforma:
 *  - lecturas permitidas: se guardan y, sin red, se sirven desde la caché local;
 *  - escrituras permitidas: sin red (o tras un fallo de conexión) se registran en la Outbox con su
 *    clave de idempotencia y se lanza [OfflineQueuedException].
 * No persiste cabeceras de autorización: el envío posterior obtiene un token vigente.
 */
@OptIn(InternalAPI::class)
class OfflineHttpEngine(
    private val delegate: HttpClientEngine,
    private val runtime: OfflineHttpRuntime,
    /** Base de la API (origen y prefijo, p. ej. el proxy web); solo sus rutas /api/v1/ pasan por la capa offline. */
    private val apiBase: String,
) : HttpClientEngineBase("bpm-offline") {
    override val config: HttpClientEngineConfig get() = delegate.config
    @Suppress("DEPRECATION")
    override val dispatcher: CoroutineDispatcher get() = delegate.dispatcher
    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> get() = delegate.supportedCapabilities

    override fun close() {
        delegate.close()
        super.close()
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val scope = runtime.active.current
        val full = data.url.toString()
        if (scope == null || !full.startsWith(apiBase + OfflineHttpPolicy.API_PREFIX)) return delegate.execute(data)
        val method = data.method.value
        val path = full.removePrefix(apiBase)
        if (data.headers[CACHE_ONLY_HEADER] != null) {
            return if (OfflineHttpPolicy.cacheableRead(method, path)) cached(path) ?: throw OfflineCacheMissException() else throw OfflineCacheMissException()
        }
        return when {
            OfflineHttpPolicy.cacheableRead(method, path) -> read(data, path)
            else -> {
                val body = bodyText(data)
                if (body != null && OfflineHttpPolicy.queueableWrite(method, path)) write(data, method, path, body) else delegate.execute(data)
            }
        }
    }

    private suspend fun read(data: HttpRequestData, path: String): HttpResponseData {
        if (!runtime.connectivity.online.value) return cached(path) ?: delegate.execute(data)
        val response = try {
            delegate.execute(data)
        } catch (cancelled: CancellationException) {
            // Un tiempo de espera agotado llega como cancelación de la llamada: se atiende desde la caché.
            if (!isTimeout(cancelled)) throw cancelled
            return withContext(NonCancellable) { cached(path) } ?: throw cancelled
        } catch (error: Throwable) {
            return cached(path) ?: throw error
        }
        val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val json = response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true
        if (response.statusCode.value != 200 || !json || (declared != null && declared > MAX_CACHED_BYTES)) return response
        val channel = response.body as? ByteReadChannel ?: return response
        val bytes = channel.toByteArray()
        if (bytes.size <= MAX_CACHED_BYTES) {
            try { runtime.store.put(CACHE_COLLECTION, path, bytes.decodeToString()) } catch (_: Exception) { /* la caché es opcional */ }
        }
        runtime.fresh()
        return HttpResponseData(response.statusCode, response.requestTime, response.headers, response.version, ByteReadChannel(bytes), response.callContext)
    }

    private suspend fun cached(path: String): HttpResponseData? {
        val record = try { runtime.store.get(CACHE_COLLECTION, path) } catch (_: Exception) { null } ?: return null
        runtime.served(record.updatedAt)
        return HttpResponseData(
            HttpStatusCode.OK, GMTDate(),
            Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(OFFLINE_CACHE_HEADER, record.updatedAt.toString())
            },
            HttpProtocolVersion.HTTP_1_1, ByteReadChannel(record.json.encodeToByteArray()), callContext(),
        )
    }

    private suspend fun write(data: HttpRequestData, method: String, path: String, body: String): HttpResponseData {
        val organization = OfflineHttpPolicy.organizationOf(path)
        val key = OfflineHttpPolicy.idempotencyKey(data.headers["Idempotency-Key"], method, path, body)
            ?: if (OfflineHttpPolicy.generatesKey(method, path)) runtime.newId() else return delegate.execute(data)
        val entity = OfflineHttpPolicy.entityOf(path, body)
        if (!runtime.connectivity.online.value || runtime.outbox.hasUnsentForEntity(entity)) throw queue(data, method, path, body, organization, entity, key)
        val response = try {
            delegate.execute(data)
        } catch (cancelled: CancellationException) {
            // El tiempo de espera agotado no es una cancelación del usuario: la escritura se registra para enviarla después.
            if (!isTimeout(cancelled)) throw cancelled
            throw withContext(NonCancellable) { queue(data, method, path, body, organization, entity, key) }
        } catch (_: Throwable) {
            throw queue(data, method, path, body, organization, entity, key)
        }
        if (response.statusCode.value in 200..299) invalidate(path)
        return response
    }

    private suspend fun queue(data: HttpRequestData, method: String, path: String, body: String, organization: String, entity: String, key: String): OfflineQueuedException {
        val payload = HttpOperationPayload(
            method, path, data.body.contentType?.toString().orEmpty(), body, keyHeader = data.headers["Idempotency-Key"] != null,
        )
        val operation = runtime.outbox.enqueue(OutboxDraft(
            id = runtime.newId(), kind = HttpOutboxHandler.KIND, organizationId = organization, entityId = entity,
            idempotencyKey = key, payload = Json.encodeToString(HttpOperationPayload.serializer(), payload), clientCreatedAt = runtime.clock(),
        ))
        return OfflineQueuedException(operation.id)
    }

    private suspend fun invalidate(path: String) {
        OfflineCacheInvalidation.invalidate(runtime.store, path)
    }

    private fun bodyText(data: HttpRequestData): String? = when (val content = data.body) {
        is OutgoingContent.NoContent -> ""
        is ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> null
    }

    companion object {
        const val CACHE_COLLECTION = "http-cache"
        const val MAX_CACHED_BYTES = 400_000
    }
}

/** true si el error (o su causa) es un tiempo de espera de conexión, de socket o de la solicitud completa. */
internal fun isTimeout(error: Throwable): Boolean {
    var cause: Throwable? = error
    var depth = 0
    while (cause != null && depth++ < 6) {
        if (cause is HttpRequestTimeoutException || cause is ConnectTimeoutException || cause is SocketTimeoutException) return true
        cause = cause.cause
    }
    return false
}

/** Una escritura confirmada deja obsoletas las lecturas del mismo recurso: se retiran de la caché. */
internal object OfflineCacheInvalidation {
    suspend fun invalidate(store: LocalStore, writePath: String) {
        val clean = OfflineHttpPolicy.pathOf(writePath)
        val organization = OfflineHttpPolicy.organizationOf(clean)
        if (organization.isEmpty()) return
        val resource = clean.removePrefix("/api/v1/organizations/$organization/").substringBefore('/')
        val prefix = "/api/v1/organizations/$organization/$resource"
        try {
            store.list(OfflineHttpEngine.CACHE_COLLECTION).filter { it.key.startsWith(prefix) }
                .forEach { store.remove(OfflineHttpEngine.CACHE_COLLECTION, it.key) }
        } catch (_: Exception) { /* la caché es opcional */ }
    }
}
