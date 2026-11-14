package pe.bpm.core.offline

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pe.bpm.core.network.createHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ORIGIN = "https://api.test"
private const val ORG = "$ORIGIN/api/v1/organizations/org1"
private val json = headersOf(HttpHeaders.ContentType, "application/json")

class OfflineHttpTest {
    private class Rig(online: Boolean = true) {
        val h = OfflineHarness(connectivity = ManualConnectivityMonitor(online))
        var failNetwork = false
        val requests = mutableListOf<String>()
        var status = HttpStatusCode.OK
        var body = """{"data":[1]}"""
        val delegate = MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            if (failNetwork) throw kotlinx.io.IOException("sin red")
            respond(body, status, json)
        }
        val client: HttpClient = createHttpClient(OfflineHttpEngine(delegate, h.runtime.http, ORIGIN))
        // Cliente de reenvío: sin la capa offline y con token vigente.
        val handlerSeen = mutableListOf<Pair<String, String?>>()
        val raw = createHttpClient(MockEngine { request ->
            handlerSeen += request.url.encodedPath to request.headers["Authorization"]
            respond("{}", status, json)
        })
        suspend fun login(user: String = "user-1") = h.login(user)
    }

    @Test fun onlineReadsAreStoredAndServedWithoutNetwork() = runTest {
        val rig = Rig(); rig.login()
        assertEquals("""{"data":[1]}""", rig.client.get("$ORG/products?page=1") { header("Authorization", "Bearer t") }.bodyAsText())
        rig.h.connectivity.set(false)
        rig.requests.clear()
        val offline = rig.client.get("$ORG/products?page=1")
        assertEquals("""{"data":[1]}""", offline.bodyAsText())
        assertTrue(offline.headers[OFFLINE_CACHE_HEADER] != null)
        assertTrue(rig.requests.isEmpty())
        assertTrue(rig.h.runtime.http.staleSince.value != null)
    }

    @Test fun networkFailureFallsBackToCacheAndRecoveryClearsStaleness() = runTest {
        val rig = Rig(); rig.login()
        rig.client.get("$ORG/alerts")
        rig.failNetwork = true
        assertEquals("""{"data":[1]}""", rig.client.get("$ORG/alerts").bodyAsText())
        assertTrue(rig.h.runtime.http.staleSince.value != null)
        rig.failNetwork = false
        rig.client.get("$ORG/alerts")
        assertNull(rig.h.runtime.http.staleSince.value)
    }

    @Test fun uncachedResourceFailsWhenOfflineInsteadOfInventingData() = runTest {
        val rig = Rig(online = false); rig.login()
        rig.failNetwork = true
        assertFailsWith<Throwable> { rig.client.get("$ORG/alerts") }
    }

    @Test fun cacheIsScopedPerUserAndNotStoredWithoutSession() = runTest {
        val rig = Rig(); rig.login("user-1")
        rig.client.get("$ORG/alerts")
        rig.h.runtime.activate(OfflineScope(ORIGIN, "user-2"))
        rig.failNetwork = true
        assertFailsWith<Throwable> { rig.client.get("$ORG/alerts") }
    }

    @Test fun nonAllowlistedAndErrorResponsesAreNotCached() = runTest {
        val rig = Rig(); rig.login()
        rig.client.get("$ORG/reports/summary")
        rig.status = HttpStatusCode.InternalServerError
        rig.client.get("$ORG/products")
        assertEquals(0, rig.h.runtime.store.list(OfflineHttpEngine.CACHE_COLLECTION).size)
    }

    @Test fun offlineWriteIsQueuedWithoutTokenAndSurfacesAsConnectionFailure() = runTest {
        val rig = Rig(online = false); rig.login()
        val error = assertFailsWith<OfflineQueuedException> {
            rig.client.post("$ORG/cleaning-executions") {
                header("Authorization", "Bearer secreto-123"); header("Idempotency-Key", "key-clean-0001")
                contentType(ContentType.Application.Json); setBody("""{"id":"e1","note":"ok"}""")
            }
        }
        val op = rig.h.runtime.outbox.snapshot().single()
        assertEquals(error.operationId, op.id)
        assertEquals("key-clean-0001", op.idempotencyKey); assertEquals("e1", op.entityId); assertEquals("org1", op.organizationId)
        assertFalse(op.payload.contains("secreto")); assertFalse(op.payload.contains("Bearer"))
        assertTrue(rig.requests.isEmpty())
        // El mismo intento repetido (usuario pulsa de nuevo) no duplica la operación.
        assertFailsWith<OfflineQueuedException> {
            rig.client.post("$ORG/cleaning-executions") {
                header("Idempotency-Key", "key-clean-0001"); contentType(ContentType.Application.Json); setBody("""{"id":"e1","note":"ok"}""")
            }
        }
        assertEquals(1, rig.h.runtime.outbox.snapshot().size)
    }

    @Test fun onlineWriteThatLosesConnectionIsQueuedAndLaterOperationsKeepOrder() = runTest {
        val rig = Rig(); rig.login()
        rig.failNetwork = true
        assertFailsWith<OfflineQueuedException> {
            rig.client.put("$ORG/checklist-runs/run1/answers") {
                header("Idempotency-Key", "key-save-0001"); contentType(ContentType.Application.Json); setBody("""{"rowVersion":1}""")
            }
        }
        rig.failNetwork = false
        rig.requests.clear()
        // Aunque haya red, el envío del mismo registro espera su operación previa.
        assertFailsWith<OfflineQueuedException> {
            rig.client.post("$ORG/checklist-runs/run1/submit") {
                header("Idempotency-Key", "key-submit-001"); contentType(ContentType.Application.Json); setBody("""{"rowVersion":2}""")
            }
        }
        assertTrue(rig.requests.isEmpty())
        val ops = rig.h.runtime.outbox.snapshot()
        assertEquals(listOf("key-save-0001", "key-submit-001"), ops.map { it.idempotencyKey })
        assertEquals(listOf(ops[0].id), ops[1].dependsOn)
    }

    @Test fun successfulOnlineWritePassesThroughAndInvalidatesRelatedCache() = runTest {
        val rig = Rig(); rig.login()
        rig.client.get("$ORG/incidents"); rig.client.get("$ORG/alerts")
        val response = rig.client.post("$ORG/incidents") {
            header("Idempotency-Key", "key-inc-00001"); contentType(ContentType.Application.Json); setBody("""{"id":"i1"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("/api/v1/organizations/org1/alerts"), rig.h.runtime.store.list(OfflineHttpEngine.CACHE_COLLECTION).map { it.key })
        assertEquals(0, rig.h.runtime.outbox.snapshot().size)
    }

    @Test fun adminAndEvidenceWritesAreNeverQueued() = runTest {
        val rig = Rig(online = false); rig.login()
        rig.failNetwork = true
        for (path in listOf("$ORG/members", "$ORG/incidents/i1/evidence/BEFORE", "$ORG/alerts/rules", "$ORG/incident-catalog")) {
            assertFailsWith<kotlinx.io.IOException> {
                rig.client.post(path) { header("Idempotency-Key", "key-admin-0001"); contentType(ContentType.Application.Json); setBody("{}") }
            }
        }
        assertEquals(0, rig.h.runtime.outbox.snapshot().size)
    }

    @Test fun alertViewWithoutKeyUsesDeterministicKey() = runTest {
        val rig = Rig(online = false); rig.login()
        repeat(2) {
            assertFailsWith<OfflineQueuedException> { rig.client.post("$ORG/alerts/a1/view") }
        }
        val op = rig.h.runtime.outbox.snapshot().single()
        assertEquals("alert-a1-view", op.idempotencyKey)
    }

    @Test fun handlerReplaysWithFreshTokenAndClassifiesResponses() = runTest {
        val rig = Rig(online = false); rig.login()
        assertFailsWith<OfflineQueuedException> {
            rig.client.post("$ORG/stock-movements") {
                header("Idempotency-Key", "key-stock-0001"); contentType(ContentType.Application.Json); setBody("""{"id":"m1","quantity":"2"}""")
            }
        }
        val handler = HttpOutboxHandler(rig.raw, ORIGIN, { "fresh-token" })
        val op = rig.h.runtime.outbox.snapshot().single()
        suspend fun outcomeFor(status: HttpStatusCode): SendOutcome { rig.status = status; return handler.send(op) }
        assertTrue(outcomeFor(HttpStatusCode.Created) is SendOutcome.Sent)
        assertEquals("Bearer fresh-token", rig.handlerSeen.single().second)
        assertTrue(outcomeFor(HttpStatusCode.Conflict) is SendOutcome.Conflict)
        assertTrue(outcomeFor(HttpStatusCode.UnprocessableEntity) is SendOutcome.Rejected)
        assertTrue(outcomeFor(HttpStatusCode.Forbidden) is SendOutcome.Rejected)
        assertTrue(outcomeFor(HttpStatusCode.NotFound) is SendOutcome.Rejected)
        assertTrue(outcomeFor(HttpStatusCode.TooManyRequests) is SendOutcome.Retry)
        assertTrue(outcomeFor(HttpStatusCode.ServiceUnavailable) is SendOutcome.Retry)
        val unauthorized = outcomeFor(HttpStatusCode.Unauthorized) as SendOutcome.Retry
        assertFalse(unauthorized.countAttempt)
    }

    @Test fun handlerRejectsTamperedPathsAndMissingTokenWaits() = runTest {
        val rig = Rig(); rig.login()
        val bad = OutboxOperation("x", "HTTP", "o", "", "key-bad-000001", 1,
            """{"method":"POST","path":"/api/v1/../admin","body":"{}"}""")
        assertTrue(HttpOutboxHandler(rig.raw, ORIGIN, { "t" }).send(bad) is SendOutcome.Rejected)
        val ok = bad.copy(payload = """{"method":"POST","path":"/api/v1/organizations/o/alerts/a1/view"}""")
        val waiting = HttpOutboxHandler(rig.raw, ORIGIN, { null }).send(ok) as SendOutcome.Retry
        assertFalse(waiting.countAttempt)
        val offHost = HttpOutboxHandler(MockEngineThatThrows.client(), ORIGIN, { "t" }).send(ok) as SendOutcome.Retry
        assertTrue(offHost.network)
    }

    /** Fin a fin: lo escrito sin red se envía al volver, una sola vez, con la misma clave. */
    @Test fun queuedWritesAreDeliveredOnceWhenNetworkReturns() = runTest {
        val rig = Rig(online = false); rig.login()
        repeat(5) { n ->
            assertFailsWith<OfflineQueuedException> {
                rig.client.post("$ORG/supply-receipts") {
                    header("Idempotency-Key", "key-recv-000$n"); contentType(ContentType.Application.Json); setBody("""{"id":"r$n"}""")
                }
            }
        }
        rig.h.runtime.register("HTTP", HttpOutboxHandler(rig.raw, ORIGIN, { "fresh" }, store = rig.h.runtime.store))
        rig.h.connectivity.set(true)
        val first = rig.h.runtime.syncNow()
        rig.h.runtime.syncNow()
        assertEquals(5, first.sent)
        assertEquals(5, rig.handlerSeen.size)
        assertTrue(rig.h.runtime.outbox.snapshot().all { it.status == OutboxStatus.SENT })
    }
}

private object MockEngineThatThrows {
    fun client() = createHttpClient(MockEngine { throw kotlinx.io.IOException("sin red") })
}
