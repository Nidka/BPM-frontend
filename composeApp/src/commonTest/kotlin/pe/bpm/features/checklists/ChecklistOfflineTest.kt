package pe.bpm.features.checklists

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.DraftStorage
import pe.bpm.features.checklists.data.LocalChecklistOfflineRepository
import pe.bpm.features.checklists.domain.*
import kotlin.test.*

/** Comprueba recuperación real de documentos y el contrato de reintentos, sin cuentas externas. */
class ChecklistOfflineTest {
    private val owner = DraftOwner("chef", "org")
    private val item = ChecklistItem("question", "Limpieza", "¿La superficie está limpia?", ChecklistResponseType.COMPLIANCE, 1, true, "", null, null, false)
    private fun run(id: String) = ChecklistRun(id, "org", "site", "version", "chef", "DRAFT", 1, listOf(item), emptyList())
    private fun manager(storage: Storage, remote: Remote, origin: String = "https://api.example.com") =
        ManageOfflineChecklists(LocalChecklistOfflineRepository(storage, origin), LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }

    @Test fun fiveControlsSurviveRestartWithoutSharingAccountsTenantsOrOrigins() = runTest {
        val storage = Storage(); val remote = Remote()
        val first = manager(storage, remote)
        repeat(5) { index ->
            val id = "run-$index"; remote.runs[id] = run(id)
            first.prepare(owner, id)
            first.edit(owner, id, "question", RunAnswerInput("COMPLIANT", "Sí, revisión $index"))
            first.enqueue(owner, id, finalize = true)
        }
        val second = manager(storage, remote)
        val entries = second.list(owner)
        assertEquals(5, entries.size)
        assertTrue(entries.all { it.upload?.finalize == true && it.status == OfflineRunStatus.PENDING })
        assertEquals("Sí, revisión 3", entries.single { it.run.id == "run-3" }.inputs["question"]?.observation)
        assertTrue(second.list(DraftOwner("another", "org")).isEmpty())
        assertTrue(second.list(DraftOwner("chef", "foreign")).isEmpty())
        assertTrue(manager(storage, remote, "https://other.example.com").list(owner).isEmpty())
    }

    @Test fun confirmedPhotoCountSurvivesRestartWithoutReplacingTextOrFrozenUpload() = runTest {
        val storage = Storage(); val remote = Remote()
        remote.runs["run"] = run("run").copy(items = listOf(item.copy(evidenceRequiredOnFailure = true)))
        val first = manager(storage, remote)
        first.prepare(owner, "run")
        first.edit(owner, "run", "question", RunAnswerInput("NON_COMPLIANT", "Requiere limpieza"))
        assertFailsWith<IllegalArgumentException> { first.enqueue(owner, "run", true) }
        first.evidenceCount(owner, "run", "question", 1)
        val restored = manager(storage, remote)
        val entry = restored.list(owner).single()
        assertEquals(1, entry.run.evidenceCounts["question"])
        assertEquals("Requiere limpieza", entry.inputs["question"]?.observation)
        restored.enqueue(owner, "run", true)
        val frozen = restored.list(owner).single()
        restored.evidenceCount(owner, "run", "question", 0)
        assertEquals(frozen, restored.list(owner).single())
    }

    @Test fun lostSaveResponseRetriesIdenticalBodyAndKeyBeforeSubmittingOnce() = runTest {
        val storage = Storage(); val remote = Remote(); remote.runs["run"] = run("run")
        val repo = LocalChecklistOfflineRepository(storage, "origin")
        val local = ManageOfflineChecklists(repo, LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }
        local.prepare(owner, "run"); local.edit(owner, "run", "question", RunAnswerInput("COMPLIANT")); local.enqueue(owner, "run", true)
        val sync = SyncOfflineChecklists(repo, SaveChecklistRun(remote), SubmitChecklistRun(remote))
        remote.loseSaveResponse = true
        sync(owner)
        assertEquals(OfflineRunStatus.PENDING, local.list(owner).single().status)
        assertEquals(0, remote.submissions)
        SyncOfflineChecklists(LocalChecklistOfflineRepository(storage, "origin"), SaveChecklistRun(remote), SubmitChecklistRun(remote))(owner)
        assertEquals(remote.saves.first(), remote.saves.last())
        assertEquals(1, remote.savedVersions)
        assertEquals(1, remote.submissions)
        assertEquals("SUBMITTED", local.list(owner).single().run.status)
        assertEquals(OfflineRunStatus.CONFIRMED, local.list(owner).single().status)
    }

