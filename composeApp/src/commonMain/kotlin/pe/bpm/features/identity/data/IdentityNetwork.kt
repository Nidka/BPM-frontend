package pe.bpm.features.identity.data

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException

/**
 * Tiempo máximo de las llamadas de acceso (iniciar sesión, renovar, leer el perfil): 30 s en lugar de los 12 s generales. Firebase y el
 * servidor pueden tardar más en un arranque en frío o con red móvil lenta, y cancelar a los 12 s dejaba a la persona en «La conexión tardó
 * demasiado» aunque el acceso estuviera a punto de terminar.
 */
internal const val IDENTITY_REQUEST_TIMEOUT_MS = 30_000L

internal fun HttpRequestBuilder.identityTimeout() {
    timeout { requestTimeoutMillis = IDENTITY_REQUEST_TIMEOUT_MS; socketTimeoutMillis = IDENTITY_REQUEST_TIMEOUT_MS }
}

/** Esperas entre intentos de una lectura de acceso: 0,6 s y 1,5 s (dos reintentos como máximo). */
internal val IDENTITY_BACKOFF_MS = listOf(600L, 1_500L)

/**
 * Repite [block] ante un fallo de red o un tiempo agotado, con espera creciente, antes de rendirse; así un corte breve no obliga a
 * pulsar de nuevo. Solo para llamadas **seguras de repetir** (iniciar sesión, renovar credencial, leer configuración o perfil): nunca para
 * crear cuenta, enviar correos o cambiar datos. La cancelación y los errores de interpretación de la respuesta no se reintentan.
 */
internal suspend fun <R> retryingNetwork(backoff: List<Long> = IDENTITY_BACKOFF_MS, block: suspend () -> R): R {
    for (wait in backoff) {
        try { return block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IdentityDataException) { throw error }
        catch (error: SerializationException) { throw error }
        catch (_: Throwable) { delay(wait) }
    }
    return block()
}
