package pe.bpm.core.offline

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Celular compartido: sin cambios pendientes se puede salir; con pendientes no; conflictos y rechazos se descartan a propósito. */
class LogoutPolicyTest {
    @Test fun withNothingPendingLogoutIsAllowed() = runTest {
        val h = OfflineHarness(); h.login()
        assertEquals(LogoutGate.Clear, LogoutPolicy.evaluate(h.runtime.outbox.snapshot()))
        // Lo ya enviado tampoco cuenta.
        h.enqueue("key-a-00000001"); h.runtime.syncNow()
        assertEquals(LogoutGate.Clear, LogoutPolicy.evaluate(h.runtime.outbox.snapshot()))
    }

    @Test fun pendingChangesBlockLogoutAndTheMessageCountsThem() = runTest {
        val h = OfflineHarness(); h.login()
        h.connectivity.set(false)
        h.enqueue("key-a-00000001"); h.enqueue("key-b-00000001")
        val gate = assertIs<LogoutGate.Blocked>(LogoutPolicy.evaluate(h.runtime.outbox.snapshot()))
        assertEquals(2, gate.unsent)
        assertEquals("Tienes 2 cambios sin enviar. Conéctate y sincroniza antes de salir", LogoutPolicy.blockedMessage(gate.unsent))
        assertEquals("Tienes 1 cambio sin enviar. Conéctate y sincroniza antes de salir", LogoutPolicy.blockedMessage(1))
        // Al conectarse y sincronizar, ya se puede salir.
        h.connectivity.set(true)
        h.runtime.syncNow()
        assertEquals(LogoutGate.Clear, LogoutPolicy.evaluate(h.runtime.outbox.snapshot()))
    }

    @Test fun exhaustedRetriesStillBlockBecauseTheyAreNotDiscardable() = runTest {
        val h = OfflineHarness(); h.login()
        val op = h.enqueue("key-a-00000001")
        val exhausted = op.copy(attempts = OutboxPolicy.MAX_ATTEMPTS)
        assertTrue(exhausted.exhausted)
        assertIs<LogoutGate.Blocked>(LogoutPolicy.evaluate(listOf(exhausted)))
    }

    @Test fun conflictsAndRejectionsCanBeDiscardedExplicitly() = runTest {
        val h = OfflineHarness(); h.login()
        val rejected = h.enqueue("key-a-00000001", entity = "E1")
        val dependent = h.enqueue("key-b-00000001", entity = "E1")
        h.server.script.addLast(SendOutcome.Rejected("no", 422))
        h.runtime.syncNow()
        // El dependiente espera al rechazado y se descarta con él: no bloquea por sí solo.
        val gate = assertIs<LogoutGate.NeedsDiscard>(LogoutPolicy.evaluate(h.runtime.outbox.snapshot()))
        assertEquals(listOf(rejected.id), gate.operationIds)
        assertEquals(1, gate.dependents)
        assertEquals(dependent.id, h.runtime.outbox.snapshot().last().id)
        // Tras la confirmación se descarta y ya se puede salir.
        gate.operationIds.forEach { h.runtime.outbox.discard(it) }
        assertEquals(LogoutGate.Clear, LogoutPolicy.evaluate(h.runtime.outbox.snapshot()))
    }

    @Test fun anIndependentPendingChangeBlocksEvenIfAnotherOneIsRejected() = runTest {
        val h = OfflineHarness(); h.login()
        h.enqueue("key-a-00000001", entity = "E1")
        h.server.script.addLast(SendOutcome.Conflict("otro cambió"))
        h.runtime.syncNow()
        h.connectivity.set(false)
        h.enqueue("key-b-00000001", entity = "E2")
        val gate = assertIs<LogoutGate.Blocked>(LogoutPolicy.evaluate(h.runtime.outbox.snapshot()))
        assertEquals(1, gate.unsent)
    }

    @Test fun logoutWipesPersonalDataButNeverTheDeviceVideoStore() = runTest {
        val personal = InMemoryPrivateFileStore(durable = true)
        val vault = InMemoryPrivateFileStore(durable = true)
        val runtime = OfflineRuntime(OfflinePlatform(files = personal, videos = vault), { 1L }, { "op" })
        runtime.activate(OfflineScope("https://api.test", "user-1"))
        personal.save("photo-1.jpg", byteArrayOf(1, 2, 3))
        vault.save("video-m1-0123456789abcdef.mp4", byteArrayOf(9, 9, 9))
        runtime.wipe()
        assertEquals(null, personal.read("photo-1.jpg"))
        assertEquals(3L, vault.size("video-m1-0123456789abcdef.mp4"))
        // Otra cuenta que inicia sesión en el mismo celular tampoco borra los videos.
        runtime.activate(OfflineScope("https://api.test", "user-2"))
        assertEquals(3L, vault.size("video-m1-0123456789abcdef.mp4"))
    }
}
