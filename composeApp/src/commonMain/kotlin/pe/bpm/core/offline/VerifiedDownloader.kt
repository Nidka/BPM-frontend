package pe.bpm.core.offline

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import pe.bpm.core.crypto.Sha256

/** Motivos públicos por los que no se pudo dejar un archivo verificado en el dispositivo. */
enum class DownloadFailure {
    /** Sin red o corte repetido; lo descargado se conserva para reanudar. */
    NETWORK,
    /** No se pudo obtener la dirección de descarga (sin permiso o sin conexión). */
    TICKET,
    /** El almacén de este dispositivo no admite archivos grandes (navegador). */
    UNSUPPORTED,
}

sealed interface DownloadOutcome {
    /** Archivo completo, con tamaño y SHA-256 verificados, ya publicado en el almacén. */
    data object Done : DownloadOutcome
    /** El contenido recibido no coincide con el tamaño o el SHA-256 esperados; se descartó. */
    data object Corrupt : DownloadOutcome
    data class Failed(val reason: DownloadFailure) : DownloadOutcome
}

/**
 * Descarga un archivo grande al [PrivateFileStore] por bloques, sin cargarlo en memoria, y verifica
 * tamaño y SHA-256 antes de publicarlo. Si la conexión se corta, reanuda con `Range` desde lo ya
 * escrito (rehaciendo el hash de esa parte); si el origen ignora `Range`, reinicia. La dirección
 * firmada se pide con [urlProvider] justo antes de cada intento y nunca se guarda.
 */
class VerifiedDownloader(
    private val client: HttpClient,
    private val files: PrivateFileStore,
    private val maxAttempts: Int = 4,
    private val chunkBytes: Int = 64 * 1024,
    private val retryDelay: suspend (attempt: Int) -> Unit = { attempt -> delay(1_000L shl (attempt - 1).coerceIn(0, 4)) },
) {
    suspend fun download(
        ref: String,
        expectedSize: Long,
        expectedSha256: String,
        urlProvider: suspend () -> String?,
        onProgress: (Long) -> Unit = {},
    ): DownloadOutcome {
        require(ref.matches(PRIVATE_FILE_REF) && expectedSize > 0 && expectedSha256.matches(Regex("[0-9a-fA-F]{64}")))
        if (!files.supportsStreaming) return DownloadOutcome.Failed(DownloadFailure.UNSUPPORTED)
        var corruptions = 0
        var failure = DownloadFailure.NETWORK
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            if (attempt > 1) retryDelay(attempt - 1)
            val url = try { urlProvider() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (url == null) { failure = DownloadFailure.TICKET; continue }
            when (transfer(ref, url, expectedSize, expectedSha256.lowercase(), onProgress)) {
                Step.Done -> return DownloadOutcome.Done
                Step.Corrupt -> {
                    // Una sola repetición completa: un archivo que vuelve a fallar no coincide con el catálogo.
                    files.discardPart(ref)
                    if (++corruptions >= 2) return DownloadOutcome.Corrupt
                }
                Step.Interrupted -> failure = DownloadFailure.NETWORK
                Step.Restart -> files.discardPart(ref)
            }
        }
        return DownloadOutcome.Failed(failure)
    }

    private enum class Step { Done, Corrupt, Interrupted, Restart }

    private suspend fun transfer(ref: String, url: String, size: Long, sha: String, onProgress: (Long) -> Unit): Step {
        var have = files.partialSize(ref)
        if (have > size) { files.discardPart(ref); have = 0 }
        var hash = Sha256.Calculo()
        val buffer = ByteArray(chunkBytes)
        if (have > 0) {
            var offset = 0L
            while (offset < have) {
                val read = files.readPart(ref, offset, buffer)
                if (read <= 0) break
                hash.update(buffer, 0, read)
                offset += read
                yield()
            }
            if (offset != have) { files.discardPart(ref); have = 0; hash = Sha256.Calculo() }
        }
        onProgress(have)
        try {
            var result = Step.Interrupted
            client.prepareGet(url) {
                if (client.pluginOrNull(HttpTimeout) != null) timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = 60_000 }
                if (have > 0) header(HttpHeaders.Range, "bytes=$have-")
            }.execute { response ->
                val status = response.status.value
                when {
                    status == 206 && have > 0 && response.headers[HttpHeaders.ContentRange]?.startsWith("bytes $have-") == true -> Unit
                    status == 200 -> if (have > 0) {
                        // El origen ignoró Range: se reinicia desde cero con la respuesta completa.
                        files.discardPart(ref); have = 0; hash = Sha256.Calculo(); onProgress(0)
                    }
                    status == 416 -> { result = if (have == size) Step.Corrupt else Step.Restart; return@execute }
                    else -> { result = Step.Interrupted; return@execute }
                }
                val channel = response.bodyAsChannel()
                var sinceYield = 0
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (read == 0) continue
                    if (have + read > size) { result = Step.Corrupt; return@execute }
                    files.appendPart(ref, buffer, read)
                    hash.update(buffer, 0, read)
                    have += read
                    onProgress(have)
                    sinceYield += read
                    if (sinceYield >= YIELD_EVERY) { sinceYield = 0; yield() }
                }
                result = if (have == size) Step.Done else Step.Interrupted
            }
            if (result != Step.Done) return result
            if (hash.hex() != sha) return Step.Corrupt
            files.commitPart(ref)
            return Step.Done
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Step.Interrupted
        }
    }

    private companion object {
        const val YIELD_EVERY = 1 shl 20
    }
}
