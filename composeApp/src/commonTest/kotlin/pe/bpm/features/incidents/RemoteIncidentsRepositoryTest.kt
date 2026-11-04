package pe.bpm.features.incidents

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.incidents.data.*
import pe.bpm.features.incidents.domain.*

class RemoteIncidentsRepositoryTest {
    private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
    private val other = "49d000f4-e487-41cf-a166-741c98cd7e05"
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")

    private fun detail(site: String = id, due: String = "2026-11-04T15:00:00.123456Z") =
        """{"id":"$id","locationId":"$site","catalogId":"$id","name":"Criterio","area":"Área","riskLevel":"HIGH","status":"ASSIGNED","description":"Problema","reportedByName":"Persona","createdAt":"2026-11-03T00:00:00Z","rowVersion":2,"reportedBy":"reporter","catalogVersion":1,"normativeCriterion":"Norma de la organización","evidenceRequired":true,"actions":[{"id":"$other","responsibleUserId":"worker","responsibleName":"Responsable","description":"Corregir","dueAt":"$due","status":"PENDING","resolutionNote":null}],"allowedActions":[],"beforeCount":1,"afterCount":0}"""

    @Test
    fun assignmentSerializesOnlyAllowedFieldsAndAcceptsMicrosecondPrecision() = runTest {
        var body = ""
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(
                        "/api/v1/organizations/org/incidents/$id/actions",
                        request.url.encodedPath,
                    )
                    assertEquals("key-12345678", request.headers["Idempotency-Key"])
                    body = (request.body as TextContent).text
                    assertEquals(
                        setOf(
                            "operationId",
                            "rowVersion",
                            "actionType",
                            "actionId",
                            "responsibleUserId",
                            "description",
                            "dueAt",
                        ),
                        Json.parseToJsonElement(body).jsonObject.keys,
                    )
                    respond("""{"data":${detail()}}""", HttpStatusCode.OK, headers)
                }
            )
        try {
            val repo =
                RemoteIncidentsRepository(client, AppConfig("https://api.example")) { "token" }
            val form =
                IncidentForm(
                    id,
                    "2026-11-03T00:00:00Z",
                    "ASSIGN",
                    mapOf(
                        "actionId" to other,
                        "responsibleUserId" to "worker",
                        "description" to "Corregir",
                        "dueAt" to "2026-11-04T15:00:00.123456789Z",
                        "note" to "exclude",
                    ),
                    id,
                )
            val pending = repo.prepare(form, id, "key-12345678")
            assertIs<IncidentsResult.Success<IncidentSaved>>(repo.submit("org", id, form, pending))
            assertEquals(pending.body, body)
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsForeignSiteOrAlteredResponseDeadline() = runTest {
        var mode = 0
        val client =
            createHttpClient(
                MockEngine {
                    respond(
                        """{"data":${if (mode == 0) detail(site = other) else detail(due = "2026-11-04T15:00:00.123457Z")}}""",
                        HttpStatusCode.OK,
                        headers,
                    )
                }
            )
        try {
            val repo =
                RemoteIncidentsRepository(client, AppConfig("https://api.example")) { "token" }
            val form =
                IncidentForm(
                    id,
                    "2026-11-03T00:00:00Z",
                    "ASSIGN",
                    mapOf(
                        "actionId" to other,
                        "responsibleUserId" to "worker",
                        "description" to "Corregir",
                        "dueAt" to "2026-11-04T15:00:00.123456Z",
                    ),
                    id,
                )
            val pending = repo.prepare(form, id, "key-12345678")
            assertEquals(
                IncidentsFailure.INVALID_RESPONSE,
                assertIs<IncidentsResult.Failure>(repo.submit("org", id, form, pending)).reason,
            )
            mode = 1
            assertEquals(
                IncidentsFailure.INVALID_RESPONSE,
                assertIs<IncidentsResult.Failure>(repo.submit("org", id, form, pending)).reason,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun listsUseSiteServerStatusSearchAndOpaqueCursor() = runTest {
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals(id, request.url.parameters["locationId"])
                    assertEquals("RESOLVED", request.url.parameters["status"])
                    assertEquals("cursor", request.url.parameters["pageToken"])
                    assertEquals("buscar", request.url.parameters["query"])
                    respond(
                        """{"data":[],"pagination":{"nextPageToken":"next"}}""",
                        HttpStatusCode.OK,
                        headers,
                    )
                }
            )
        try {
            val repo =
                RemoteIncidentsRepository(client, AppConfig("https://api.example")) { "token" }
            assertEquals(
                "next",
                assertIs<IncidentsResult.Success<*>>(
                        repo.incidents(
                            "org",
                            id,
                            IncidentStatus.RESOLVED,
                            PageRequest("cursor", " buscar "),
                        )
                    )
                    .value
                    .let { (it as pe.bpm.core.domain.Page<*>).nextToken },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun deniedIsNotAnEmptyListAndPostNeverChangesPendingBody() = runTest {
        var calls = 0
        val client =
            createHttpClient(
                MockEngine {
                    calls++
                    respond("{}", HttpStatusCode.Forbidden, headers)
                }
            )
        try {
            val repo =
                RemoteIncidentsRepository(client, AppConfig("https://api.example")) { "token" }
            assertEquals(
                IncidentsFailure.DENIED,
                assertIs<IncidentsResult.Failure>(repo.incidents("org", id, null, PageRequest()))
                    .reason,
            )
            val form =
                IncidentForm(
                    id,
                    "2026-11-03T00:00:00Z",
                    "REPORT",
                    mapOf("catalogId" to id, "description" to "Problema"),
                )
            val pending = repo.prepare(form, id, "key-12345678")
            assertEquals(
                IncidentsFailure.INVALID_RESPONSE,
                assertIs<IncidentsResult.Failure>(
                        repo.submit(
                            "org",
                            id,
                            form.copy(values = form.values + ("description" to "Modificado")),
                            pending,
                        )
                    )
                    .reason,
            )
            assertEquals(1, calls)
        } finally {
            client.close()
        }
    }
}
