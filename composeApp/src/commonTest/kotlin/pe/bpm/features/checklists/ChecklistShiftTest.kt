package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.checklists.data.*
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.*

/** Programación por turno, estado del turno, versión de plantilla y rechazos de inicio. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistShiftTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val daily = AssignmentInput("assignment", "template", "site", ChecklistFrequency.DAILY, "", "shift-am")

    private fun assignmentJson(state: String = "OPEN", shiftId: String = "shift-am", version: Int = 3) =
        """{"id":"assignment","templateId":"template","templateName":"Apertura","templateVersionId":"version","locationId":"site","locationName":"Lima","frequency":"DAILY","dueTime":"","timezone":"America/Lima","active":true,"createdAt":"2026-11-10T10:00:00Z","periodStartsAt":"2026-11-10T11:00:00Z","periodEndsAt":"2026-11-10T19:00:00Z","templateVersionNumber":$version,"shiftId":"$shiftId","shiftName":"Mañana","shiftStartTime":"06:00","shiftEndTime":"14:00","shiftState":"$state","run":null}"""

    @Test
    fun shiftSchedulesMustBeDailyWithoutReferenceTime() {
        assertTrue(AssignmentInputRules.errors(daily).isEmpty())
        assertEquals(setOf("frequency"), AssignmentInputRules.errors(daily.copy(frequency = ChecklistFrequency.WEEKLY)).keys)
        assertEquals(setOf("dueTime"), AssignmentInputRules.errors(daily.copy(dueTime = "08:30")).keys)
        assertTrue(AssignmentInputRules.errors(daily.copy(shiftId = null, dueTime = "08:30")).isEmpty())
    }

    @Test
    fun matchingComparesTheShift() {
        val saved = ChecklistAssignment("assignment", "template", "Apertura", "version", "site", "Lima", ChecklistFrequency.DAILY, "", "America/Lima", true,
            "2026-11-10T10:00:00Z", shiftId = "shift-am", shiftState = ShiftState.OPEN)
        assertTrue(saved.matches(daily))
        assertFalse(saved.matches(daily.copy(shiftId = "shift-pm")))
        assertFalse(saved.matches(daily.copy(shiftId = null)))
        assertTrue(saved.copy(shiftId = "", shiftState = null).matches(daily.copy(shiftId = null)))
    }

    @Test
    fun repositoryReadsShiftStateAndTemplateVersionAndSendsShiftId() = runTest {
        var body: JsonObject? = null
        var payload = ""
        val client =
            createHttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Post) body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    respond(payload, HttpStatusCode.OK, jsonHeaders)
                }
            )
        val repo = RemoteChecklistAssignmentRepository(client, AppConfig("https://example.test"), token = { "token" })
        payload = """{"data":${assignmentJson()}}"""
        val created = repo.create("org", daily) as AssignmentResult.Success
        assertEquals("shift-am", body!!.getValue("shiftId").jsonPrimitive.content)
        assertEquals(ShiftState.OPEN, created.value.shiftState)
        assertEquals(3, created.value.templateVersionNumber)
        assertEquals("Mañana", created.value.shiftName)
        // Sin turno el cuerpo no incluye shiftId.
        payload = """{"data":${assignmentJson(shiftId = "", state = "").replace("\"shiftName\":\"Mañana\"", "\"shiftName\":\"\"")}}"""
        assertIs<AssignmentResult.Success>(repo.create("org", daily.copy(shiftId = null)))
        assertFalse("shiftId" in body!!.keys)
        // CLOSED y UPCOMING son estados válidos de lectura.
        for (state in listOf("CLOSED", "UPCOMING")) {
            payload = """{"data":[${assignmentJson(state)}],"pagination":{"nextPageToken":""}}"""
            val page = repo.list("org", PageRequest()) as AssignmentPageResult.Success
            assertEquals(ShiftState.valueOf(state), page.items.single().shiftState)
        }
        // Un estado sin turno, o un estado desconocido, es una respuesta inválida.
        payload = """{"data":[${assignmentJson("OPEN", shiftId = "")}],"pagination":{"nextPageToken":""}}"""
        assertEquals(AssignmentFailure.INVALID_RESPONSE, (repo.list("org", PageRequest()) as AssignmentPageResult.Failure).reason)
        payload = """{"data":[${assignmentJson("SOON")}],"pagination":{"nextPageToken":""}}"""
        assertEquals(AssignmentFailure.INVALID_RESPONSE, (repo.list("org", PageRequest()) as AssignmentPageResult.Failure).reason)
        client.close()
    }

    @Test
    fun runStartMapsClosedShiftAndOutdatedTemplate() = runTest {
        var status = HttpStatusCode.UnprocessableEntity
        var payload = """{"errors":[{"code":"SHIFT_OUT_OF_HOURS","message":"interno"}]}"""
        val client = createHttpClient(MockEngine { respond(payload, status, jsonHeaders) })
        val repo = RemoteChecklistRunRepository(client, AppConfig("https://example.test"), token = { "token" })
        val start = StartChecklistInput("run", "site", "version", "run", "2026-11-10T12:00:00Z", "assignment")
        assertEquals(RunResult.Failure(RunFailure.SHIFT_OUT_OF_HOURS), repo.start("org", start))
        payload = """{"errors":[{"code":"VALIDATION_ERROR"}]}"""
        assertEquals(RunResult.Failure(RunFailure.INVALID), repo.start("org", start))
        status = HttpStatusCode.Conflict
        payload = """{"errors":[{"code":"TEMPLATE_VERSION_OUTDATED"}]}"""
        assertEquals(RunResult.Failure(RunFailure.TEMPLATE_OUTDATED), repo.start("org", start))
        payload = """{"errors":[{"code":"ASSIGNMENT_ALREADY_STARTED"}]}"""
        assertEquals(RunResult.Failure(RunFailure.ASSIGNMENT_STARTED), repo.start("org", start))
        assertTrue(runFailureMessage(RunFailure.SHIFT_OUT_OF_HOURS).contains("turno"))
        assertTrue(runFailureMessage(RunFailure.TEMPLATE_OUTDATED).contains("versión"))
        client.close()
    }

    @Test
    fun runKeepsItsShiftAndTemplateVersion() = runTest {
        val run = """{"data":{"id":"run","organizationId":"org","locationId":"site","templateVersionId":"version","performedBy":"user","status":"DRAFT","rowVersion":1,"items":[{"id":"question","sectionName":"Higiene","label":"Superficies limpias","responseType":"COMPLIANCE","displayOrder":1,"required":true}],"answers":[],"assignmentId":"assignment","shiftId":"shift-am","shiftName":"Mañana","templateVersionNumber":2}}"""
        val client = createHttpClient(MockEngine { respond(run, HttpStatusCode.OK, jsonHeaders) })
        val repo = RemoteChecklistRunRepository(client, AppConfig("https://example.test"), token = { "token" })
        val start = StartChecklistInput("run", "site", "version", "run", "2026-11-10T12:00:00Z", "assignment")
        val started = (repo.start("org", start) as RunResult.Success).run
        assertEquals("Mañana", started.shiftName)
        assertEquals("shift-am", started.shiftId)
        assertEquals(2, started.templateVersionNumber)
        client.close()
    }

    @Test
    fun formLoadsShiftsForDailySchedulesAndDropsThemWhenTheFrequencyChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val loaded = mutableListOf<String>()
            val sent = mutableListOf<AssignmentInput>()
            val reader =
                object : ChecklistAssignmentReader {
                    override suspend fun list(organizationId: String, page: PageRequest) = AssignmentPageResult.Success(emptyList(), "")

                    override suspend fun get(organizationId: String, id: String) = AssignmentResult.Failure(AssignmentFailure.NOT_FOUND)
                }
            val writer =
                object : ChecklistAssignmentWriter {
                    override suspend fun create(organizationId: String, input: AssignmentInput): AssignmentResult {
                        sent += input
                        return AssignmentResult.Success(
                            ChecklistAssignment("assignment", "template", "Apertura", "version", input.locationId, "Lima", input.frequency!!, input.dueTime, "America/Lima", true,
                                "2026-11-10T10:00:00Z", shiftId = input.shiftId.orEmpty(), shiftState = if (input.shiftId != null) ShiftState.OPEN else null)
                        )
                    }

                    override suspend fun deactivate(organizationId: String, id: String) = AssignmentResult.Failure(AssignmentFailure.NOT_FOUND)
                }
            val vm =
                ChecklistAssignmentFormViewModel(
                    CreateChecklistAssignment(reader, writer),
                    ManageAssignmentDraft(LocalChecklistAssignmentDraftRepository(TestDraftStorage(), "https://api.example")),
                    { _, location ->
                        loaded += location
                        ShiftOptionsResult.Success(listOf(ChecklistShiftOption("shift-am", "Mañana", "06:00", "14:00", true)))
                    },
                    { "assignment" },
                )
            models.put("vm", vm)
            vm.open("admin", "org", "template")
            advanceUntilIdle()
            vm.location("site", "Lima")
            assertTrue(loaded.isEmpty(), "Sin frecuencia diaria no se piden turnos")
            vm.frequency(ChecklistFrequency.DAILY)
            advanceUntilIdle()
            assertEquals(listOf("site"), loaded)
            assertEquals(listOf("shift-am"), vm.state.value.shifts.map { it.id })
            vm.dueTime("08:30")
            vm.shift("shift-am")
            assertEquals("shift-am", vm.state.value.input?.shiftId)
            assertEquals("", vm.state.value.input?.dueTime, "El turno reemplaza la hora de referencia")
            vm.frequency(ChecklistFrequency.WEEKLY)
            assertNull(vm.state.value.input?.shiftId)
            vm.frequency(ChecklistFrequency.DAILY)
            vm.shift("shift-am")
            vm.save()
            advanceUntilIdle()
            assertEquals("shift-am", sent.single().shiftId)
            assertEquals(ChecklistFrequency.DAILY, sent.single().frequency)
            assertNotNull(vm.state.value.saved)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun changingTheLocationDiscardsTheChosenShift() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val vm =
                ChecklistAssignmentFormViewModel(
                    CreateChecklistAssignment(
                        object : ChecklistAssignmentReader {
                            override suspend fun list(organizationId: String, page: PageRequest) = AssignmentPageResult.Success(emptyList(), "")

                            override suspend fun get(organizationId: String, id: String) = AssignmentResult.Failure(AssignmentFailure.NOT_FOUND)
                        },
                        object : ChecklistAssignmentWriter {
                            override suspend fun create(organizationId: String, input: AssignmentInput) = AssignmentResult.Failure(AssignmentFailure.UNAVAILABLE)

                            override suspend fun deactivate(organizationId: String, id: String) = AssignmentResult.Failure(AssignmentFailure.UNAVAILABLE)
                        },
                    ),
                    ManageAssignmentDraft(LocalChecklistAssignmentDraftRepository(TestDraftStorage(), "https://api.example")),
                    { _, _ -> ShiftOptionsResult.Success(listOf(ChecklistShiftOption("shift-am", "Mañana", "06:00", "14:00"))) },
                    { "assignment" },
                )
            models.put("vm", vm)
            vm.open("admin", "org", "template")
            advanceUntilIdle()
            vm.location("site", "Lima")
            vm.frequency(ChecklistFrequency.DAILY)
            advanceUntilIdle()
            vm.shift("shift-am")
            vm.location("other", "Cusco")
            advanceUntilIdle()
            assertNull(vm.state.value.input?.shiftId)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun shiftReaderListsOnlyActiveShiftsOfTheLocation() = runTest {
        var asked: Url? = null
        val client =
            createHttpClient(
                MockEngine { request ->
                    asked = request.url
                    respond(
                        """{"data":[{"id":"shift-am","locationId":"site","name":"Mañana","startTime":"06:00","endTime":"14:00","active":true,"openNow":true},{"id":"old","locationId":"site","name":"Vieja","startTime":"14:00","endTime":"22:00","active":false}]}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            )
        val reader = RemoteChecklistShiftReader(client, AppConfig("https://example.test"), token = { "token" })
        val result = reader.shifts("org", "site") as ShiftOptionsResult.Success
        assertEquals(listOf("shift-am"), result.shifts.map { it.id })
        assertTrue(result.shifts.single().openNow)
        assertEquals("site", asked!!.parameters["locationId"])
        assertEquals("ACTIVE", asked!!.parameters["status"])
        client.close()
    }
}
