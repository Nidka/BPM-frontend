package pe.bpm.features.checklists

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.checklists.data.RemoteChecklistRunRepository
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistRunTest {
    private val item = ChecklistItem("question", "Higiene", "Superficies limpias", ChecklistResponseType.COMPLIANCE, 1, true, "", null, null, false)
    private val initial = ChecklistRun("run", "org", "site", "version", "user", "DRAFT", 1, listOf(item), emptyList())

    @Test fun validatesRequiredAnswersAndExcludesNonApplicableFromEvaluableItems() {
        assertTrue(validRunAnswers(initial.items, emptyList(), complete = false))
        assertFalse(validRunAnswers(initial.items, emptyList(), complete = true))
        assertFalse(validRunAnswers(initial.items, listOf(ChecklistAnswer("question", "NOT_APPLICABLE")), complete = true))
        assertFalse(validRunAnswers(initial.items, listOf(ChecklistAnswer("other", "COMPLIANT")), complete = false))
        assertFalse(validRunAnswers(initial.items, listOf(ChecklistAnswer("question", "COMPLIANT"), ChecklistAnswer("question", "COMPLIANT")), complete = false))
        assertTrue(validRunAnswers(initial.items, listOf(ChecklistAnswer("question", "NON_COMPLIANT")), complete = true))
        assertFalse(validRunAnswers(listOf(item.copy(evidenceRequiredOnFailure = true)), listOf(ChecklistAnswer("question", "NON_COMPLIANT")), complete = true))
        val numeric = item.copy(responseType = ChecklistResponseType.TEMPERATURE, minimumValue = -20.0, maximumValue = -15.0)
        assertTrue(validRunAnswers(listOf(numeric), listOf(ChecklistAnswer("question", numericValue = -17.5)), true))
        assertFalse(validRunAnswers(listOf(numeric), listOf(ChecklistAnswer("question", numericValue = -17.555)), true))
    }

    @Test fun retriesTheExactWriteAndRequiresSaveThenReviewBeforeSubmission() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRunRepository(initial)
            var ids = 0
            val vm = ChecklistRunViewModel(LoadChecklistRun(remote), StartChecklistRun(remote), SaveChecklistRun(remote), SubmitChecklistRun(remote), { "key-${++ids}" }, { "2026-10-19T12:00:00Z" })
            vm.open("user", "org", "run"); advanceUntilIdle()
            vm.edit("question", RunAnswerInput("COMPLIANT"))
            vm.review(); assertFalse(vm.state.value.reviewing)
            remote.failOnce = true
            vm.save(); advanceUntilIdle()
            assertTrue(vm.state.value.pending)
            val locked = vm.state.value.inputs
            vm.edit("question", RunAnswerInput("NON_COMPLIANT"))
            assertEquals(locked, vm.state.value.inputs)
            vm.retry(); advanceUntilIdle()
            assertEquals(remote.writes[0], remote.writes[1])
            assertFalse(vm.state.value.dirty)
            vm.confirmSubmit(); advanceUntilIdle(); assertEquals(0, remote.submissions)
            vm.review(); assertTrue(vm.state.value.reviewing)
            vm.confirmSubmit(); advanceUntilIdle()
            assertEquals("SUBMITTED", vm.state.value.run?.status)
            assertEquals(100.0, vm.state.value.run?.compliancePercent)
            assertFalse(vm.state.value.editable)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun contextChangeCancelsLateResultsAndRemovesAnswers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRunRepository(initial)
            remote.gate = CompletableDeferred()
            val vm = ChecklistRunViewModel(LoadChecklistRun(remote), StartChecklistRun(remote), SaveChecklistRun(remote), SubmitChecklistRun(remote), { "key-valid" }, { "2026-10-19T12:00:00Z" })
            vm.open("user", "org", "run"); runCurrent()
            vm.open("another", "another-org")
            remote.gate!!.complete(Unit); advanceUntilIdle()
            assertEquals("another-org", vm.state.value.organizationId)
            assertNull(vm.state.value.run)
            assertTrue(vm.state.value.inputs.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun switchingRunCancelsThePreviousReadWithinTheSameTenant() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val remote = FakeRunRepository(initial)
            remote.gate = CompletableDeferred()
            val vm = ChecklistRunViewModel(LoadChecklistRun(remote), StartChecklistRun(remote), SaveChecklistRun(remote), SubmitChecklistRun(remote), { "key-valid" }, { "2026-10-19T12:00:00Z" })
            vm.open("user", "org", "old-run"); runCurrent()
            vm.open("user", "org", "new-run"); runCurrent()
            remote.gate!!.complete(Unit); advanceUntilIdle()
            assertEquals("new-run", vm.state.value.run?.id)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun transportRejectsWrongTenantAndInvalidatesUnauthorizedSession() = runTest {
        var status = HttpStatusCode.OK
        var token: String? = null
        var rejected: String? = null
        var calls = 0
        val client = createHttpClient(MockEngine {
            calls++; assertEquals("Bearer test.token", it.headers["Authorization"])
            respond("""{"data":{"id":"run","organizationId":"foreign","locationId":"site","templateVersionId":"version","performedBy":"user","status":"DRAFT","rowVersion":1,"items":[],"answers":[]}}""", status, headersOf("Content-Type", "application/json"))
        })
        try {
            val repo = RemoteChecklistRunRepository(client, AppConfig("https://api.example.com"), onSessionRejected = { rejected = it }) { token }
            assertEquals(RunFailure.SESSION, assertIs<RunResult.Failure>(repo.read("org", "run")).reason)
            assertEquals(0, calls); token = "test.token"
            assertEquals(RunFailure.INVALID_RESPONSE, assertIs<RunResult.Failure>(repo.read("org", "run")).reason)
            status = HttpStatusCode.Unauthorized
            assertEquals(RunFailure.SESSION, assertIs<RunResult.Failure>(repo.read("org", "run")).reason)
            assertEquals("test.token", rejected)
        } finally { client.close() }
    }

    @Test fun transportWritesOnlyTheProposedVersionAndRequiresConfirmedSubmission() = runTest {
        var submitted = false
        val client = createHttpClient(MockEngine { request ->
            assertEquals("operation-unique", request.headers["Idempotency-Key"])
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(if (submitted) 2 else 1, body.getValue("rowVersion").jsonPrimitive.int)
            assertFalse("performedBy" in body)
            val data = """{"data":{"id":"run","organizationId":"org","locationId":"site","templateVersionId":"version","performedBy":"user","status":"${if (submitted) "SUBMITTED" else "DRAFT"}","rowVersion":${if (submitted) 3 else 2},"compliancePercent":${if (submitted) "100" else "null"},"items":[{"id":"question","sectionName":"Higiene","label":"Limpieza","responseType":"COMPLIANCE","displayOrder":1,"required":true}],"answers":[{"itemId":"question","complianceResult":"COMPLIANT"}]}}"""
            respond(data, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val repo = RemoteChecklistRunRepository(client, AppConfig("https://api.example.com")) { "test.token" }
            val saved = assertIs<RunResult.Success>(repo.save("org", "run", 1, "operation-unique", listOf(ChecklistAnswer("question", "COMPLIANT")))).run
            assertEquals(2, saved.rowVersion)
            submitted = true
            val final = assertIs<RunResult.Success>(repo.submit("org", "run", 2, "operation-unique")).run
            assertEquals("SUBMITTED", final.status)
            assertEquals(100.0, final.compliancePercent)
        } finally { client.close() }
    }

    private class FakeRunRepository(var current: ChecklistRun) : ChecklistRunReader, ChecklistRunWriter {
        var failOnce = false
        var gate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Triple<Int, String, List<ChecklistAnswer>>>()
        var submissions = 0
        override suspend fun read(organizationId: String, id: String): RunResult { gate?.await(); return RunResult.Success(current.copy(id = id, organizationId = organizationId)) }
        override suspend fun start(organizationId: String, input: StartChecklistInput): RunResult = RunResult.Success(current.copy(id = input.id, locationId = input.locationId))
        override suspend fun save(organizationId: String, id: String, version: Int, key: String, answers: List<ChecklistAnswer>): RunResult {
            writes += Triple(version, key, answers)
            if (failOnce) { failOnce = false; return RunResult.Failure(RunFailure.CONNECTION) }
            current = current.copy(rowVersion = version + 1, answers = answers); return RunResult.Success(current)
        }
        override suspend fun submit(organizationId: String, id: String, version: Int, key: String): RunResult {
            submissions++; current = current.copy(rowVersion = version + 1, status = "SUBMITTED", compliancePercent = 100.0); return RunResult.Success(current)
        }
    }
}
