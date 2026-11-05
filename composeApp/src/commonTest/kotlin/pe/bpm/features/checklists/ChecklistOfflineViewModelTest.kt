package pe.bpm.features.checklists

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.checklists.data.LocalChecklistOfflineRepository
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.ChecklistOfflineViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistOfflineViewModelTest {
    private val owner = DraftOwner("chef", "org")
    private val item = ChecklistItem("q", "Limpieza", "¿Está limpio?", ChecklistResponseType.COMPLIANCE, 1, true, "", null, null, false)
    private val run = ChecklistRun("run", "org", "site", "version", "chef", "DRAFT", 1, listOf(item), emptyList())

    @Test fun newSessionCannotRevealLocalAnswersUntilServerAuthorizesThem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = LocalChecklistOfflineRepository(TestDraftStorage(), "origin")
            var denied = false
            val reader = object : ChecklistRunReader {
                override suspend fun read(organizationId: String, id: String): RunResult =
                    if (denied) RunResult.Failure(RunFailure.DENIED) else RunResult.Success(run)
            }
            val local = ManageOfflineChecklists(repo, LoadChecklistRun(reader)) { "valid-key" }
            local.prepare(owner, "run"); local.edit(owner, "run", "q", RunAnswerInput("COMPLIANT", "Respuesta privada"))
            denied = true
            val vm = ChecklistOfflineViewModel(local, SyncOfflineChecklists(repo, SaveChecklistRun(noWrites), SubmitChecklistRun(noWrites)))
            vm.open("chef", "org"); advanceUntilIdle()
            assertTrue(vm.state.value.entries.isEmpty())
            assertNotNull(vm.state.value.failure)
            assertEquals("Respuesta privada", local.list(owner).single().inputs["q"]?.observation)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun editsAreProtectedBeforeQueueAndResetHidesOldContext() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = LocalChecklistOfflineRepository(TestDraftStorage(), "origin")
            val local = ManageOfflineChecklists(repo, LoadChecklistRun(object : ChecklistRunReader {
                override suspend fun read(organizationId: String, id: String) = RunResult.Success(run)
            })) { "valid-key" }
            local.prepare(owner, "run")
            val vm = ChecklistOfflineViewModel(local, SyncOfflineChecklists(repo, SaveChecklistRun(noWrites), SubmitChecklistRun(noWrites)))
            vm.open("chef", "org"); advanceUntilIdle(); vm.select("run")
            vm.edit("q", RunAnswerInput("COMPLIANT", "Sí, mañana")); advanceUntilIdle()
            assertEquals(DraftSaveStatus.SAVED, vm.state.value.localStatus)
            assertEquals("Sí, mañana", local.list(owner).single().inputs["q"]?.observation)
            vm.reset(); advanceUntilIdle()
            assertTrue(vm.state.value.entries.isEmpty()); assertNull(vm.state.value.owner)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun lateAuthorizationAfterResetCannotRestoreAnotherSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = LocalChecklistOfflineRepository(TestDraftStorage(), "origin")
            val gate = CompletableDeferred<Unit>(); var wait = false
            val local = ManageOfflineChecklists(repo, LoadChecklistRun(object : ChecklistRunReader {
                override suspend fun read(organizationId: String, id: String): RunResult {
                    if (wait) withContext(NonCancellable) { gate.await() }
                    return RunResult.Success(run)
                }
            })) { "valid-key" }
            local.prepare(owner, "run"); wait = true
            val vm = ChecklistOfflineViewModel(local, SyncOfflineChecklists(repo, SaveChecklistRun(noWrites), SubmitChecklistRun(noWrites)))
            vm.open("chef", "org"); runCurrent(); vm.reset(); gate.complete(Unit); advanceUntilIdle()
            assertNull(vm.state.value.owner); assertTrue(vm.state.value.entries.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    private val noWrites = object : ChecklistRunWriter {
        override suspend fun start(organizationId: String, input: StartChecklistInput): RunResult = error("No crear")
        override suspend fun save(organizationId: String, id: String, version: Int, key: String, answers: List<ChecklistAnswer>) = RunResult.Failure(RunFailure.CONNECTION)
        override suspend fun submit(organizationId: String, id: String, version: Int, key: String) = RunResult.Failure(RunFailure.CONNECTION)
    }
}
