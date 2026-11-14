package pe.bpm.features.checklists

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.offline.HttpOutboxHandler
import pe.bpm.core.offline.OfflineHarness
import pe.bpm.core.offline.OutboxStatus
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.checklists.data.LegacyChecklistMigration
import pe.bpm.features.checklists.data.LocalChecklistOfflineRepository
import pe.bpm.features.checklists.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Migración de la cola antigua de controles preparados (formato guardado por usuarios) a la Outbox. */
class LegacyChecklistMigrationTest {
    private val owner = DraftOwner("chef", "org")
    private val item = ChecklistItem("question", "Limpieza", "¿Limpio?", ChecklistResponseType.COMPLIANCE, 1, true, "", null, null, false)
    private fun run(id: String) = ChecklistRun(id, "org", "site", "version", "chef", "DRAFT", 3, listOf(item), emptyList())

    private class FakeRuns : ChecklistRunReader {
        val runs = mutableMapOf<String, ChecklistRun>()
        override suspend fun read(organizationId: String, id: String): RunResult = runs[id]?.let { RunResult.Success(it) } ?: RunResult.Failure(RunFailure.NOT_FOUND)
    }

    private suspend fun legacyWith(storage: TestDraftStorage, vararg finalize: Pair<String, Boolean>): LocalChecklistOfflineRepository {
        val repository = LocalChecklistOfflineRepository(storage, "https://api.test")
        val runs = FakeRuns(); var n = 0
        val manager = ManageOfflineChecklists(repository, LoadChecklistRun(runs)) { "legacy-key-${++n}-abc" }
        for ((id, fin) in finalize) {
            runs.runs[id] = run(id)
            manager.prepare(owner, id)
            manager.edit(owner, id, "question", RunAnswerInput("COMPLIANT", "ok $id"))
            manager.enqueue(owner, id, finalize = fin)
        }
        return repository
    }

    @Test fun pendingUploadsMoveToOutboxKeepingKeysAndAreRemovedFromLegacy() = runTest {
        val storage = TestDraftStorage()
        val legacy = legacyWith(storage, "run-a" to true, "run-b" to false)
        val h = OfflineHarness(); h.login("chef")
        val migration = LegacyChecklistMigration(legacy, h.runtime.outbox, { h.runtime.newId() }, { h.now })
        assertEquals(2, migration.migrate(owner))
        val ops = h.runtime.outbox.snapshot()
        // run-a: guardar + enviar (encadenados por entidad); run-b: solo guardar.
        assertEquals(listOf("legacy-key-1-abc", "legacy-key-2-abc", "legacy-key-3-abc"), ops.map { it.idempotencyKey })
        assertEquals(listOf(ops[0].id), ops[1].dependsOn)
        val save = Json.decodeFromString(pe.bpm.core.offline.HttpOperationPayload.serializer(), ops[0].payload)
        assertEquals("/api/v1/organizations/org/checklist-runs/run-a/answers", save.path)
        assertEquals(3, Json.parseToJsonElement(save.body).jsonObject["rowVersion"]!!.jsonPrimitive.content.toInt())
        val submit = Json.decodeFromString(pe.bpm.core.offline.HttpOperationPayload.serializer(), ops[1].payload)
        assertTrue(submit.path.endsWith("/submit") && submit.refreshVersionFrom.endsWith("/checklist-runs/run-a"))
        assertEquals(0, legacy.read(owner).size)
        // Repetir (cierre inesperado entre registrar y quitar) no duplica nada.
        assertEquals(0, migration.migrate(owner))
        assertEquals(3, h.runtime.outbox.snapshot().size)
    }

    @Test fun crashBetweenEnqueueAndRemovalDoesNotDuplicate() = runTest {
        val storage = TestDraftStorage()
        val legacy = legacyWith(storage, "run-a" to true)
        val h = OfflineHarness(); h.login("chef")
        val migration = LegacyChecklistMigration(legacy, h.runtime.outbox, { h.runtime.newId() }, { h.now })
        migration.migrate(owner)
        // Simula que la quitada de la cola antigua no llegó a persistirse: se restaura y se repite.
        val again = legacyWith(TestDraftStorage(), "run-a" to true)
        assertEquals(1, LegacyChecklistMigration(again, h.runtime.outbox, { h.runtime.newId() }, { h.now }).migrate(owner))
        assertEquals(2, h.runtime.outbox.snapshot().size)
    }

    @Test fun entriesNeedingAttentionOrWithoutUploadStayInLegacyEditor() = runTest {
        val storage = TestDraftStorage()
        val legacy = legacyWith(storage)
        val runs = FakeRuns(); runs.runs["edit"] = run("edit")
        ManageOfflineChecklists(legacy, LoadChecklistRun(runs)) { "k-abcdefgh" }.prepare(owner, "edit")
        val h = OfflineHarness(); h.login("chef")
        assertEquals(0, LegacyChecklistMigration(legacy, h.runtime.outbox, { h.runtime.newId() }, { h.now }).migrate(owner))
        assertEquals(1, legacy.read(owner).size)
        assertEquals(0, h.runtime.outbox.snapshot().size)
    }

    @Test fun submitReadsTheCurrentVersionFromTheServerAfterTheSave() = runTest {
        val storage = TestDraftStorage()
        val legacy = legacyWith(storage, "run-a" to true)
        val h = OfflineHarness(); h.login("chef")
        LegacyChecklistMigration(legacy, h.runtime.outbox, { h.runtime.newId() }, { h.now }).migrate(owner)
        val sent = mutableListOf<Triple<String, String, String>>()
        var version = 3
        val raw = createHttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            sent += Triple(request.method.value, path, body)
            if (request.method.value == "PUT") version = 4
            respond("""{"data":{"rowVersion":$version}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        h.runtime.register(HttpOutboxHandler.KIND, HttpOutboxHandler(raw, "https://api.test", { "tok" }))
        val report = h.runtime.syncNow()
        assertEquals(2, report.sent)
        assertTrue(h.runtime.outbox.snapshot().all { it.status == OutboxStatus.SENT })
        val submit = sent.last()
        assertEquals("POST", submit.first)
        assertEquals(4, Json.parseToJsonElement(submit.third).jsonObject["rowVersion"]!!.jsonPrimitive.content.toInt())
        // La lectura de versión no es una escritura: el orden fue PUT, GET, POST.
        assertEquals(listOf("PUT", "GET", "POST"), sent.map { it.first })
    }
}
