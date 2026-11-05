package pe.bpm.features.alerts

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.alerts.data.*
import pe.bpm.features.alerts.domain.*

class RemoteAlertsRepositoryTest {
  private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
  private val headers = headersOf(HttpHeaders.ContentType, "application/json")

  private fun alert(
    status: String = "PENDING",
    viewed: String = "null",
    attended: String = "null",
    actualId: String = id,
  ) =
    """{"id":"$actualId","type":"TRAINING_ASSIGNED","title":"Curso asignado","message":"Revisa tu capacitación","status":"$status","createdAt":"2026-11-01T00:00:00Z","viewedAt":$viewed,"attendedAt":$attended,"locationId":null,"locationName":"","sourceType":"TRAINING_ASSIGNMENT","sourceId":"$id","sourceAvailable":true}"""

  @Test
  fun newInactivityRuleAcceptsConfirmedVersionOne() = runTest {
    val form =
      AlertRuleForm(
        id,
        AlertType.ORGANIZATION_INACTIVE,
        values = mapOf("name" to "Prueba confirmación", "inactivityDays" to "3"),
      )
    val client =
      createHttpClient(
        MockEngine {
          respond(
            """{"data":{"active":true,"configuration":{"inactivityDays":3},"emailEnabled":false,"id":"$id","locationId":null,"locationName":"","name":"Prueba confirmación","pushEnabled":false,"rowVersion":1,"type":"ORGANIZATION_INACTIVE"}}""",
            HttpStatusCode.Created,
            headers,
          )
        }
      )
    try {
      val repo = RemoteAlertsRepository(client, AppConfig("https://api.example")) { "token" }
      assertIs<AlertsResult.Success<AlertRule>>(repo.submit("org", form, repo.prepare(form, id)))
    } finally {
      client.close()
    }
  }

  @Test
  fun privateInboxUsesFilterPaginationAndSessionAndNeverMakesFailedCountZero() = runTest {
    val client =
      createHttpClient(
        MockEngine { request ->
          assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
          if (request.url.encodedPath.endsWith("unread-count"))
            respond("{}", HttpStatusCode.ServiceUnavailable, headers)
          else {
            assertEquals("UNREAD", request.url.parameters["state"])
            assertEquals("curso", request.url.parameters["query"])
            assertEquals("25", request.url.parameters["pageSize"])
            respond(
              """{"data":[${alert()}],"pagination":{"nextPageToken":"next"}}""",
              HttpStatusCode.OK,
              headers,
            )
          }
        }
      )
    try {
      val repo = RemoteAlertsRepository(client, AppConfig("https://api.example")) { "token" }
      val page =
        assertIs<AlertsResult.Success<*>>(
            repo.inbox("org", AlertFilter.UNREAD, PageRequest(query = " curso "))
          )
          .value as pe.bpm.core.domain.Page<*>
      assertEquals("next", page.nextToken)
      assertIs<AlertsResult.Failure>(repo.unreadCount("org"))
    } finally {
      client.close()
    }
  }

  @Test
  fun acknowledgmentRequiresMatchingConfirmedTimestamps() = runTest {
    var mode = 0
    val client =
      createHttpClient(
        MockEngine { request ->
          assertEquals(HttpMethod.Post, request.method)
          assertEquals("{}", (request.body as TextContent).text)
          assertNull(request.headers["Idempotency-Key"])
          val body =
            when (mode) {
              0 -> alert()
              1 -> alert("VIEWED", "\"2026-11-01T00:01:00Z\"")
              else -> alert("ATTENDED", "\"2026-11-01T00:01:00Z\"", "\"2026-11-01T00:02:00Z\"")
            }
          respond("""{"data":$body}""", HttpStatusCode.OK, headers)
        }
      )
    try {
      val repo = RemoteAlertsRepository(client, AppConfig("https://api.example")) { "token" }
      assertEquals(
        AlertsFailure.INVALID_RESPONSE,
        assertIs<AlertsResult.Failure>(repo.view("org", id)).reason,
      )
      mode = 1
      assertNotNull(assertIs<AlertsResult.Success<Alert>>(repo.view("org", id)).value.viewedAt)
      mode = 2
      assertNotNull(assertIs<AlertsResult.Success<Alert>>(repo.attend("org", id)).value.attendedAt)
    } finally {
      client.close()
    }
  }

  @Test
  fun updateSendsImmutableScopeVersionAndStableIdempotencyBody() = runTest {
    val original =
      AlertRule(
        id,
        null,
        "",
        "Curso asignado",
        AlertType.TRAINING_ASSIGNED,
        AlertConfiguration(),
        true,
        false,
        false,
        7,
      )
    val form = AlertRuleForm.from(original).copy(active = false, pushEnabled = true)
    val client =
      createHttpClient(
        MockEngine { request ->
          assertEquals(HttpMethod.Put, request.method)
          assertEquals(id, request.headers["Idempotency-Key"])
          val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
          assertEquals(7, body.getValue("rowVersion").jsonPrimitive.int)
          assertEquals(JsonNull, body["locationId"])
          assertEquals("TRAINING_ASSIGNED", body.getValue("type").jsonPrimitive.content)
          assertEquals(false, body.getValue("active").jsonPrimitive.boolean)
          assertEquals(emptySet(), body.getValue("configuration").jsonObject.keys)
          respond(
            """{"data":{"id":"$id","locationId":null,"locationName":"","name":"Curso asignado","type":"TRAINING_ASSIGNED","configuration":{},"active":false,"emailEnabled":false,"pushEnabled":true,"rowVersion":8}}""",
            HttpStatusCode.OK,
            headers,
          )
        }
      )
    try {
      val repo = RemoteAlertsRepository(client, AppConfig("https://api.example")) { "token" }
      assertIs<AlertsResult.Success<AlertRule>>(repo.submit("org", form, repo.prepare(form, id)))
    } finally {
      client.close()
    }
  }

  @Test
  fun malformedAndCrossIdResponsesAreNotPublished() = runTest {
    val client =
      createHttpClient(
        MockEngine { request ->
          if (request.url.encodedPath.endsWith("unread-count"))
            respond("""{"count":-1}""", HttpStatusCode.OK, headers)
          else
            respond(
              """{"data":${alert(actualId="49d000f4-e487-41cf-a166-741c98cd7e05")}}""",
              HttpStatusCode.OK,
              headers,
            )
        }
      )
    try {
      val repo = RemoteAlertsRepository(client, AppConfig("https://api.example")) { "token" }
      assertEquals(
        AlertsFailure.INVALID_RESPONSE,
        assertIs<AlertsResult.Failure>(repo.detail("org", id)).reason,
      )
      assertEquals(
        AlertsFailure.INVALID_RESPONSE,
        assertIs<AlertsResult.Failure>(repo.unreadCount("org")).reason,
      )
    } finally {
      client.close()
    }
  }
}
