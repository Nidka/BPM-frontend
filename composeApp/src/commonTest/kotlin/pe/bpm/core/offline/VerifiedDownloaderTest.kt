package pe.bpm.core.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import pe.bpm.core.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Descarga por bloques con verificación SHA-256, reanudación y reinicio. */
class VerifiedDownloaderTest {
    private val data = ByteArray(5_000) { (it * 31 + 7).toByte() }
    private val sha = Sha256.hex(data)
    private val ref = "video-demo.mp4"

    private class Origin(val data: ByteArray) {
        val ranges = mutableListOf<String?>()
        var cutOnceAt: Int? = null
        var honorRange = true
        var failNetwork = false
        var served: ByteArray = data
    }

    private fun client(origin: Origin) = HttpClient(MockEngine { request ->
        val range = request.headers[HttpHeaders.Range]
        origin.ranges += range
        if (origin.failNetwork) throw IOException("sin red")
        val start = if (range != null && origin.honorRange) range.removePrefix("bytes=").removeSuffix("-").toInt() else 0
        val end = origin.cutOnceAt?.takeIf { it > start } ?: origin.served.size
        origin.cutOnceAt = null
        val slice = origin.served.copyOfRange(start, end)
        if (range != null && origin.honorRange)
            respond(ByteReadChannel(slice), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes $start-${origin.served.size - 1}/${origin.served.size}"))
        else respond(ByteReadChannel(slice), HttpStatusCode.OK)
    }) { expectSuccess = false; install(HttpTimeout) }

    private fun downloader(origin: Origin, files: PrivateFileStore, attempts: Int = 4) =
        VerifiedDownloader(client(origin), files, maxAttempts = attempts, chunkBytes = 512, retryDelay = {})

    @Test fun aCorrectFileIsStoredAfterVerifyingSizeAndSha256() = runTest {
        val origin = Origin(data); val files = InMemoryPrivateFileStore(durable = true)
        val progress = mutableListOf<Long>()
        val outcome = downloader(origin, files).download(ref, data.size.toLong(), sha, { "https://r2.example/video" }, { progress += it })
        assertEquals(DownloadOutcome.Done, outcome)
        assertTrue(files.read(ref)!!.contentEquals(data))
        assertEquals(0L, files.partialSize(ref))
        assertEquals(data.size.toLong(), progress.last())
    }

    @Test fun aCorruptFileIsDiscardedAndNeverPublished() = runTest {
        val origin = Origin(data).also { it.served = data.copyOf().also { copy -> copy[100] = (copy[100] + 1).toByte() } }
        val files = InMemoryPrivateFileStore(durable = true)
        val outcome = downloader(origin, files).download(ref, data.size.toLong(), sha, { "https://r2.example/video" })
        assertEquals(DownloadOutcome.Corrupt, outcome)
        assertNull(files.size(ref))
        assertEquals(0L, files.partialSize(ref))
        // Un único reintento completo antes de rendirse.
        assertEquals(2, origin.ranges.size)
    }

    @Test fun aCutDownloadResumesFromWhatWasAlreadyWritten() = runTest {
        val origin = Origin(data).also { it.cutOnceAt = 2_000 }
        val files = InMemoryPrivateFileStore(durable = true)
        var tickets = 0
        val outcome = downloader(origin, files).download(ref, data.size.toLong(), sha, { tickets++; "https://r2.example/video?sig=$tickets" })
        assertEquals(DownloadOutcome.Done, outcome)
        assertEquals(listOf(null, "bytes=2000-"), origin.ranges)
        assertTrue(files.read(ref)!!.contentEquals(data))
        // Cada intento pidió una URL firmada nueva.
        assertEquals(2, tickets)
    }

    @Test fun anExistingPartIsHashedAndContinuedAcrossSessions() = runTest {
        val origin = Origin(data); val files = InMemoryPrivateFileStore(durable = true)
        files.appendPart(ref, data, 1_500)
        val outcome = downloader(origin, files).download(ref, data.size.toLong(), sha, { "https://r2.example/video" })
        assertEquals(DownloadOutcome.Done, outcome)
        assertEquals(listOf<String?>("bytes=1500-"), origin.ranges)
        assertTrue(files.read(ref)!!.contentEquals(data))
    }

    @Test fun anOriginThatIgnoresRangeRestartsFromTheBeginning() = runTest {
        val origin = Origin(data).also { it.honorRange = false }
        val files = InMemoryPrivateFileStore(durable = true)
        files.appendPart(ref, data, 1_000)
        assertEquals(DownloadOutcome.Done, downloader(origin, files).download(ref, data.size.toLong(), sha, { "https://r2.example/video" }))
        assertTrue(files.read(ref)!!.contentEquals(data))
    }

    @Test fun aBrokenConnectionKeepsTheProgressAndReportsNetwork() = runTest {
        val origin = Origin(data).also { it.failNetwork = true }
        val files = InMemoryPrivateFileStore(durable = true)
        files.appendPart(ref, data, 700)
        val outcome = downloader(origin, files, attempts = 2).download(ref, data.size.toLong(), sha, { "https://r2.example/video" })
        assertEquals(DownloadOutcome.Failed(DownloadFailure.NETWORK), outcome)
        assertEquals(700L, files.partialSize(ref))
    }

    @Test fun withoutATicketTheDownloadFailsWithoutTouchingTheNetwork() = runTest {
        val origin = Origin(data); val files = InMemoryPrivateFileStore(durable = true)
        assertEquals(DownloadOutcome.Failed(DownloadFailure.TICKET), downloader(origin, files, attempts = 2).download(ref, data.size.toLong(), sha, { null }))
        assertTrue(origin.ranges.isEmpty())
    }

    @Test fun hostsWithoutStreamingStorageReportUnsupported() = runTest {
        val unsupported = object : PrivateFileStore {
            override val durable = false
            override suspend fun save(ref: String, bytes: ByteArray) = Unit
            override suspend fun read(ref: String): ByteArray? = null
            override suspend fun delete(ref: String) = Unit
            override suspend fun deleteAll() = Unit
        }
        assertEquals(DownloadOutcome.Failed(DownloadFailure.UNSUPPORTED),
            downloader(Origin(data), unsupported).download(ref, data.size.toLong(), sha, { "https://r2.example/video" }))
    }
}
