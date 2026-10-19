package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.checklists.data.*
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistDraftRecoveryTest {
    private val item = ChecklistItem("question", "Higiene", "Revisión", ChecklistResponseType.COMPLIANCE, 1, true, "", null, null, false)
    private val original = ChecklistRun("run", "org", "site", "version", "user", "DRAFT", 1, listOf(item), emptyList())
    private val owner = DraftOwner("user", "org")

    @Test fun unfinishedInputSurvivesSessionResetAndIsReauthorizedBeforeRecovery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = TestDraftStorage()
            val remote = TestRunRemote(original.copy(items = listOf(item.copy(responseType = ChecklistResponseType.TEMPERATURE, minimumValue = 0.0, maximumValue = 5.0))))
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://api.example.com"))
            val vm = vm(remote, drafts); models.put("vm", vm)
            vm.open("user", "org", "run"); advanceUntilIdle()
            vm.edit("question", RunAnswerInput("-", "Dato todavía incompleto"))
            // El reset no cancela la última copia encolada cuando vence la sesión.
            vm.reset(); vm.open("user", "org", "run"); advanceUntilIdle()
            assertEquals("-", vm.state.value.inputs["question"]?.value)
            assertTrue(vm.state.value.dirty)
            assertTrue(vm.state.value.editable)
            assertEquals(DraftSaveStatus.SAVED, vm.state.value.localStatus)
            assertEquals(2, remote.reads)
            assertTrue(remote.saves.isEmpty())
            assertEquals(0, remote.submissions)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun lostSaveResponseReplaysTheExactVersionKeyAndBodyAfterRecreation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = TestDraftStorage(); val remote = TestRunRemote(original)
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://api.example.com"))
            val first = vm(remote, drafts); models.put("first", first)
            first.open("user", "org", "run"); advanceUntilIdle()
            first.edit("question", RunAnswerInput("COMPLIANT", "Observación original"))
            remote.loseResponse = true
            first.save(); advanceUntilIdle()
            assertTrue(first.state.value.pending)
            first.reset(); advanceUntilIdle()
            val restored = vm(remote, drafts); models.put("restored", restored)
            restored.open("user", "org"); advanceUntilIdle()
            assertTrue(restored.state.value.pending)
            assertEquals(1, remote.saves.size) // Recuperar no vuelve a enviar automáticamente.
            restored.retry(); advanceUntilIdle()
            assertEquals(remote.saves[0], remote.saves[1])
            assertFalse(restored.state.value.dirty)
            assertFalse(restored.state.value.pending)
            assertEquals(2, restored.state.value.run?.rowVersion)
            assertNull(drafts.read(owner))
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun startPendingAfterRestartKeepsClientOperationAndTimestamp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val remote = TestRunRemote(original); val storage = TestDraftStorage()
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://api.example.com"))
            val first = vm(remote, drafts); models.put("first", first)
            first.open("user", "org"); advanceUntilIdle()
            remote.loseResponse = true
            first.begin("site", "version"); advanceUntilIdle(); first.reset(); advanceUntilIdle()
            val next = vm(remote, drafts); models.put("next", next)
            next.open("user", "org"); advanceUntilIdle()
            assertTrue(next.state.value.pending)
            next.retry(); advanceUntilIdle()
            assertEquals(remote.starts[0], remote.starts[1])
            assertEquals(1, remote.created)
            assertFalse(next.state.value.pending)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun storageFailurePreventsAnUnrecoverableWriteAndCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = TestDraftStorage(); val remote = TestRunRemote(original)
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://api.example.com"))
            val current = vm(remote, drafts); models.put("vm", current)
            current.open("user", "org", "run"); advanceUntilIdle()
            storage.failWrites = true
            current.edit("question", RunAnswerInput("NON_COMPLIANT")); current.save(); advanceUntilIdle()
            assertTrue(remote.saves.isEmpty())
            assertEquals(DraftSaveStatus.FAILED, current.state.value.localStatus)
            assertTrue(current.state.value.editable)
            storage.failWrites = false; current.save(); advanceUntilIdle()
            assertEquals(1, remote.saves.size)
            assertFalse(current.state.value.pending)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun conflictAndFailedReloadNeverDiscardOrOverwriteLocalAnswers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = TestDraftStorage(); val remote = TestRunRemote(original)
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://api.example.com"))
            val current = vm(remote, drafts); models.put("vm", current)
            current.open("user", "org", "run"); advanceUntilIdle()
            current.edit("question", RunAnswerInput("NON_COMPLIANT", "Mi respuesta")); advanceUntilIdle()
            current.reset(); remote.current = original.copy(rowVersion = 2)
            current.open("user", "org", "run"); advanceUntilIdle()
            assertTrue(current.state.value.conflicted); assertFalse(current.state.value.editable)
            remote.readFailure = RunFailure.CONNECTION
            current.reload(); advanceUntilIdle()
            assertTrue(current.state.value.conflicted)
            assertEquals("Mi respuesta", drafts.read(owner)?.inputs?.get("question")?.observation)
            assertTrue(remote.saves.isEmpty())
            remote.readFailure = null
            current.reload(); advanceUntilIdle()
            assertFalse(current.state.value.conflicted)
            assertNull(drafts.read(owner))
            assertTrue(current.state.value.inputs.isEmpty())
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun deniedRecoveryDoesNotExposeCachedDataAndTransientReadDoesNotDeleteIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = TestDraftStorage(); val remote = TestRunRemote(original)
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://api.example.com"))
            drafts.write(owner, RunDraft(original, mapOf("question" to RunAnswerInput("COMPLIANT", "Privado"))))
            val current = vm(remote, drafts); models.put("vm", current)
            remote.readFailure = RunFailure.CONNECTION
            current.open("user", "org", "run"); advanceUntilIdle()
            assertNull(current.state.value.run)
            assertTrue(current.state.value.inputs.isEmpty())
            assertNotNull(drafts.read(owner))
            remote.readFailure = null
            current.reload(); advanceUntilIdle()
            assertEquals("Privado", current.state.value.inputs["question"]?.observation)
            current.reset(); remote.readFailure = RunFailure.DENIED
            current.open("user", "org", "run"); advanceUntilIdle()
            assertTrue(current.state.value.inputs.isEmpty())
            assertNotNull(drafts.read(owner))
            assertNull(drafts.read(DraftOwner("other-user", "org")))
            assertNull(drafts.read(DraftOwner("user", "other-org")))
            assertNull(ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://other.example.com")).read(owner))
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun leavingDuringAnUncertainWritePreservesRecoveryAndRejectsLateUiResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        try {
            val remote = TestRunRemote(original)
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(TestDraftStorage(), "https://api.example.com"))
            val current = vm(remote, drafts); models.put("vm", current)
            current.open("user", "org", "run"); advanceUntilIdle()
            current.edit("question", RunAnswerInput("COMPLIANT")); advanceUntilIdle()
            remote.saveGate = release
            current.save(); runCurrent()
            var left = false; current.leave { left = true }; runCurrent()
            assertTrue(left)
            assertNull(current.state.value.run)
            assertIs<RunOperation.Save>(drafts.read(owner)?.operation)
            release.complete(Unit); advanceUntilIdle()
            assertNull(current.state.value.run)
            assertNull(current.state.value.userId)
        } finally { release.complete(Unit); models.clear(); Dispatchers.resetMain() }
    }

    @Test fun corruptionIsReportedAndNeverOverwrittenByANewRun() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val storage = TestDraftStorage()
            val drafts = ManageRunDraft(LocalChecklistRunDraftRepository(storage, "https://api.example.com"))
            drafts.write(owner, RunDraft(original, emptyMap()))
            storage.values.keys.toList().forEach { storage.values[it] = "invalid-json" }
            val remote = TestRunRemote(original); val current = vm(remote, drafts); models.put("vm", current)
            current.open("user", "org"); advanceUntilIdle()
            assertEquals(DraftSaveStatus.FAILED, current.state.value.localStatus)
            current.begin("site", "version"); advanceUntilIdle()
            assertTrue(remote.starts.isEmpty())
            assertEquals("invalid-json", storage.values.values.single())
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    private fun vm(remote: TestRunRemote, drafts: ManageRunDraft): ChecklistRunViewModel {
        var ids = 0
        return ChecklistRunViewModel(LoadChecklistRun(remote), StartChecklistRun(remote), SaveChecklistRun(remote), SubmitChecklistRun(remote),
            { "operation-${++ids}" }, { "2026-10-19T12:00:00Z" }, drafts)
    }
    private class TestRunRemote(var current: ChecklistRun) : ChecklistRunReader, ChecklistRunWriter {
        var reads = 0; var submissions = 0; var created = 0; var loseResponse = false
        var readFailure: RunFailure? = null
        var saveGate: CompletableDeferred<Unit>? = null
        val saves = mutableListOf<Triple<Int, String, List<ChecklistAnswer>>>()
        val starts = mutableListOf<StartChecklistInput>()
        private val replies = mutableMapOf<String, ChecklistRun>()
        override suspend fun read(organizationId: String, id: String): RunResult {
            reads++; readFailure?.let { return RunResult.Failure(it) }
            return if (id == current.id) RunResult.Success(current) else RunResult.Failure(RunFailure.NOT_FOUND)
        }
        override suspend fun start(organizationId: String, input: StartChecklistInput): RunResult {
            starts += input
            if (replies[input.clientOperationId] == null) {
                current = current.copy(id = input.id); created++; replies[input.clientOperationId] = current
            }
            return reply(input.clientOperationId)
        }
        override suspend fun save(organizationId: String, id: String, version: Int, key: String, answers: List<ChecklistAnswer>): RunResult {
            saves += Triple(version, key, answers)
            saveGate?.let { withContext(NonCancellable) { it.await() } }
            if (replies[key] == null) {
                if (version != current.rowVersion) return RunResult.Failure(RunFailure.CONFLICT)
                current = current.copy(rowVersion = version + 1, answers = answers); replies[key] = current
            }
            return reply(key)
        }
        private fun reply(key: String): RunResult {
            if (loseResponse) { loseResponse = false; return RunResult.Failure(RunFailure.CONNECTION) }
            return RunResult.Success(replies.getValue(key))
        }
        override suspend fun submit(organizationId: String, id: String, version: Int, key: String): RunResult {
            submissions++; return RunResult.Success(current.copy(status = "SUBMITTED", rowVersion = version + 1, compliancePercent = 100.0))
        }
    }
}
