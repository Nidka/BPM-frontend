package pe.bpm.features.media

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.media.data.RemoteMediaRepository
import pe.bpm.features.media.data.photoSha256
import pe.bpm.features.media.domain.*
import kotlin.test.*

class RemoteMediaRepositoryTest {
    private val context = EvidenceContext("org", "run", "item")
    private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
    private val photo = PickedPhoto("foto.jpg", "image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun file(status: String = "AVAILABLE", item: String = "item") = """{"id":"$id","runId":"run","itemId":"$item","originalName":"foto.jpg","mimeType":"image/jpeg","sizeBytes":4,"status":"$status","createdAt":"2026-11-01T00:00:00Z","uploadedAt":${if(status == "AVAILABLE") "\"2026-11-01T00:01:00Z\"" else "null"}}"""
    private fun ticket(url: String = "https://account.r2.cloudflarestorage.com/bucket/photo?secret=neverlog") = """{"url":"$url","headers":{"Content-Type":["image/jpeg"],"If-None-Match":["*"]},"expiresAt":"2099-11-01T00:10:00Z"}"""

    @Test fun sha256UsesKnownStandardVector() = runTest {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", photoSha256("abc".encodeToByteArray()))
    }

    @Test fun reservationSerializesExactMetadataAndConfirmsAfterPreconditionFailure() = runTest {
        val calls = mutableListOf<String>()
        val api = createHttpClient(MockEngine { request ->
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            calls += request.url.encodedPath.substringAfterLast('/')
            if (request.url.encodedPath.endsWith("uploads")) {
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals(setOf("id", "runId", "itemId", "originalName", "mimeType", "sizeBytes", "sha256"), body.keys)
                assertEquals(id, body.getValue("id").jsonPrimitive.content)
                assertEquals("ba4f25bf16ba4be6bc7d3276fafeb67f9eb3c5df042bc3a405e1af15b921eed7", body.getValue("sha256").jsonPrimitive.content)
                respond("""{"data":{"file":${file("PENDING_UPLOAD")},"upload":${ticket()}}}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                assertEquals("{}", (request.body as TextContent).text)
                respond("""{"data":${file()}}""", HttpStatusCode.OK, jsonHeaders)
            }
        })
        val objects = HttpClient(MockEngine { request ->
            calls += "PUT"
            assertEquals(HttpMethod.Put, request.method)
            assertNull(request.headers[HttpHeaders.Authorization]); assertNull(request.headers[HttpHeaders.Cookie])
            assertEquals("*", request.headers[HttpHeaders.IfNoneMatch])
            respond("", HttpStatusCode.PreconditionFailed)
        }) { followRedirects = false }
        try {
            val repo = RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            assertIs<MediaResult.Success<EvidenceFile>>(repo.upload(context, EvidenceUpload(id, photo)) {})
            assertEquals(listOf("uploads", "PUT", "confirm"), calls)
        } finally { api.close(); objects.close() }
    }

    @Test fun retryConfirmsTheSameReservationBeforeAnyPut() = runTest {
        var puts = 0
        val api = createHttpClient(MockEngine { request ->
            assertTrue(request.url.encodedPath.endsWith("/$id/confirm"))
            respond("""{"data":${file()}}""", HttpStatusCode.OK, jsonHeaders)
        })
        val objects = HttpClient(MockEngine { puts++; respond("") })
        try {
            val repo = RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            assertIs<MediaResult.Success<EvidenceFile>>(repo.upload(context, EvidenceUpload(id, photo, confirmFirst = true)) {})
            assertEquals(0, puts)
        } finally { api.close(); objects.close() }
    }

    @Test fun rejectsCrossContextResponsesAndUntrustedSignedTargets() = runTest {
        var mode = 0
        var objectsCalled = false
        val api = createHttpClient(MockEngine {
            if (mode == 0) respond("""{"data":{"files":[${file(item="foreign")}],"maxBytes":100,"allowedMimeTypes":["image/jpeg"],"maxFiles":3,"uploadAvailable":true}}""", HttpStatusCode.OK, jsonHeaders)
            else respond("""{"data":{"file":${file("PENDING_UPLOAD")},"upload":${ticket("https://attacker.example/collect")}}}""", HttpStatusCode.OK, jsonHeaders)
        })
        val objects = HttpClient(MockEngine { objectsCalled = true; respond("") })
        try {
            val repo = RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            assertEquals(MediaFailure.INVALID_RESPONSE, assertIs<MediaResult.Failure>(repo.list(context)).reason)
            mode = 1
            assertEquals(MediaFailure.INVALID_RESPONSE, assertIs<MediaResult.Failure>(repo.upload(context, EvidenceUpload(id, photo)) {}).reason)
            assertFalse(objectsCalled)
        } finally { api.close(); objects.close() }
    }

    @Test fun uncertainPutIsConfirmedAndRetryNeverCreatesAnotherReservation() = runTest {
        val calls = mutableListOf<String>()
        var confirms = 0
        val api = createHttpClient(MockEngine { request ->
            calls += request.url.encodedPath.substringAfterLast('/')
            if (request.url.encodedPath.endsWith("uploads")) {
                respond("""{"data":{"file":${file("PENDING_UPLOAD")},"upload":${ticket()}}}""", HttpStatusCode.OK, jsonHeaders)
            } else {
                confirms++
                if (confirms == 1) respond("{}", HttpStatusCode.ServiceUnavailable, jsonHeaders)
                else respond("""{"data":${file()}}""", HttpStatusCode.OK, jsonHeaders)
            }
        })
        val objects = HttpClient(MockEngine { calls += "PUT"; throw IllegalStateException("respuesta perdida") }) { followRedirects = false }
        try {
            val repo = RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            val failed = assertIs<MediaResult.Failure>(repo.upload(context, EvidenceUpload(id, photo)) {})
            assertTrue(failed.mustConfirm)
            assertIs<MediaResult.Success<EvidenceFile>>(repo.upload(context, EvidenceUpload(id, photo, confirmFirst = true)) {})
            assertEquals(listOf("uploads", "PUT", "confirm", "confirm"), calls)
        } finally { api.close(); objects.close() }
    }

    @Test fun previewDoesNotFollowRedirectsOrAcceptMoreBytesThanTheConfirmedFile() = runTest {
        var redirect = true
        var objectCalls = 0
        val api = createHttpClient(MockEngine {
            respond("""{"data":{"url":"https://account.r2.cloudflarestorage.com/bucket/photo?secret=neverlog","headers":{},"expiresAt":"2099-11-01T00:10:00Z"}}""", HttpStatusCode.OK, jsonHeaders)
        })
        val objects = pe.bpm.features.media.data.createMediaObjectHttpClient(MockEngine { request ->
            objectCalls++
            assertNull(request.headers[HttpHeaders.Authorization]); assertNull(request.headers[HttpHeaders.Cookie])
            if (redirect) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://attacker.example/collect"))
            else respond(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00), HttpStatusCode.OK)
        })
        try {
            val repo = RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            val confirmed = EvidenceFile(id, "run", "item", "foto.jpg", "image/jpeg", 4, EvidenceStatus.AVAILABLE, "2026-11-01T00:00:00Z", "2026-11-01T00:01:00Z")
            assertEquals(MediaFailure.UNAVAILABLE, assertIs<MediaResult.Failure>(repo.preview(context, confirmed)).reason)
            assertEquals(1, objectCalls)
            redirect = false
            assertEquals(MediaFailure.INVALID_RESPONSE, assertIs<MediaResult.Failure>(repo.preview(context, confirmed)).reason)
            assertEquals(2, objectCalls)
        } finally { api.close(); objects.close() }
    }

    @Test fun unauthorizedResponseRejectsOnlyTheCurrentSessionToken() = runTest {
        var rejected: String? = null
        val api = createHttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized, jsonHeaders) })
        val objects = HttpClient(MockEngine { error("No debe tocar almacenamiento") })
        try {
            val repo = RemoteMediaRepository(api, objects, AppConfig("https://api.example"), onSessionRejected = { rejected = it }) { "token" }
            assertEquals(MediaFailure.SESSION, assertIs<MediaResult.Failure>(repo.list(context)).reason)
            assertEquals("token", rejected)
        } finally { api.close(); objects.close() }
    }
}