    @Test fun lostSubmitResponseAndFailedCheckpointNeverGenerateAnotherKey() = runTest {
        val storage = Storage(); val remote = Remote(); remote.runs["run"] = run("run")
        val repo = LocalChecklistOfflineRepository(storage, "origin")
        val local = ManageOfflineChecklists(repo, LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }
        local.prepare(owner, "run"); local.edit(owner, "run", "question", RunAnswerInput("COMPLIANT")); local.enqueue(owner, "run", true)
        val sync = SyncOfflineChecklists(repo, SaveChecklistRun(remote), SubmitChecklistRun(remote))
        remote.loseSubmitResponse = true; sync(owner)
        assertNotNull(local.list(owner).single().upload?.savedRun)
        sync(owner)
        assertEquals(1, remote.savedVersions); assertEquals(1, remote.submissions)
        assertEquals(remote.submitKeys.first(), remote.submitKeys.last())
        assertEquals(OfflineRunStatus.CONFIRMED, local.list(owner).single().status)
    }

    @Test fun versionConflictPreservesAnswersAndStopsAutomaticRetries() = runTest {
        val storage = Storage(); val remote = Remote(); remote.runs["run"] = run("run")
        val repo = LocalChecklistOfflineRepository(storage, "origin")
        val local = ManageOfflineChecklists(repo, LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }
        local.prepare(owner, "run"); local.edit(owner, "run", "question", RunAnswerInput("NON_COMPLIANT", "Falta limpiar")); local.enqueue(owner, "run", false)
        remote.failure = RunFailure.CONFLICT
        val sync = SyncOfflineChecklists(repo, SaveChecklistRun(remote), SubmitChecklistRun(remote))
        sync(owner); val calls = remote.saves.size; sync(owner)
        val entry = local.list(owner).single()
        assertEquals(OfflineRunStatus.ATTENTION, entry.status)
        assertEquals(calls, remote.saves.size)
        assertEquals("Falta limpiar", entry.inputs["question"]?.observation)
        assertNotNull(entry.upload)
    }

    @Test fun failedDurableWriteCannotBeAdvertisedOrSent() = runTest {
        val storage = Storage(); val remote = Remote(); remote.runs["run"] = run("run")
        val repo = LocalChecklistOfflineRepository(storage, "origin")
        val local = ManageOfflineChecklists(repo, LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }
        local.prepare(owner, "run"); local.edit(owner, "run", "question", RunAnswerInput("COMPLIANT"))
        storage.failWrite = true
        assertFailsWith<IllegalStateException> { local.enqueue(owner, "run", true) }
        storage.failWrite = false
        SyncOfflineChecklists(repo, SaveChecklistRun(remote), SubmitChecklistRun(remote))(owner)
        assertTrue(remote.saves.isEmpty())
        assertEquals(OfflineRunStatus.LOCAL, local.list(owner).single().status)
    }

    @Test fun preparationDoesNotAcceptAnotherExecutorAndDoesNotReplaceExistingEdits() = runTest {
        val storage = Storage(); val remote = Remote(); remote.runs["run"] = run("run")
        val local = manager(storage, remote)
        local.prepare(owner, "run"); local.edit(owner, "run", "question", RunAnswerInput("COMPLIANT", "Conservar"))
        local.prepare(owner, "run")
        assertEquals("Conservar", local.list(owner).single().inputs["question"]?.observation)
        remote.runs["foreign"] = run("foreign").copy(performedBy = "other")
        assertIs<RunResult.Failure>(local.prepare(owner, "foreign"))
        assertEquals(1, local.list(owner).size)
    }

