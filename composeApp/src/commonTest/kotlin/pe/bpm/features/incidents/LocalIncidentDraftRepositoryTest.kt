package pe.bpm.features.incidents

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.DraftStorage
import pe.bpm.features.incidents.data.*
import pe.bpm.features.incidents.domain.*

class LocalIncidentDraftRepositoryTest {
    private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
    private val site2 = "49d000f4-e487-41cf-a166-741c98cd7e05"

    @Test
    fun documentsIsolateAccountOrganizationSiteAndApiOriginAndKeepExactPending() = runTest {
        val values = mutableMapOf<String, String>()
        val storage =
            object : DraftStorage {
                override suspend fun read(key: String) = values[key]

                override suspend fun write(key: String, value: String) {
                    values[key] = value
                }

                override suspend fun remove(key: String) {
                    values.remove(key)
                }
            }
        val repo = LocalIncidentDraftRepository(storage, "https://api.example")
        val owner = DraftOwner("alice", "org")
        val form =
            IncidentForm(
                id,
                "2026-11-03T00:00:00.123456789Z",
                "REPORT",
                mapOf("description" to "Problema", "catalogId" to id),
            )
        val draft = IncidentDraft(id, form, prepareIncident(form, id, "key-12345678"))
        repo.write(owner, id, draft)
        assertEquals(draft, repo.read(owner, id))
        assertNull(repo.read(DraftOwner("bob", "org"), id))
        assertNull(repo.read(DraftOwner("alice", "other"), id))
        assertNull(repo.read(owner, site2))
        assertNull(LocalIncidentDraftRepository(storage, "https://other.example").read(owner, id))
        assertFailsWith<IllegalArgumentException> {
            repo.write(owner, id, draft.copy(pending = draft.pending!!.copy(body = "{}")))
        }
        assertEquals(draft, repo.read(owner, id))
    }
}
