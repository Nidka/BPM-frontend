package pe.bpm.features.operations

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.operations.data.RemoteShiftsRepository
import pe.bpm.features.operations.domain.*
import pe.bpm.features.operations.presentation.ShiftsViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class ShiftsTest {
    private val org = "22222222-2222-4222-8222-222222222222"
    private val site = "33333333-3333-4333-8333-333333333333"
    private val id = "44444444-4444-4444-8444-444444444444"

    private fun shift(
        shiftId: String = id,
        name: String = "Mañana",
        start: String = "06:00",
        end: String = "14:00",
        active: Boolean = true,
    ) = Shift(shiftId, site, name, start, end, "America/Lima", end < start, active, false, 1, "2026-11-10T15:00:00Z")

    @Test
    fun endIsExclusiveAndMidnightShiftsOverlapOnBothSides() {
        assertFalse(ShiftRules.overlaps("06:00", "14:00", "14:00", "22:00"))
        assertFalse(ShiftRules.overlaps("22:00", "06:00", "06:00", "14:00"))
        assertTrue(ShiftRules.overlaps("22:00", "06:00", "05:00", "07:00"))
        assertTrue(ShiftRules.overlaps("22:00", "06:00", "23:00", "23:30"))
        assertTrue(ShiftRules.overlaps("06:00", "14:00", "13:59", "22:00"))
        assertFalse(ShiftRules.overlaps("06:00", "06:00", "05:00", "07:00"))
        assertTrue(ShiftRules.crossesMidnight("22:00", "06:00"))
        assertFalse(ShiftRules.crossesMidnight("06:00", "14:00"))
    }

    @Test
    fun rulesRejectBadHoursDuplicatesAndOverlapsButIgnoreInactiveAndSelf() {
        assertEquals(setOf("name", "startTime", "endTime"), ShiftRules.errors("A", "6:00", "25:00").keys)
        assertEquals(setOf("endTime"), ShiftRules.errors("Tarde", "14:00", "14:00").keys)
        val others = listOf(shift(), shift("55555555-5555-4555-8555-555555555555", "Vieja", "14:00", "22:00", active = false))
        assertEquals(setOf("name"), ShiftRules.errors(" mañana ", "14:00", "22:00", others).keys)
        assertEquals(setOf("startTime"), ShiftRules.errors("Tarde", "13:00", "22:00", others).keys)
        // Un turno inactivo no bloquea y editar un turno no choca consigo mismo.
        assertTrue(ShiftRules.errors("Tarde", "14:00", "22:00", others).isEmpty())
        assertTrue(ShiftRules.errors("Mañana", "05:00", "13:00", others, ignoreId = id).isEmpty())
    }

    @Test
    fun onlyAdminsManageAndInvalidInputNeverReachesTransport() = runTest {
        val repo = FakeShifts()
        val manage = ManageShifts(repo)
        val input = ShiftInput(id, site, "Mañana", "06:00", "14:00")
        assertEquals(OperationsResult.Failure(OperationsFailure.DENIED), manage.create(org, "CHEF", input, emptyList(), id))
        assertEquals(OperationsResult.Failure(OperationsFailure.DENIED), manage.list(org, site, "WORKER", includeInactive = true))
        val invalid = manage.create(org, "ADMIN", input.copy(endTime = "99:99"), emptyList(), id) as OperationsResult.Failure
        assertEquals(OperationsFailure.INVALID, invalid.reason)
        assertEquals(0, repo.calls)
        repo.created += shift()
        assertIs<OperationsResult.Success<Shift>>(manage.create(org, "SUPER_ADMIN", input, emptyList(), id))
    }

    @Test
    fun deactivatingAShiftInUseIsADefinitiveRejectionAndKeepsTheShift() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo = FakeShifts().apply { listed += shift(); deactivation = OperationsResult.Failure(OperationsFailure.SHIFT_IN_USE) }
            val vm = ShiftsViewModel(ManageShifts(repo)) { "key-${repo.keys.size}-00000" }
            models.put("vm", vm)
            vm.open("admin", org, "ADMIN", site)
            advanceUntilIdle()
            assertEquals(1, vm.state.value.shifts.size)
            vm.requestDeactivate(vm.state.value.shifts.single())
            vm.confirmDeactivate()
            advanceUntilIdle()
            assertEquals(OperationsFailure.SHIFT_IN_USE, vm.state.value.failure)
            assertNull(vm.state.value.deactivating)
            assertFalse(vm.state.value.busy)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun uncertainSaveReusesTheSameKeyAndLocksTheForm() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo = FakeShifts()
            var counter = 0
            val vm = ShiftsViewModel(ManageShifts(repo)) { "00000000-0000-4000-8000-" + (counter++).toString().padStart(12, '0') }
            models.put("vm", vm)
            vm.open("admin", org, "ADMIN", site)
            advanceUntilIdle()
            vm.newShift()
            vm.change(name = "Mañana", start = "06:00", end = "14:00")
            repo.createResults += OperationsResult.Failure(OperationsFailure.CONNECTION)
            repo.createResults += OperationsResult.Success(shift())
            vm.save()
            advanceUntilIdle()
            assertTrue(vm.state.value.editor!!.uncertain)
            vm.change(name = "Otro")
            assertEquals("Mañana", vm.state.value.editor!!.name)
            vm.save()
            advanceUntilIdle()
            assertEquals(2, repo.keys.size)
            assertEquals(repo.keys[0], repo.keys[1])
            assertNull(vm.state.value.editor)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun nonAdminCannotOpenTheEditor() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val vm = ShiftsViewModel(ManageShifts(FakeShifts())) { id }
            models.put("vm", vm)
            vm.open("chef", org, "CHEF", site)
            advanceUntilIdle()
            vm.newShift()
            assertNull(vm.state.value.editor)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun remoteRepositorySendsStrictBodiesAndMapsShiftInUse() = runTest {
        val bodies = mutableListOf<JsonObject>()
        var status = HttpStatusCode.OK
        var payload = ""
        val client =
            createHttpClient(
                MockEngine { request ->
                    assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
                    if (request.method != HttpMethod.Get) {
                        assertEquals("operation-key", request.headers["Idempotency-Key"])
                        bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    } else assertEquals("ALL", request.url.parameters["status"])
                    respond(payload, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
        val repo = RemoteShiftsRepository(client, AppConfig("https://example.test")) { "token" }
        val json = """{"id":"$id","locationId":"$site","name":"Mañana","startTime":"06:00","endTime":"14:00","timezone":"America/Lima","crossesMidnight":false,"active":true,"openNow":true,"rowVersion":1,"createdAt":"2026-11-10T15:00:00Z"}"""
        payload = """{"data":$json}"""
        val created = repo.create(org, ShiftInput(id, site, "Mañana", "06:00", "14:00"), "operation-key")
        assertIs<OperationsResult.Success<Shift>>(created)
        assertEquals(setOf("id", "locationId", "name", "startTime", "endTime"), bodies.single().keys)
        payload = """{"data":${json.replace("\"rowVersion\":1", "\"rowVersion\":2")}}"""
        assertIs<OperationsResult.Success<Shift>>(repo.update(org, id, ShiftChange("Mañana", "06:00", "14:00", 1), "operation-key"))
        assertEquals(setOf("name", "startTime", "endTime", "rowVersion"), bodies.last().keys)
        // Una respuesta con otro nombre no confirma el alta.
        payload = """{"data":${json.replace("Mañana", "Otro turno")}}"""
        assertEquals(
            OperationsFailure.INVALID_RESPONSE,
            (repo.create(org, ShiftInput(id, site, "Mañana", "06:00", "14:00"), "operation-key") as OperationsResult.Failure).reason,
        )
        status = HttpStatusCode.Conflict
        payload = """{"errors":[{"code":"SHIFT_IN_USE","message":"detalle interno"}]}"""
        assertEquals(OperationsFailure.SHIFT_IN_USE, (repo.deactivate(org, id, 1, "operation-key") as OperationsResult.Failure).reason)
        payload = """{"errors":[{"code":"RESOURCE_CONFLICT"}]}"""
        assertEquals(OperationsFailure.CONFLICT, (repo.deactivate(org, id, 1, "operation-key") as OperationsResult.Failure).reason)
        status = HttpStatusCode.OK
        payload = """{"data":[$json],"pagination":{"nextPageToken":""}}"""
        val list = repo.shifts(org, site, true, PageRequest()) as OperationsResult.Success
        assertEquals(listOf(id), list.value.items.map { it.id })
        client.close()
    }

    private inner class FakeShifts : ShiftRepository {
        val listed = mutableListOf<Shift>()
        val created = mutableListOf<Shift>()
        val keys = mutableListOf<String>()
        val createResults = ArrayDeque<OperationsResult<Shift>>()
        var deactivation: OperationsResult<Shift> = OperationsResult.Success(shift(active = false))
        var calls = 0

        override suspend fun shifts(org: String, site: String, includeInactive: Boolean, page: PageRequest) =
            OperationsResult.Success(Page(listed.toList(), ""))

        override suspend fun create(org: String, input: ShiftInput, key: String): OperationsResult<Shift> {
            calls++
            keys += key
            return createResults.removeFirstOrNull() ?: OperationsResult.Success(created.first())
        }

        override suspend fun update(org: String, id: String, change: ShiftChange, key: String): OperationsResult<Shift> {
            calls++
            keys += key
            return OperationsResult.Success(shift(name = change.name))
        }

        override suspend fun deactivate(org: String, id: String, rowVersion: Long, key: String): OperationsResult<Shift> {
            calls++
            keys += key
            return deactivation
        }
    }
}
