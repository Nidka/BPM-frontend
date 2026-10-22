package pe.bpm.features.checklists

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.checklists.data.RemoteChecklistHistoryRepository
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.ChecklistHistoryViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistHistoryTest {
    private val summary = ChecklistRunSummary("run", "Limpieza", "site", "Lima", "chef", "Chef Pruebas", "DRAFT", "2026-10-19T12:00:00Z", answerCount = 0, questionCount = 1)
    @Test fun cursorAndFiltersGoToServerAndUnknownFieldsAreNotDomainData() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("team", request.url.parameters["scope"])
            assertEquals("DRAFT", request.url.parameters["status"])
            assertEquals("Lima", request.url.parameters["query"])
            assertEquals("Bearer token", request.headers["Authorization"])
            respond("""{"data":[{"id":"run","templateName":"Limpieza","locationId":"site","locationName":"Lima","performedBy":"chef","performedByName":"Chef Pruebas","status":"DRAFT","createdAt":"2026-10-19T12:00:00Z","answerCount":0,"questionCount":1}],"pagination":{"nextPageToken":"next"}}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        try {
            val repo = RemoteChecklistHistoryRepository(client, AppConfig("https://api.example"), token = { "token" })
            val result = repo.history("org", PageRequest(query = "Lima"), HistoryScope.Team, "DRAFT")
            assertEquals(ChecklistHistoryResult.Success(listOf(summary), "next"), result)
        } finally { client.close() }
    }
    @Test fun changingTenantCannotRestoreAnOldPageEvenIfTransportIgnoresCancellation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val deferred = CompletableDeferred<ChecklistHistoryResult>()
        val reader = object : ChecklistHistoryReader {
            override suspend fun history(organizationId: String, request: PageRequest, scope: HistoryScope, status: String): ChecklistHistoryResult =
                if (organizationId == "old") withContext(NonCancellable) { deferred.await() } else ChecklistHistoryResult.Success(emptyList(), "")
        }
        val vm = ChecklistHistoryViewModel(LoadChecklistHistory(reader), LoadChecklistRun(object : ChecklistRunReader {
            override suspend fun read(organizationId: String, id: String) = RunResult.Failure(RunFailure.NOT_FOUND)
        }))
        try {
            vm.open("chef", "old", HistoryScope.Mine); runCurrent()
            vm.open("chef", "new", HistoryScope.Mine); runCurrent()
            deferred.complete(ChecklistHistoryResult.Success(listOf(summary), "")); advanceUntilIdle()
            assertEquals("new", vm.state.value.organizationId)
            assertTrue(vm.state.value.items.isEmpty())
            assertFalse(vm.state.value.loading)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
