package pe.bpm.core.offline

import kotlinx.coroutines.test.runTest
import pe.bpm.core.storage.TestDraftStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contrato común: toda implementación de [OfflineDatabase] debe comportarse igual. */
abstract class OfflineDatabaseContract {
    abstract fun create(): OfflineDatabase

    private fun op(id: String, key: String = "key-$id-xxxxxxxx", entity: String = "", status: OutboxStatus = OutboxStatus.PENDING) =
        OutboxOperation(id, "HTTP", "org1", entity, key, 10, "{}", status = status)

    @Test fun recordsAreSeparatedByScopeAndCollection() = runTest {
        val db = create()
        db.putRecord("a", "c1", StoredRecord("k", "1", 5))
        db.putRecord("b", "c1", StoredRecord("k", "2", 6))
        db.putRecord("a", "c2", StoredRecord("k", "3", 7))
        assertEquals("1", db.getRecord("a", "c1", "k")?.json)
        assertEquals("2", db.getRecord("b", "c1", "k")?.json)
        assertEquals(listOf("k"), db.listRecords("a", "c2").map { it.key })
        db.putRecord("a", "c1", StoredRecord("k", "9", 8))
        assertEquals(1, db.listRecords("a", "c1").size)
        assertEquals("9", db.getRecord("a", "c1", "k")?.json)
        db.deleteCollection("a", "c1")
        assertNull(db.getRecord("a", "c1", "k"))
        assertEquals("3", db.getRecord("a", "c2", "k")?.json)
    }

    @Test fun operationsKeepOrderAndRejectDuplicateKeys() = runTest {
        val db = create()
        assertTrue(db.insertOperation("s", op("b")))
        assertTrue(db.insertOperation("s", op("a")))
        assertFalse(db.insertOperation("s", op("c", key = "key-b-xxxxxxxx")))
        assertEquals(listOf("b", "a"), db.operations("s").map { it.id })
        db.updateOperation("s", op("b", status = OutboxStatus.SENT).copy(attempts = 3, dependsOn = listOf("a"), lastError = "x"))
        val updated = db.operations("s").first()
        assertEquals(OutboxStatus.SENT, updated.status)
        assertEquals(listOf("a"), updated.dependsOn)
        assertEquals(3, updated.attempts)
        assertEquals(emptyList(), db.operations("other"))
        db.deleteOperation("s", "b")
        assertEquals(listOf("a"), db.operations("s").map { it.id })
    }

    @Test fun clearRemovesOnlyTheScopeAndClearAllRemovesEverything() = runTest {
        val db = create()
        db.putRecord("a", "c", StoredRecord("k", "1", 1)); db.insertOperation("a", op("x"))
        db.putRecord("b", "c", StoredRecord("k", "1", 1)); db.insertOperation("b", op("y"))
        assertEquals(setOf("a", "b"), db.scopes().toSet())
        db.clear("a")
        assertNull(db.getRecord("a", "c", "k")); assertEquals(0, db.operations("a").size)
        assertEquals(1, db.operations("b").size)
        db.clearAll()
        assertEquals(emptyList(), db.scopes()); assertEquals(0, db.operations("b").size)
    }
}

class InMemoryOfflineDatabaseTest : OfflineDatabaseContract() { override fun create(): OfflineDatabase = InMemoryOfflineDatabase() }
class DocumentOfflineDatabaseTest : OfflineDatabaseContract() { override fun create(): OfflineDatabase = DocumentOfflineDatabase(TestDraftStorage()) }
