package pe.bpm.core.offline

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Elemento de contexto que pide leer solo de la caché local. Mientras una llamada corre dentro de
 * [LocalFirstMode], [LocalFirstPlugin] marca sus solicitudes y [OfflineHttpEngine] las atiende desde
 * la base local, sin tocar la red; sin copia local falla con [OfflineCacheMissException].
 */
object LocalFirstMode : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<LocalFirstMode>
}

/**
 * Plugin del cliente HTTP: traduce [LocalFirstMode] en la cabecera interna [CACHE_ONLY_HEADER] y, si una
 * lectura agota su tiempo de espera, la repite una vez contra la caché local (la copia guardada en lugar de
 * un error). Debe instalarse después de HttpTimeout (los interceptores más recientes corren primero).
 */
val LocalFirstPlugin = createClientPlugin("BpmLocalFirst") {
    onRequest { request, _ ->
        if (currentCoroutineContext()[LocalFirstMode.Key] != null) request.headers.append(CACHE_ONLY_HEADER, "1")
    }
    on(Send) { request ->
        try {
            proceed(request)
        } catch (timeout: Throwable) {
            if (!isTimeout(timeout) || !currentCoroutineContext().isActive || request.method != HttpMethod.Get || request.headers.contains(CACHE_ONLY_HEADER)) throw timeout
            // Una solicitud nueva: la original ya tiene su contexto de ejecución cancelado por el tiempo de espera.
            val retry = HttpRequestBuilder().apply {
                method = HttpMethod.Get
                url.takeFrom(request.url.build())
                headers.appendAll(request.headers.build())
                headers.append(CACHE_ONLY_HEADER, "1")
                // Los atributos de la solicitud original (validación de respuestas, tiempos) se conservan.
                @Suppress("UNCHECKED_CAST")
                request.attributes.allKeys.forEach { key -> attributes.put(key as AttributeKey<Any>, request.attributes[key]) }
            }
            try {
                proceed(retry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw timeout
            }
        }
    }
}

/**
 * Estrategia de lectura de una pantalla. [Direct] pide al servidor una sola vez (pruebas con repositorios falsos);
 * [Cached] entrega primero la copia local y luego refresca, y es la que compone la aplicación.
 */
interface LocalFirstReader {
    suspend fun <R> read(isUsable: (R) -> Boolean, onLocal: suspend (R) -> Unit, load: suspend () -> R): R

    companion object {
        val Direct: LocalFirstReader = object : LocalFirstReader {
            override suspend fun <R> read(isUsable: (R) -> Boolean, onLocal: suspend (R) -> Unit, load: suspend () -> R): R = load()
        }
        val Cached: LocalFirstReader = object : LocalFirstReader {
            override suspend fun <R> read(isUsable: (R) -> Boolean, onLocal: suspend (R) -> Unit, load: suspend () -> R): R =
                readLocalFirst(isUsable, onLocal, load)
        }
    }
}

/**
 * Lectura local primero para las pantallas de trabajo diario: si hay copia local la entrega de inmediato
 * a [onLocal] (marcada por la pantalla como «Datos de hace X») y después pide al servidor con [load];
 * sin red, [load] cae a la misma copia. Equivale a [CachedResource] pero para los repositorios que ya
 * leen por HTTP con caché transparente. Devuelve el resultado de la lectura normal.
 */
suspend fun <R> readLocalFirst(isUsable: (R) -> Boolean, onLocal: suspend (R) -> Unit, load: suspend () -> R): R {
    val local = try {
        withContext(LocalFirstMode) { load() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (local != null && isUsable(local)) onLocal(local)
    return load()
}
