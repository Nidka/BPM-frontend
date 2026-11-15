package pe.bpm.core.offline

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Celular compartido: otra cuenta no borra, ve ni envía los cambios pendientes de la anterior. */
class AccountSwitchTest {
    private val day = 24L * 60 * 60 * 1000
    private fun scopeId(user: String) = OfflineScope("https://api.test", user).id

    private suspend fun pendingOwner(): OfflineHarness {
        val a = OfflineHarness(user = "ana"); a.login("ana")
        a.connectivity.set(false)
        a.enqueue("key-ana-00000001")
        a.runtime.store.put("cache", "dato", "{\"secreto\":1}")
        a.runtime.store.put(RuntimeOfflineIdentity.PROFILE_COLLECTION, RuntimeOfflineIdentity.PROFILE_KEY, "{\"email\":\"katherine@gmail.com\"}")
        return a
    }

    @Test fun switchingAccountsKeepsThePartitionWithPendingChanges() = runTest {
        val a = pendingOwner()
        val b = OfflineHarness(database = a.database, user = "beto"); b.login("beto")
        assertEquals(1, a.database.operations(scopeId("ana")).size)
        assertTrue(scopeId("ana") in a.database.scopes())
    }

    @Test fun theNewAccountNeverSeesNorSendsThePreviousAccountsData() = runTest {
        val a = pendingOwner()
        val b = OfflineHarness(database = a.database, user = "beto"); b.login("beto")
        assertTrue(b.runtime.outbox.snapshot().isEmpty())
        assertNull(b.runtime.store.get("cache", "dato"))
        b.runtime.syncNow()
        assertTrue(b.server.calls.isEmpty())
        // Solo se expone el recuento y el correo enmascarado.
        val other = b.runtime.otherAccountsPending().single()
        assertEquals(1, other.count)
        assertEquals("ka•••@gmail.com", other.maskedEmail)
        assertEquals("Hay 1 cambio sin enviar de otra persona (ka•••@gmail.com). Se enviarán cuando esa persona vuelva a entrar", other.notice())
        assertFalse(other.notice().contains("katherine"))
    }

    @Test fun thePreviousAccountReturnsAndItsQueueIsSent() = runTest {
        val a = pendingOwner()
        val b = OfflineHarness(database = a.database, user = "beto"); b.login("beto")
        val again = OfflineHarness(database = a.database, user = "ana"); again.login("ana")
        assertEquals(1, again.runtime.outbox.snapshot().size)
        assertTrue(again.runtime.otherAccountsPending().isEmpty())
        assertEquals(1, again.runtime.syncNow().sent)
        assertEquals(1, again.server.calls.size)
        assertEquals("{\"secreto\":1}", again.runtime.store.get("cache", "dato")?.json)
    }

    @Test fun switchingAccountsWithNothingPendingDeletesThePartition() = runTest {
        val a = OfflineHarness(user = "ana"); a.login("ana")
        a.enqueue("key-ana-00000002"); a.runtime.syncNow()
        a.runtime.store.put("cache", "dato", "{}")
        val b = OfflineHarness(database = a.database, user = "beto"); b.login("beto")
        assertFalse(scopeId("ana") in a.database.scopes())
        assertTrue(b.runtime.otherAccountsPending().isEmpty())
    }

    @Test fun conflictsAndRejectionsAlsoKeepThePartition() = runTest {
        val a = OfflineHarness(user = "ana"); a.login("ana")
        a.enqueue("key-ana-00000003")
        a.server.script.addLast(SendOutcome.Rejected("no", 422))
        a.runtime.syncNow()
        val b = OfflineHarness(database = a.database, user = "beto"); b.login("beto")
        assertTrue(scopeId("ana") in a.database.scopes())
    }

    @Test fun aPartitionWithPendingChangesExpiresAfterThirtyDays() = runTest {
        val a = pendingOwner()
        val start = a.now
        val soon = OfflineHarness(database = a.database, now = start + 29 * day, user = "beto"); soon.login("beto")
        assertTrue(scopeId("ana") in a.database.scopes())
        val late = OfflineHarness(database = a.database, now = start + 31 * day, user = "carla"); late.login("carla")
        assertFalse(scopeId("ana") in a.database.scopes())
        assertTrue(late.runtime.otherAccountsPending().isEmpty())
    }

    @Test fun explicitLogoutKeepsAnotherAccountsPendingPartitionAndPersonalFiles() = runTest {
        val db = InMemoryOfflineDatabase()
        val files = InMemoryPrivateFileStore(durable = true)
        val a = OfflineRuntime(OfflinePlatform(database = db, files = files), { 1_000L }, { "op-a" })
        a.activate(OfflineScope("https://api.test", "ana"))
        a.outbox.enqueue(OutboxDraft("op-1", "HTTP", "org1", "", "key-ana-00000004", "{}", 1_000L))
        files.save("foto-1.jpg", byteArrayOf(1))
        val b = OfflineRuntime(OfflinePlatform(database = db, files = files), { 2_000L }, { "op-b" })
        b.activate(OfflineScope("https://api.test", "beto"))
        b.wipe()
        assertTrue(scopeId("ana") in db.scopes())
        assertEquals(1L, files.size("foto-1.jpg"))
        // Sin nada pendiente en ninguna cuenta, el cierre borra todo.
        a.activate(OfflineScope("https://api.test", "ana"))
        a.outbox.discard("op-1")
        a.wipe()
        assertTrue(db.scopes().isEmpty())
        assertNull(files.read("foto-1.jpg"))
    }

    @Test fun emailsAreMaskedKeepingTwoLettersAndTheDomain() {
        assertEquals("ka•••@gmail.com", maskEmail("katherine@gmail.com"))
        assertEquals("a•••@x.pe", maskEmail("a@x.pe"))
        assertEquals("a•••@x.pe", maskEmail("ab@x.pe"))
        assertEquals("•••", maskEmail("sin-arroba"))
    }
}