    @Test fun simultaneousEditsDoNotLoseTheOtherControl() = runTest {
        val storage = Storage(); val remote = Remote()
        remote.runs["a"] = run("a"); remote.runs["b"] = run("b")
        val local = manager(storage, remote); local.prepare(owner, "a"); local.prepare(owner, "b")
        val otherTab = manager(storage, remote)
        coroutineScope { listOf("a", "b").map { id -> launch { (if (id == "a") local else otherTab).edit(owner, id, "question", RunAnswerInput("COMPLIANT", id)) } }.joinAll() }
        assertEquals(setOf("a", "b"), local.list(owner).map { it.inputs["question"]!!.observation }.toSet())
    }

    @Test fun lostLocalCheckpointAfterSaveReplaysSaveBeforeFinalizing() = runTest {
        val storage = Storage(); val remote = Remote(); remote.runs["run"] = run("run")
        val repo = LocalChecklistOfflineRepository(storage, "origin")
        val local = ManageOfflineChecklists(repo, LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }
        local.prepare(owner, "run"); local.edit(owner, "run", "question", RunAnswerInput("COMPLIANT")); local.enqueue(owner, "run", true)
        remote.afterSave = { storage.failWrite = true }
        val sync = SyncOfflineChecklists(repo, SaveChecklistRun(remote), SubmitChecklistRun(remote))
        assertFailsWith<IllegalStateException> { sync(owner) }
        assertEquals(0, remote.submissions)
        storage.failWrite = false; remote.afterSave = {}
        sync(owner)
        assertEquals(remote.saves.first(), remote.saves.last())
        assertEquals(1, remote.savedVersions); assertEquals(1, remote.submissions)
        assertEquals("SUBMITTED", local.list(owner).single().run.status)
    }

    @Test fun retriesAreBoundedAndExplicitResolutionReadsServerWithoutOverwritingIt() = runTest {
        val storage = Storage(); val remote = Remote(); remote.runs["run"] = run("run")
        val repo = LocalChecklistOfflineRepository(storage, "origin")
        val local = ManageOfflineChecklists(repo, LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }
        local.prepare(owner, "run"); local.edit(owner, "run", "question", RunAnswerInput("NON_COMPLIANT")); local.enqueue(owner, "run", false)
        remote.failure = RunFailure.CONNECTION
        val sync = SyncOfflineChecklists(repo, SaveChecklistRun(remote), SubmitChecklistRun(remote))
        repeat(8) { sync(owner) }
        assertEquals(3, remote.saves.size)
        local.retry(owner); remote.failure = RunFailure.CONFLICT; sync(owner)
        val writes = remote.saves.size
        remote.runs["run"] = run("run").copy(rowVersion = 7, answers = listOf(ChecklistAnswer("question", "COMPLIANT")))
        assertIs<RunResult.Success>(local.useServerVersion(owner, "run"))
        assertEquals(writes, remote.saves.size)
        val recovered = local.list(owner).single()
        assertNull(recovered.upload); assertEquals(7, recovered.run.rowVersion)
        assertEquals("COMPLIANT", recovered.inputs["question"]?.value)
    }

