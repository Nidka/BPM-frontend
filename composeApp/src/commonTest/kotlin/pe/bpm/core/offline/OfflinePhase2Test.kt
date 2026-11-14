package pe.bpm.core.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import pe.bpm.core.network.createHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ORIGIN = "https://api.test"
private const val ORG = "$ORIGIN/api/v1/organizations/org1"
private val json = headersOf(HttpHeaders.ContentType, "application/json")

/** Fase 2: lectura local primero, tiempos de espera, marcas de pendiente y archivos por bloques. */
class OfflinePhase2Test {
    private class Rig(timeoutMillis: Long? = null) {
        val h = OfflineHarness()
        val requests = mutableListOf<String>()
        var body = """{"data":["viejo"]}"""
        var slow = false
        val delegate = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            if (slow) delay(10_000)
            respond(body, HttpStatusCode.OK, json)
        }
        val engine = OfflineHttpEngine(delegate, h.runtime.http, ORIGIN)
        val client: HttpClient = if (timeoutMillis == null) createHttpClient(engine) else HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = timeoutMillis }
            install(LocalFirstPlugin)
        }
    }

    @Test fun localFirstShowsTheSavedCopyImmediatelyAndThenTheFreshOne() = runTest {
        val rig = Rig(); rig.h.login()
        rig.client.get("$ORG/alerts")
        rig.body = """{"data":["nuevo"]}"""
        rig.requests.clear()
        val seen = mutableListOf<String>()
        val result = readLocalFirst(
            isUsable = { it.isNotBlank() },
            onLocal = { seen += "local:$it" },
            load = { rig.client.get("$ORG/alerts").bodyAsText() },
        )
        // La copia local salió sin tocar la red; después se pidió la versión fresca una sola vez.
        assertEquals(listOf("""local:{"data":["viejo"]}"""), seen)
        assertEquals("""{"data":["nuevo"]}""", result)
        assertEquals(listOf("GET /api/v1/organizations/org1/alerts"), rig.requests)
    }

    @Test fun localFirstWithoutACopyAsksTheServerOnly() = runTest {
        val rig = Rig(); rig.h.login()
        val seen = mutableListOf<String>()
        val result = readLocalFirst(isUsable = { true }, onLocal = { seen += it }) { rig.client.get("$ORG/alerts").bodyAsText() }
        assertTrue(seen.isEmpty())
        assertEquals("""{"data":["viejo"]}""", result)
    }

    @Test fun localFirstOfflineServesTheCopyTwiceWithoutNetwork() = runTest {
        val rig = Rig(); rig.h.login()
        rig.client.get("$ORG/incidents")
        rig.h.connectivity.set(false)
        rig.requests.clear()
        val seen = mutableListOf<String>()
        val result = readLocalFirst({ true }, { seen += it }) { rig.client.get("$ORG/incidents").bodyAsText() }
        assertEquals(1, seen.size)
        assertEquals(seen.single(), result)
        assertTrue(rig.requests.isEmpty())
    }

    @Test fun aReadTimeoutFallsBackToTheCachedCopy() = runTest {
        val rig = Rig(timeoutMillis = 100); rig.h.login()
        rig.client.get("$ORG/alerts")
        rig.slow = true
        assertEquals("""{"data":["viejo"]}""", rig.client.get("$ORG/alerts").bodyAsText())
    }

    @Test fun aWriteTimeoutIsQueuedInsteadOfLost() = runTest {
        val rig = Rig(timeoutMillis = 100); rig.h.login()
        rig.slow = true
        val failure = runCatching {
            rig.client.post("$ORG/cleaning-executions") {
                header("Idempotency-Key", "key-timeout-0001")
                contentType(ContentType.Application.Json); setBody("""{"id":"e-timeout","note":"ok"}""")
            }
        }.exceptionOrNull()
        // El repositorio recibe la señal de «guardado para después», no un fallo de conexión.
        assertTrue(failure is OfflineQueuedException, "se esperaba OfflineQueuedException y fue $failure")
        val op = rig.h.runtime.outbox.snapshot().single()
        assertEquals("key-timeout-0001", op.idempotencyKey)
        assertEquals("e-timeout", op.entityId)
        assertEquals(OutboxStatus.PENDING, op.status)
    }

    @Test fun pendingMarksIdentifyTheRecordAndTheResource() = runTest {
        val h = OfflineHarness(); h.login()
        val draft = HttpOperations.draft("op-a", "org1", "POST", "/api/v1/organizations/org1/incidents", """{"id":"inc-1"}""", "key-incident-0001", "inc-1", 1)
        val queued = h.runtime.outbox.enqueue(draft)
        val mark = queued.toMark()!!
        assertEquals("inc-1", mark.entityId)
        assertEquals("incidents", mark.resource)
        val marks = PendingMarks(listOf(mark))
        assertTrue(marks.isPending("INC-1"))
        assertFalse(marks.isPending("otro"))
        assertEquals(1, marks.forResource("incidents", "alerts").size)
        assertTrue(marks.forResource("alerts").isEmpty())
        // Lo ya enviado no marca nada.
        assertEquals(null, queued.copy(status = OutboxStatus.SENT).toMark())
    }

    @Test fun streamingStoreKeepsPartsUntilCommitted() = runTest {
        val files = InMemoryPrivateFileStore(durable = true)
        files.appendPart("video-x.mp4", byteArrayOf(1, 2, 3, 4), 3)
        assertEquals(3L, files.partialSize("video-x.mp4"))
        assertEquals(null, files.size("video-x.mp4"))
        val buffer = ByteArray(8)
        assertEquals(3, files.readPart("video-x.mp4", 0, buffer))
        files.truncatePart("video-x.mp4", 2)
        assertEquals(2L, files.partialSize("video-x.mp4"))
        files.commitPart("video-x.mp4")
        assertEquals(2L, files.size("video-x.mp4"))
        assertEquals(0L, files.partialSize("video-x.mp4"))
        assertEquals(listOf(StoredFileInfo("video-x.mp4", 2, false)), files.list())
    }
}
