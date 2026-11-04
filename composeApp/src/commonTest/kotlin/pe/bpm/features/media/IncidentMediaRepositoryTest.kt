package pe.bpm.features.media

import io.ktor.client.HttpClient
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
import pe.bpm.features.media.presentation.MediaEvidenceState

class IncidentMediaRepositoryTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val photo =
        PickedPhoto(
            "foto.jpg",
            "image/jpeg",
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()),
        )

    private fun file(
        incident: String = "incident",
        action: String? = "action",
        stage: String = "AFTER",
    ) =
        """{"id":"file","runId":"","itemId":"","incidentId":"$incident","actionId":${action?.let { "\"$it\"" } ?: "null"},"stage":"$stage","originalName":"foto.jpg","mimeType":"image/jpeg","sizeBytes":4,"status":"AVAILABLE","createdAt":"2026-11-01T00:00:00Z","uploadedAt":"2026-11-01T00:01:00Z"}"""

    @Test
    fun readsFourLegacyBeforePhotosWithoutAllowingAnotherUpload() = runTest {
        val target = EvidenceContext("org", incidentId = "incident", stage = EvidenceStage.BEFORE)
        val files =
            (1..4).joinToString(",") { index ->
                file(action = null, stage = "BEFORE")
                    .replace("\"id\":\"file\"", "\"id\":\"file-$index\"")
            }
        val api =
            createHttpClient(
                MockEngine {
                    respond(
                        """{"data":{"files":[$files],"maxBytes":100,"allowedMimeTypes":["image/jpeg"],"maxFiles":3,"uploadAvailable":true}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            )
        val objects = HttpClient(MockEngine { fail("La lectura de metadatos no usa objetos") })
        try {
            val repo =
                RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            val listing = assertIs<MediaResult.Success<EvidenceListing>>(repo.list(target)).value
            assertEquals(4, listing.files.size)
            assertEquals(3, listing.policy.maxFiles)
            val state =
                MediaEvidenceState(
                    userId = "user",
                    organizationId = "org",
                    incidentId = "incident",
                    stage = EvidenceStage.BEFORE,
                    canEdit = true,
                    files = listing.files,
                    policy = listing.policy,
                )
            assertEquals(4, state.confirmedCount)
            assertFalse(state.canSelect)
        } finally {
            api.close()
            objects.close()
        }
    }

    @Test
    fun routesAndUploadMetadataUseExplicitIncidentTargetAndConfirmFirstRetry() = runTest {
        val target =
            EvidenceContext(
                "org",
                incidentId = "incident",
                actionId = "action",
                stage = EvidenceStage.AFTER,
            )
        val calls = mutableListOf<String>()
        val api =
            createHttpClient(
                MockEngine { request ->
                    calls += request.url.encodedPath
                    val path = request.url.encodedPath
                    when {
                        path.endsWith("evidence/AFTER") ->
                            respond(
                                """{"data":{"files":[${file()}],"maxBytes":100,"allowedMimeTypes":["image/jpeg"],"maxFiles":3,"uploadAvailable":true}}""",
                                HttpStatusCode.OK,
                                jsonHeaders,
                            )
                        path.endsWith("uploads") -> {
                            val body =
                                Json.parseToJsonElement((request.body as TextContent).text)
                                    .jsonObject
                            assertEquals(
                                setOf(
                                    "id",
                                    "incidentId",
                                    "actionId",
                                    "stage",
                                    "originalName",
                                    "mimeType",
                                    "sizeBytes",
                                    "sha256",
                                ),
                                body.keys,
                            )
                            assertEquals(
                                "incident",
                                body.getValue("incidentId").jsonPrimitive.content,
                            )
                            assertEquals("AFTER", body.getValue("stage").jsonPrimitive.content)
                            respond(
                                """{"data":{"file":${file()}}}""",
                                HttpStatusCode.OK,
                                jsonHeaders,
                            )
                        }
                        else -> respond("""{"data":${file()}}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            )
        val objects = HttpClient(MockEngine { fail("No debe cargar objetos ya confirmados") })
        try {
            val repo =
                RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            assertIs<MediaResult.Success<EvidenceListing>>(repo.list(target))
            assertTrue(calls.single().endsWith("incidents/incident/actions/action/evidence/AFTER"))
            assertIs<MediaResult.Success<EvidenceFile>>(
                repo.upload(target, EvidenceUpload("file", photo)) {}
            )
            assertIs<MediaResult.Success<EvidenceFile>>(
                repo.upload(target, EvidenceUpload("file", photo, true)) {}
            )
            assertTrue(calls.last().endsWith("media/file/confirm"))
            assertEquals(3, calls.size)
        } finally {
            api.close()
            objects.close()
        }
    }

    @Test
    fun rejectsWrongIncidentActionAndStageAndDenialIsNotEmpty() = runTest {
        val target =
            EvidenceContext(
                "org",
                incidentId = "incident",
                actionId = "action",
                stage = EvidenceStage.AFTER,
            )
        var mode = 0
        val api =
            createHttpClient(
                MockEngine {
                    if (mode == 3) respond("{}", HttpStatusCode.Forbidden, jsonHeaders)
                    else
                        respond(
                            """{"data":{"files":[${when (mode) { 0 -> file(incident = "other")
 1 -> file(action = "other")
 else -> file(stage = "BEFORE") }}],"maxBytes":100,"allowedMimeTypes":["image/jpeg"],"maxFiles":3,"uploadAvailable":false}}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                }
            )
        val objects = HttpClient(MockEngine { fail("No debe cargar objetos") })
        try {
            val repo =
                RemoteMediaRepository(api, objects, AppConfig("https://api.example")) { "token" }
            repeat(3) {
                mode = it
                assertEquals(
                    MediaFailure.INVALID_RESPONSE,
                    assertIs<MediaResult.Failure>(repo.list(target)).reason,
                )
            }
            mode = 3
            assertEquals(
                MediaFailure.DENIED,
                assertIs<MediaResult.Failure>(repo.list(target)).reason,
            )
        } finally {
            api.close()
            objects.close()
        }
    }
}
