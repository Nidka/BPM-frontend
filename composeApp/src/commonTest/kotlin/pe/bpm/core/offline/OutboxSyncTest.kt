package pe.bpm.core.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutboxSyncTest {
    @Test fun enqueueIsIdempotentByKeyEvenAfterRestart() = runTest {
        val h = OfflineHarness(); h.login()
        val first = h.enqueue("key-0001-abcd")
        // Reinicio simulado: otra Outbox sobre la misma base devuelve la misma fila.
        val reopened = OfflineHarness(database = h.database); reopened.login()
        val again = reopened.enqueue("key-0001-abcd")
        assertEquals(first.id, again.id)
        assertEquals(1, reopened.runtime.outbox.snapshot().size)
    }

    @Test fun operationsAreSentInCreationOrder() = runTest {
        val h = OfflineHarness(); h.login()
        val a = h.enqueue("key-a-00000001"); val b = h.enqueue("key-b-00000001", entity = "E1"); val c = h.enqueue("key-c-00000001")
        h.runtime.syncNow()
        assertEquals(listOf(a.id, b.id, c.id), h.server.calls)
    }

    @Test fun dependentWaitsForParentAndIsBlockedWhileParentFails() = runTest {
        val h = OfflineHarness(); h.login()
        val parent = h.enqueue("key-p-00000001", entity = "E1")
        val child = h.enqueue("key-c-00000001", entity = "E1")
        assertEquals(listOf(parent.id), child.dependsOn)
        h.server.script.addLast(SendOutcome.Rejected("no", 422))
        h.runtime.syncNow()
        // El hijo no se intenta mientras el padre no esté enviado.
        assertEquals(listOf(parent.id), h.server.calls)
        assertEquals(OutboxStatus.REJECTED, h.runtime.outbox.snapshot().first().status)
        assertEquals(OutboxStatus.PENDING, h.runtime.outbox.snapshot().last().status)
        // Descartar el padre descarta también al hijo y avisa cuántos.
        assertEquals(2, h.runtime.outbox.discard(parent.id))
        assertEquals(0, h.runtime.outbox.snapshot().size)
    }

    @Test fun retryReturnsRejectedOperationToPendingAndSendsIt() = runTest {
        val h = OfflineHarness(); h.login()
        val op = h.enqueue("key-r-00000001")
        h.server.script.addLast(SendOutcome.Conflict("otro cambió"))
        h.runtime.syncNow()
        assertEquals(OutboxStatus.CONFLICT, h.runtime.outbox.snapshot().single().status)
        assertEquals(1, h.runtime.outbox.attentionCount.first())
        h.runtime.outbox.retry(op.id)
        h.runtime.syncNow()
        assertEquals(OutboxStatus.SENT, h.runtime.outbox.snapshot().single().status)
        assertEquals(0, h.runtime.outbox.attentionCount.first())
    }

    @Test fun retryableFailureUsesExponentialBackoffWithCap() = runTest {
        val h = OfflineHarness(); h.login()
        h.enqueue("key-b-00000001")
        h.server.script.addLast(SendOutcome.Retry("503", 503))
        h.runtime.syncNow()
        var op = h.runtime.outbox.snapshot().single()
        assertEquals(1, op.attempts); assertEquals(h.now + 5_000, op.nextAttemptAt)
        // Antes del plazo no se reintenta; con «Sincronizar ahora» sí.
        h.runtime.engine.syncNow(ignoreBackoff = false)
        assertEquals(1, h.server.calls.size)
        h.now += 5_000
        h.server.script.addLast(SendOutcome.Retry("503", 503))
        h.runtime.engine.syncNow(ignoreBackoff = false)
        op = h.runtime.outbox.snapshot().single()
        assertEquals(2, op.attempts); assertEquals(h.now + 10_000, op.nextAttemptAt)
        assertEquals(OutboxPolicy.MAX_DELAY_MS, OutboxPolicy.backoff(50))
        assertEquals(5_000L, OutboxPolicy.backoff(1)); assertEquals(20_000L, OutboxPolicy.backoff(3))
    }

    @Test fun exhaustedAttemptsPauseUntilManualRetry() = runTest {
        val h = OfflineHarness(); h.login()
        val op = h.enqueue("key-x-00000001")
        repeat(OutboxPolicy.MAX_ATTEMPTS) { h.server.script.addLast(SendOutcome.Retry("503", 503)); h.runtime.syncNow() }
        val paused = h.runtime.outbox.snapshot().single()
        assertTrue(paused.exhausted && paused.needsAttention)
        val calls = h.server.calls.size
        h.runtime.syncNow()
        assertEquals(calls, h.server.calls.size)
        h.runtime.outbox.retry(op.id); h.runtime.syncNow()
        assertEquals(OutboxStatus.SENT, h.runtime.outbox.snapshot().single().status)
    }

    @Test fun networkFailureStopsTheRunAndSessionRenewalDoesNotConsumeAttempts() = runTest {
        val h = OfflineHarness(); h.login()
        h.enqueue("key-1-00000001"); h.enqueue("key-2-00000001")
        h.server.script.addLast(SendOutcome.Retry("sin red", network = true))
        h.runtime.syncNow()
        assertEquals(1, h.server.calls.size)
        h.server.script.addLast(SendOutcome.Retry("sesión", 401, network = true, countAttempt = false))
        h.runtime.syncNow()
        val first = h.runtime.outbox.snapshot().first()
        assertEquals(1, first.attempts)
    }

    @Test fun crashDuringSendResendsWithTheSameKeyWithoutDuplicates() = runTest {
        val h = OfflineHarness(); h.login()
        h.enqueue("key-crash-0001")
        h.server.crashAfterProcessing = true
        assertFailsWith<CancellationException> { h.runtime.syncNow() }
        assertEquals(OutboxStatus.PENDING, h.runtime.outbox.snapshot().single().status)
        // Nueva sesión de aplicación sobre la misma base: reintenta con la misma clave.
        val restarted = OfflineHarness(database = h.database); restarted.login()
        restarted.server.processed.putAll(h.server.processed)
        restarted.runtime.syncNow()
        assertEquals(OutboxStatus.SENT, restarted.runtime.outbox.snapshot().single().status)
        assertEquals(1, restarted.server.processed.values.sum())
    }

    @Test fun interruptedSendingStateIsRecoveredOnNextRun() = runTest {
        val h = OfflineHarness(); h.login()
        val op = h.enqueue("key-s-00000001")
        // Estado dejado por un cierre inesperado entre markSending y complete.
        h.database.updateOperation(h.runtime.active.current!!.id, op.copy(status = OutboxStatus.SENDING))
        h.runtime.syncNow()
        assertEquals(OutboxStatus.SENT, h.runtime.outbox.snapshot().single().status)
    }

    @Test fun missingHandlerRejectsInsteadOfLoopingForever() = runTest {
        val h = OfflineHarness(); h.login()
        h.runtime.outbox.enqueue(OutboxDraft("x1", "DESCONOCIDO", "o", "", "key-u-00000001", "{}", 1))
        h.runtime.syncNow()
        assertEquals(OutboxStatus.REJECTED, h.runtime.outbox.snapshot().single().status)
    }

    /** Criterio del piloto: cinco operaciones acumuladas sin conexión se sincronizan sin pérdida ni duplicados. */
    @Test fun fiveOperationsQueuedOfflineSyncWithoutLossOrDuplicates() = runTest {
        val h = OfflineHarness(connectivity = ManualConnectivityMonitor(false)); h.login()
        val queued = (1..5).map { h.enqueue("key-pilot-000$it", entity = if (it % 2 == 0) "E1" else "") }
        assertEquals(5, h.runtime.outbox.pendingCount.first())
        assertTrue(h.runtime.engine.syncNow().skippedOffline)
        assertEquals(0, h.server.calls.size)
        // Reinicio de la app con la cola intacta y vuelta de la red.
        val restarted = OfflineHarness(database = h.database, connectivity = ManualConnectivityMonitor(false)); restarted.login()
        restarted.connectivity.set(true)
        val first = restarted.runtime.syncNow()
        assertEquals(5, first.sent)
        restarted.runtime.syncNow(); restarted.runtime.syncNow()
        assertEquals(queued.map { it.id }, restarted.server.calls)
        assertEquals(queued.map { it.idempotencyKey }.toSet(), restarted.server.processed.keys)
        assertTrue(restarted.server.processed.values.all { it == 1 })
        assertEquals(0, restarted.runtime.outbox.pendingCount.first())
    }

    @Test fun otherUsersNeverSeeOperationsAndWipeRemovesEverythingWithoutPendingChanges() = runTest {
        val h = OfflineHarness(); h.login("user-1")
        h.enqueue("key-u1-0000001")
        h.runtime.store.put("c", "k", "{}")
        h.runtime.platform.files.save("f1", byteArrayOf(1))
        h.runtime.activate(OfflineScope("https://api.test", "user-2"))
        // Cambiar de usuario no muestra lo del anterior, pero conserva su partición mientras tenga pendientes.
        assertEquals(0, h.runtime.outbox.snapshot().size)
        assertNull(h.runtime.store.get("c", "k"))
        assertEquals(1L, h.runtime.platform.files.size("f1"))
        h.runtime.syncNow()
        assertEquals(emptyList(), h.server.calls)
        // Con la cuenta anterior sin pendientes, el cierre explícito lo borra todo.
        h.runtime.activate(OfflineScope("https://api.test", "user-1"))
        h.runtime.syncNow()
        h.runtime.wipe()
        assertEquals(emptyList(), h.database.scopes())
        assertEquals(0, h.runtime.outbox.snapshot().size)
    }

    @Test fun deactivateHidesDataButSameUserRecoversItAfterReactivation() = runTest {
        val h = OfflineHarness(); h.login()
        h.enqueue("key-d-00000001")
        h.runtime.deactivate()
        assertEquals(0, h.runtime.outbox.snapshot().size)
        h.login()
        assertEquals(1, h.runtime.outbox.snapshot().size)
    }
}
