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
import pe.bpm.core.designsystem.BpmTone
import pe.bpm.core.storage.DraftStorage
import pe.bpm.features.operations.data.*
import pe.bpm.features.operations.domain.*
import pe.bpm.features.operations.presentation.*

/** Lista «Mis tareas» del personal: orden por hora límite, chips y formulario con plan preseleccionado. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerTasksTest {
    private val site = "33333333-3333-4333-8333-333333333333"
    private val org = "22222222-2222-4222-8222-222222222222"
    private val id = "11111111-1111-4111-8111-111111111111"

    private fun plan(name: String, dueTime: String, active: Boolean = true) =
        CleaningPlan(name, site, "Cocina", name, dueTime = dueTime, active = active)

    @Test
    fun plansAreSortedByDueTimeWithEmptyLast() {
        val sorted = sortPlansByDueTime(listOf(plan("d", ""), plan("c", "16:00"), plan("a", "09:30"), plan("e", ""), plan("b", "10:00")))
        assertEquals(listOf("a", "b", "c", "d", "e"), sorted.map { it.id })
        assertTrue(sortPlansByDueTime(emptyList()).isEmpty())
    }

    @Test
    fun dueChipTextAndToneFollowTheLocalTime() {
        assertEquals("hasta 10:00", dueChipText("10:00"))
        assertNull(dueChipText(""))
        assertEquals(BpmTone.Warning, dueTone("10:00", localMinutes = 10 * 60 + 1))
        assertEquals(BpmTone.Neutral, dueTone("10:00", localMinutes = 10 * 60))
        assertEquals(BpmTone.Neutral, dueTone("10:00", localMinutes = null))
        assertEquals(BpmTone.Neutral, dueTone("", localMinutes = 23 * 60))
        assertEquals(BpmTone.Neutral, dueTone("25:99", localMinutes = 23 * 60))
    }

    @Test
    fun resultTonesMatchTheOutcome() {
        assertEquals(BpmTone.Success, resultTone(CleaningResult.COMPLETED))
        assertEquals(BpmTone.Warning, resultTone(CleaningResult.OBSERVED))
        assertEquals(BpmTone.Danger, resultTone(CleaningResult.NOT_COMPLETED))
    }

    private class Memory : DraftStorage {
        val values = mutableMapOf<String, String>()
        override suspend fun read(key: String) = values[key]
        override suspend fun write(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }

    @Test
    fun newCleaningForOpensTheExecutionFormWithThePlanPreselected() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            val body =
                                if (request.url.encodedPath.endsWith("workspace"))
                                    "{\"data\":{\"organization\":{\"id\":\"$org\"},\"role\":\"WORKER\"}}"
                                else "{\"data\":[],\"pagination\":{\"nextPageToken\":\"\"}}"
                            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    }
                )
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val repo = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
        val vm =
            OperationsViewModel(
                ManageOperations(repo, repo),
                ManageOperationsDraft(LocalOperationsDraftRepository(Memory(), "test")),
                { id },
                { "2026-11-02T10:00:00Z" },
            )
        try {
            vm.open("user", org, "WORKER", site)
            advanceUntilIdle()
            vm.resource(OperationsResource.PLANS)
            advanceUntilIdle()
            assertEquals(OperationsResource.PLANS, vm.state.value.resource)
            // Un plan inactivo no abre el formulario.
            vm.newCleaningFor(plan("viejo", "08:00", active = false))
            advanceUntilIdle()
            assertNull(vm.state.value.form)
            val chosen = plan("Limpiar mesa", "10:00")
            vm.newCleaningFor(chosen)
            advanceUntilIdle()
            val state = vm.state.value
            assertEquals(OperationsResource.EXECUTIONS, state.resource)
            assertNotNull(state.form)
            assertEquals(chosen.id, state.form.values["cleaningPlanId"])
            assertEquals("Cocina · Limpiar mesa", state.form.values["planName"])
            assertEquals(chosen, state.selectedPlan)
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