    @Test fun fiveQueuedControlsFinalizeWithoutDuplicatingWhenOneResponseIsLost() = runTest {
        val storage = Storage(); val remote = Remote()
        val repo = LocalChecklistOfflineRepository(storage, "origin")
        val local = ManageOfflineChecklists(repo, LoadChecklistRun(remote)) { "offline-key-${++remote.ids}" }
        repeat(5) { i ->
            val id = "run-$i"; remote.runs[id] = run(id)
            local.prepare(owner, id); local.edit(owner, id, "question", RunAnswerInput("COMPLIANT")); local.enqueue(owner, id, true)
        }
        val sync = SyncOfflineChecklists(repo, SaveChecklistRun(remote), SubmitChecklistRun(remote))
        remote.loseSaveResponse = true; sync(owner); sync(owner)
        assertEquals(5, remote.savedVersions); assertEquals(5, remote.submissions)
        assertEquals(5, local.list(owner).count { it.run.status == "SUBMITTED" && it.status == OfflineRunStatus.CONFIRMED })
    }

    @Test fun concurrentTabsMergeDifferentQuestionsAndRejectChangingTheSameAnswerSilently() = runTest {
        val storage = Storage(); val remote = Remote()
        remote.runs["run"] = run("run").copy(items = listOf(item, item.copy(id = "other", displayOrder = 2)))
        val local = manager(storage, remote); local.prepare(owner, "run")
        val before = emptyMap<String, RunAnswerInput>()
        local.replaceInputs(owner, "run", mapOf("question" to RunAnswerInput("COMPLIANT")), before)
        local.replaceInputs(owner, "run", mapOf("other" to RunAnswerInput("NON_COMPLIANT")), before)
        assertEquals(2, local.list(owner).single().inputs.size)
        assertFailsWith<OfflineInputConflict> {
            local.replaceInputs(owner, "run", mapOf("question" to RunAnswerInput("NON_COMPLIANT")), before)
        }
        assertEquals("COMPLIANT", local.list(owner).single().inputs["question"]?.value)
    }

    private class Storage : DraftStorage {
        val values = mutableMapOf<String, String>(); var failWrite = false
        override suspend fun read(key: String): String? { val raw = values[key]; yield(); return raw }
        override suspend fun write(key: String, value: String) { check(!failWrite); values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
        override suspend fun compareAndSet(key: String, expected: String?, value: String): Boolean {
            check(!failWrite)
            if (values[key] != expected) return false
            values[key] = value; return true
        }
    }
    private class Remote : ChecklistRunReader, ChecklistRunWriter {
        val runs = mutableMapOf<String, ChecklistRun>(); var ids = 0; var failure: RunFailure? = null
        var afterSave: () -> Unit = {}
        var loseSaveResponse = false; var loseSubmitResponse = false; var savedVersions = 0; var submissions = 0
        val saves = mutableListOf<Triple<Int, String, List<ChecklistAnswer>>>()
        val submitKeys = mutableListOf<String>(); val replay = mutableMapOf<String, ChecklistRun>()
        override suspend fun read(organizationId: String, id: String): RunResult = runs[id]?.let { RunResult.Success(it) } ?: RunResult.Failure(RunFailure.NOT_FOUND)
        override suspend fun start(organizationId: String, input: StartChecklistInput): RunResult = error("No crear sin autorización")
        override suspend fun save(organizationId: String, id: String, version: Int, key: String, answers: List<ChecklistAnswer>): RunResult {
            saves += Triple(version, key, answers); failure?.let { return RunResult.Failure(it) }
            val result = replay.getOrPut(key) { savedVersions++; runs.getValue(id).copy(rowVersion = version + 1, answers = answers).also { runs[id] = it } }
            afterSave()
            if (loseSaveResponse) { loseSaveResponse = false; return RunResult.Failure(RunFailure.CONNECTION) }
            return RunResult.Success(result)
        }
        override suspend fun submit(organizationId: String, id: String, version: Int, key: String): RunResult {
            submitKeys += key
            val result = replay.getOrPut(key) { submissions++; runs.getValue(id).copy(rowVersion = version + 1, status = "SUBMITTED", compliancePercent = 100.0).also { runs[id] = it } }
            if (loseSubmitResponse) { loseSubmitResponse = false; return RunResult.Failure(RunFailure.CONNECTION) }
            return RunResult.Success(result)
        }
    }
}
