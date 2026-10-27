package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.checklists.data.*
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistAssignmentTest {
    private val input =
        AssignmentInput("assignment", "template", "site", ChecklistFrequency.DAILY, "08:30")
    private val value =
        ChecklistAssignment(
            "assignment",
            "template",
            "Limpieza",
            "version",
            "site",
            "Lima",
            ChecklistFrequency.DAILY,
            "08:30",
            "America/Lima",
            true,
            "2026-10-24T10:00:00Z",
            "2026-10-24T05:00:00Z",
            "2026-10-25T05:00:00Z",
        )
    private val owner = DraftOwner("admin", "org")

    @Test
    fun invalidScheduleIsRejectedBeforeConsultingOrWriting() = runTest {
        val remote = AssignmentRemote(value)
        val create = CreateChecklistAssignment(remote, remote)
        for (invalid in
            listOf(
                input.copy(locationId = ""),
                input.copy(frequency = null),
                input.copy(dueTime = "24:01"),
                input.copy(dueTime = "8:30"),
            )) assertEquals(
            AssignmentFailure.INVALID,
            (create("org", invalid) as AssignmentResult.Failure).reason,
        )
        assertEquals(0, remote.reads)
        assertEquals(0, remote.writes)
        assertNull(AssignmentInputRules.time.error(""))
        assertNull(AssignmentInputRules.time.error("23:59"))
    }

    @Test
    fun lostCreateResponseIsConfirmedByIdWithoutAnotherPost() = runTest {
        val remote = AssignmentRemote(value)
        remote.loseResponse = true
        val create = CreateChecklistAssignment(remote, remote)
        assertEquals(AssignmentResult.Failure(AssignmentFailure.CONNECTION), create("org", input))
        assertEquals(AssignmentResult.Success(value), create("org", input))
        assertEquals(1, remote.writes)
        assertEquals(2, remote.reads)
        assertEquals(
            AssignmentResult.Failure(AssignmentFailure.CONFLICT),
            create("org", input.copy(locationId = "other")),
        )
        assertEquals(1, remote.writes)
    }

    @Test
    fun deniedLookupCannotFallThroughToCreate() = runTest {
        val remote = AssignmentRemote(value)
        remote.readFailure = AssignmentFailure.DENIED
        assertEquals(
            AssignmentResult.Failure(AssignmentFailure.DENIED),
            CreateChecklistAssignment(remote, remote)("org", input),
        )
        assertEquals(0, remote.writes)
    }

    @Test
    fun localDraftIsIsolatedByOwnerOrganizationOriginAndTemplate() = runTest {
        val storage = TestDraftStorage()
        val repo = LocalChecklistAssignmentDraftRepository(storage, "https://api.example")
        val draft = AssignmentDraft(input, true, "Lima")
        repo.write(owner, "template", draft)
        assertEquals(draft, repo.read(owner, "template"))
        assertNull(repo.read(owner.copy(userId = "other"), "template"))
        assertNull(repo.read(owner.copy(organizationId = "other"), "template"))
        assertNull(repo.read(owner, "different"))
        assertNull(
            LocalChecklistAssignmentDraftRepository(storage, "https://elsewhere.example")
                .read(owner, "template")
        )
        repo.write(owner, "template", null)
        assertNull(repo.read(owner, "template"))
    }

    @Test
    fun restoredPendingProposalIsLockedAndNeverSentAutomatically() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo =
                LocalChecklistAssignmentDraftRepository(TestDraftStorage(), "https://api.example")
            repo.write(owner, "template", AssignmentDraft(input, true, "Lima"))
            val remote = AssignmentRemote(value).also { it.existing = true }
            val vm =
                ChecklistAssignmentFormViewModel(
                    CreateChecklistAssignment(remote, remote),
                    ManageAssignmentDraft(repo),
                ) {
                    error("No debe regenerar el ID")
                }
            models.put("form", vm)
            vm.open("admin", "org", "template")
            advanceUntilIdle()
            assertTrue(vm.state.value.pending)
            assertFalse(vm.state.value.editable)
            vm.location("other", "Otra sede")
            vm.frequency(ChecklistFrequency.WEEKLY)
            assertEquals(input, vm.state.value.input)
            assertEquals(0, remote.reads)
            assertEquals(0, remote.writes)
            vm.save()
            advanceUntilIdle()
            assertEquals(value, vm.state.value.saved)
            assertEquals(1, remote.reads)
            assertEquals(0, remote.writes)
            assertNull(repo.read(owner, "template"))
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun localWriteFailureBlocksRemoteScheduling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = TestDraftStorage()
            val remote = AssignmentRemote(value)
            val vm =
                ChecklistAssignmentFormViewModel(
                    CreateChecklistAssignment(remote, remote),
                    ManageAssignmentDraft(
                        LocalChecklistAssignmentDraftRepository(storage, "https://api.example")
                    ),
                ) {
                    "assignment"
                }
            models.put("form", vm)
            vm.open("admin", "org", "template")
            advanceUntilIdle()
            vm.location("site", "Lima")
            vm.frequency(ChecklistFrequency.DAILY)
            vm.dueTime("08:30")
            storage.failWrites = true
            vm.save()
            advanceUntilIdle()
            assertEquals(0, remote.reads)
            assertEquals(0, remote.writes)
            assertFalse(vm.state.value.busy)
            assertEquals(
                pe.bpm.core.presentation.DraftSaveStatus.FAILED,
                vm.state.value.localStatus,
            )
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun canceledListCannotRestoreAnotherOrganization() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val response = CompletableDeferred<AssignmentPageResult>()
        val remote = AssignmentRemote(value)
        val reader =
            object : ChecklistAssignmentReader {
                override suspend fun list(organizationId: String, page: PageRequest) =
                    if (organizationId == "old") withContext(NonCancellable) { response.await() }
                    else AssignmentPageResult.Success(emptyList(), "")

                override suspend fun get(organizationId: String, id: String) =
                    AssignmentResult.Failure(AssignmentFailure.NOT_FOUND)
            }
        try {
            val vm =
                ChecklistAssignmentsViewModel(
                    LoadChecklistAssignments(reader),
                    LoadChecklistAssignment(reader),
                    DeactivateChecklistAssignment(remote),
                )
            models.put("list", vm)
            vm.open("chef", "old")
            runCurrent()
            vm.open("chef", "new")
            runCurrent()
            response.complete(AssignmentPageResult.Success(listOf(value), ""))
            advanceUntilIdle()
            assertEquals("new", vm.state.value.organizationId)
            assertTrue(vm.state.value.items.isEmpty())
            assertFalse(vm.state.value.loading)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun returningAfterPeriodEndsRevalidatesEvenIfTheListWasJustLoaded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val remote = AssignmentRemote(value).also { it.existing = true }
            var now = kotlin.time.Instant.parse("2026-10-25T04:59:59Z")
            val vm =
                ChecklistAssignmentsViewModel(
                    LoadChecklistAssignments(remote),
                    LoadChecklistAssignment(remote),
                    DeactivateChecklistAssignment(remote),
                    now = { now },
                )
            models.put("list", vm)
            vm.open("chef", "org")
            advanceUntilIdle()
            vm.refreshIfStale()
            advanceUntilIdle()
            assertEquals(1, remote.pages)
            now = kotlin.time.Instant.parse("2026-10-25T05:00:00Z")
            vm.refreshIfStale()
            advanceUntilIdle()
            assertEquals(2, remote.pages)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun assignedRunStartDraftKeepsAssignmentAcrossRestart() = runTest {
        val repo = LocalChecklistRunDraftRepository(TestDraftStorage(), "https://api.example")
        val start =
            StartChecklistInput(
                "run",
                "site",
                "version",
                "operation",
                "2026-10-24T12:00:00Z",
                "assignment",
            )
        val draft = RunDraft(null, emptyMap(), RunOperation.Start(start))
        repo.write(DraftOwner("chef", "org"), draft)
        assertEquals(draft, repo.read(DraftOwner("chef", "org")))
    }

    @Test
    fun remoteContractKeepsPeriodAndFiltersAndRejectsInvalidMetadata() = runTest {
        var payload =
            """{"data":[{"id":"assignment","templateId":"template","templateName":"Limpieza","templateVersionId":"version","locationId":"site","locationName":"Lima","frequency":"DAILY","dueTime":"08:30","timezone":"America/Lima","active":true,"createdAt":"2026-10-24T10:00:00Z","periodStartsAt":"2026-10-24T05:00:00Z","periodEndsAt":"2026-10-25T05:00:00Z","privateField":"omit"}],"pagination":{"nextPageToken":"next"}}"""
        val client =
            HttpClient(
                MockEngine { request ->
                    assertEquals("Bearer test", request.headers["Authorization"])
                    assertEquals("Lima", request.url.parameters["query"])
                    respond(
                        payload,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        try {
            val repo =
                RemoteChecklistAssignmentRepository(
                    client,
                    AppConfig("https://api.example"),
                    token = { "test" },
                )
            assertEquals(
                AssignmentPageResult.Success(listOf(value), "next"),
                repo.list("org", PageRequest(query = "Lima")),
            )
            payload = payload.replace("2026-10-25T05:00:00Z", "2026-10-23T05:00:00Z")
            assertEquals(
                AssignmentPageResult.Failure(AssignmentFailure.INVALID_RESPONSE),
                repo.list("org", PageRequest(query = "Lima")),
            )
        } finally {
            client.close()
        }
    }
}

private class AssignmentRemote(private val value: ChecklistAssignment) :
    ChecklistAssignmentReader, ChecklistAssignmentWriter {
    var pages = 0
    var reads = 0
    var writes = 0
    var existing = false
    var loseResponse = false
    var readFailure: AssignmentFailure? = null

    override suspend fun list(organizationId: String, page: PageRequest): AssignmentPageResult {
        pages++
        return AssignmentPageResult.Success(if (existing) listOf(value) else emptyList(), "")
    }

    override suspend fun get(organizationId: String, id: String): AssignmentResult {
        reads++
        return readFailure?.let { AssignmentResult.Failure(it) }
            ?: if (existing) AssignmentResult.Success(value)
            else AssignmentResult.Failure(AssignmentFailure.NOT_FOUND)
    }

    override suspend fun create(organizationId: String, input: AssignmentInput): AssignmentResult {
        writes++
        existing = true
        return if (loseResponse) AssignmentResult.Failure(AssignmentFailure.CONNECTION)
        else AssignmentResult.Success(value)
    }

    override suspend fun deactivate(organizationId: String, id: String) =
        AssignmentResult.Success(value.copy(active = false))
}
