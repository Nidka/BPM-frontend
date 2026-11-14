package pe.bpm.features.media

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.media.data.RemoteMediaRepository
import pe.bpm.features.media.domain.*

/** Fotos privadas de una ejecución de limpieza (`cleaningExecutionId`). */
class CleaningEvidenceTest {
    private val execution = "49d000f4-e487-41cf-a166-741c98cd7e04"
    private val mediaId = "59d000f4-e487-41cf-a166-741c98cd7e05"
    private val context = EvidenceContext("org", cleaningExecutionId = execution)
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val photo = PickedPhoto("limpieza.jpg", "image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))

    private fun file(status: String = "AVAILABLE", target: String = execution) =
        """{"id":"$mediaId","runId":"","itemId":"","cleaningExecutionId":"$target","originalName":"limpieza.jpg","mimeType":"image/jpeg","sizeBytes":4,"status":"$status","createdAt":"2026-11-10T00:00:00Z","uploadedAt":${if (status == "AVAILABLE") "\"2026-11-10T00:01:00Z\"" else "null"}}"""

    @Test
    fun cleaningContextExcludesOtherDestinations() {
        assertTrue(context.valid())
        assertFalse(context.copy(runId = "run", itemId = "item").valid())
        assertFalse(context.copy(incidentId = "incident", stage = EvidenceStage.BEFORE).valid())
        assertFalse(context.copy(cleaningExecutionId = "bad id").valid())
        assertTrue(EvidenceContext("org", "run", "item").valid())
        val stored = EvidenceFile(mediaId, "", "", "limpieza.jpg", "image/jpeg", 4, EvidenceStatus.AVAILABLE, "2026-11-10T00:00:00Z", cleaningExecutionId = execution)
        assertTrue(stored.belongsTo(context))
        assertFalse(stored.belongsTo(context.copy(cleaningExecutionId = "otra")))
        assertFalse(stored.copy(cleaningExecutionId = null).belongsTo(context))
    }

    @Test
    fun listsAndReservesAgainstTheCleaningRoute() = runTest {
        val paths = mutableListOf<String>()
        var reserved: JsonObject? = null
        val api =
            createHttpClient(
                MockEngine { request ->
                    paths += request.url.encodedPath
                    if (request.url.encodedPath.endsWith("uploads")) {
                        reserved = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        respond("""{"data":{"file":${file("AVAILABLE")},"upload":null}}""", HttpStatusCode.OK, jsonHeaders)
                    } else
                        respond(
                            """{"data":{"files":[${file()}],"maxBytes":1048576,"allowedMimeTypes":["image/jpeg","image/png"],"maxFiles":3,"uploadAvailable":true}}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                }
            )
        val repo = RemoteMediaRepository(api, api, AppConfig("https://example.test"), token = { "token" })
        val listing = (repo.list(context) as MediaResult.Success).value
        assertEquals("/api/v1/organizations/org/cleaning-executions/$execution/evidence", paths.single())
        assertEquals(execution, listing.files.single().cleaningExecutionId)
        assertEquals(3, listing.policy.maxFiles)
        val upload = repo.upload(context, EvidenceUpload(mediaId, photo)) {}
        assertIs<MediaResult.Success<EvidenceFile>>(upload)
        assertEquals(setOf("id", "cleaningExecutionId", "originalName", "mimeType", "sizeBytes", "sha256"), reserved!!.keys)
        assertEquals(execution, reserved!!.getValue("cleaningExecutionId").jsonPrimitive.content)
    }

    @Test
    fun filesOfAnotherExecutionAreRejected() = runTest {
        val api = createHttpClient(MockEngine {
            respond("""{"data":{"files":[${file(target = "69d000f4-e487-41cf-a166-741c98cd7e06")}],"maxBytes":1048576,"allowedMimeTypes":["image/jpeg"],"maxFiles":3,"uploadAvailable":true}}""", HttpStatusCode.OK, jsonHeaders)
        })
        val repo = RemoteMediaRepository(api, api, AppConfig("https://example.test"), token = { "token" })
        assertEquals(MediaResult.Failure(MediaFailure.INVALID_RESPONSE), repo.list(context))
    }

    @Test
    fun readOnlyAfterTheWindowAndDeniedUploadsStayVisibleAsFailures() = runTest {
        val api = createHttpClient(MockEngine {
            respond("""{"errors":[{"code":"ACCESS_DENIED"}]}""", HttpStatusCode.Forbidden, jsonHeaders)
        })
        val repo = RemoteMediaRepository(api, api, AppConfig("https://example.test"), token = { "token" })
        assertEquals(MediaResult.Failure(MediaFailure.DENIED), repo.upload(context, EvidenceUpload(mediaId, photo)) {})
    }
}
