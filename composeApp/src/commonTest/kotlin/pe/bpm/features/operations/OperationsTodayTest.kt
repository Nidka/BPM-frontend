package pe.bpm.features.operations

import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.storage.DraftStorage
import pe.bpm.features.operations.data.*
import pe.bpm.features.operations.domain.*
import pe.bpm.features.operations.presentation.*

/** Planes de limpieza con `view=today` (pestaña «Por hacer» del personal): datos del servidor y recarga del estado. */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationsTodayTest {
    private val site = "33333333-3333-4333-8333-333333333333"
    private val org = "22222222-2222-4222-8222-222222222222"
    private val planId = "11111111-1111-4111-8111-111111111111"
    private val shiftId = "44444444-4444-4444-8444-444444444444"
    private val executionId = "55555555-5555-4555-8555-555555555555"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun plan(today: String, id: String = planId) =
        """{"id":"$id","locationId":"$site","area":"Cocina","task":"Limpiar mesa","instructions":"","productName":"","frequency":"PER_SHIFT","dueTime":"10:00","active":true$today}"""

    private val todayJson = ""","today":{"periodStartsAt":"2026-11-20T05:00:00Z","periodEndsAt":"2026-11-21T05:00:00Z","timezone":"America/Lima","done":true,"doneByMe":true,
        "lastExecutionId":"$executionId","lastResult":"OBSERVED","lastExecutedAt":"2026-11-20T15:00:00Z","overdue":false,"shift":{"id":"$shiftId","name":"Mañana","state":"OPEN"}}"""

    private fun repository(client: HttpClient) = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }

    @Test
    fun todayPlansSendViewTodayAndMapTheServerState() = runTest {
        val urls = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url
            respond("""{"data":[${plan(todayJson)}],"pagination":{"nextPageToken":""}}""", headers = json)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val page = (repository(client).todayPlans(org, site, PageRequest()) as OperationsResult.Success).value
            val today = page.items.single().today!!
            assertTrue(today.done)
            assertTrue(today.doneByMe)
            assertEquals(CleaningResult.OBSERVED, today.lastResult)
            assertEquals(executionId, today.lastExecutionId)
            assertEquals("2026-11-20T05:00:00Z", today.periodStartsAt)
            assertEquals("America/Lima", today.timezone)
            assertEquals(CleaningPlanShift(shiftId, "Mañana", "OPEN"), today.shift)
            assertEquals("today", urls.single().parameters["view"])
            assertEquals(site, urls.single().parameters["locationId"])
            assertEquals("/api/v1/organizations/$org/cleaning-plans", urls.single().encodedPath)
        } finally { client.close() }
    }

    @Test
    fun theNormalPlanListSendsNoViewAndKeepsTodayEmpty() = runTest {
        val urls = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            urls += request.url
            respond("""{"data":[${plan("")}],"pagination":{"nextPageToken":""}}""", headers = json)
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val page = (repository(client).plans(org, site, PageRequest()) as OperationsResult.Success).value
            assertNull(page.items.single().today)
            assertNull(urls.single().parameters["view"])
            // `today: null` (PER_SHIFT sin turnos) también es válido.
            val client2 = HttpClient(MockEngine { respond("""{"data":[${plan(""","today":null""")}],"pagination":{"nextPageToken":""}}""", headers = json) }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            try { assertNull(((repository(client2).todayPlans(org, site, PageRequest()) as OperationsResult.Success).value).items.single().today) } finally { client2.close() }
        } finally { client.close() }
    }

    @Test
    fun anInvalidTodayObjectIsAnInvalidResponse() = runTest {
        val bad = ""","today":{"periodStartsAt":"ayer","periodEndsAt":"2026-11-21T05:00:00Z","timezone":"America/Lima","done":false,"doneByMe":false}"""
        val client = HttpClient(MockEngine { respond("""{"data":[${plan(bad)}],"pagination":{"nextPageToken":""}}""", headers = json) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        try {
            assertEquals(OperationsResult.Failure(OperationsFailure.INVALID_RESPONSE), repository(client).todayPlans(org, site, PageRequest()))
        } finally { client.close() }
    }

    private class Memory : DraftStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    @Test
    fun theViewModelLoadsAllTodayPagesIndependentlyOfTheList() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val second = "66666666-6666-4666-8666-666666666666"
        val seen = mutableListOf<Url>()
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                seen += request.url
                val body = when {
                    request.url.encodedPath.endsWith("workspace") -> """{"data":{"organization":{"id":"$org"},"role":"WORKER"}}"""
                    request.url.parameters["view"] == "today" && request.url.parameters["pageToken"] == null ->
                        """{"data":[${plan(todayJson)}],"pagination":{"nextPageToken":"page_2"}}"""
                    request.url.parameters["view"] == "today" -> """{"data":[${plan("", second)}],"pagination":{"nextPageToken":""}}"""
                    else -> """{"data":[],"pagination":{"nextPageToken":""}}"""
                }
                respond(body, headers = json)
            }
        })) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val repo = repository(client)
        val vm = OperationsViewModel(ManageOperations(repo, repo), ManageOperationsDraft(LocalOperationsDraftRepository(Memory(), "test")), { planId }, { "2026-11-20T10:00:00Z" })
        try {
            vm.open("user", org, "WORKER", site)
            advanceUntilIdle()
            assertFalse(vm.state.value.todayLoaded)
            vm.refreshToday()
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue(state.todayLoaded)
            assertNull(state.todayFailure)
            assertEquals(listOf(planId, second), state.todayPlans.map { it.id })
            assertTrue(state.todayPlans[0].today!!.done)
            assertNull(state.todayPlans[1].today)
            assertEquals(2, seen.count { it.parameters["view"] == "today" })
        } finally {
            vm.reset(); vm.viewModelScope.cancel(); client.close(); Dispatchers.resetMain()
        }
    }

    @Test
    fun todayPlansRequireReadPermissionAndASite() = runTest {
        val client = HttpClient(MockEngine { respond("""{"data":[],"pagination":{"nextPageToken":""}}""", headers = json) }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repo = repository(client)
        val manage = ManageOperations(repo, repo)
        try {
            assertEquals(OperationsFailure.DENIED, (manage.todayPlans(org, site, "UNKNOWN", PageRequest()) as OperationsResult.Failure).reason)
            assertEquals(OperationsFailure.INVALID, (manage.todayPlans(org, null, "WORKER", PageRequest()) as OperationsResult.Failure).reason)
            assertTrue(manage.todayPlans(org, site, "WORKER", PageRequest()) is OperationsResult.Success)
        } finally { client.close() }
    }
}
