package pe.bpm.features.usage

import kotlinx.coroutines.test.runTest
import pe.bpm.core.offline.OfflineHarness
import pe.bpm.features.usage.data.PersistentUsageEventQueue
import pe.bpm.features.usage.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals

class PersistentUsageEventQueueTest {
    private fun item(n: Int, user: String = "user-1", org: String = "org1") = QueuedUsageEvent(user, org,
        UsageEvent("event-$n", "session", UsageEventType.MODULE_VISIT, UsageModule.ALERTS, "2026-01-01T00:00:${n.toString().padStart(2, '0')}Z", null))

    @Test fun eventsSurviveRestartAndKeepOrder() = runTest {
        val h = OfflineHarness(); h.login()
        val first = PersistentUsageEventQueue(h.runtime.store)
        (3 downTo 1).forEach { first.enqueue(item(it)) }
        val restarted = OfflineHarness(database = h.database); restarted.login()
        val second = PersistentUsageEventQueue(restarted.runtime.store)
        assertEquals(3, second.size())
        assertEquals(listOf("event-1", "event-2", "event-3"), second.peek("user-1", 10).map { it.event.id })
        second.remove(setOf("event-1", "event-2"))
        assertEquals(listOf("event-3"), second.peek("user-1", 10).map { it.event.id })
    }

    @Test fun boundedQueueDropsTheOldestAndNeverMixesOrganizations() = runTest {
        val h = OfflineHarness(); h.login()
        val queue = PersistentUsageEventQueue(h.runtime.store, capacity = 3)
        (1..5).forEach { queue.enqueue(item(it)) }
        assertEquals(listOf("event-3", "event-4", "event-5"), queue.peek("user-1", 10).map { it.event.id })
        queue.enqueue(item(6, org = "org2"))
        assertEquals(listOf("event-4", "event-5"), queue.peek("user-1", 10).map { it.event.id }.also { })
    }

    @Test fun logoutWipeRemovesPendingUsage() = runTest {
        val h = OfflineHarness(); h.login()
        val queue = PersistentUsageEventQueue(h.runtime.store)
        queue.enqueue(item(1))
        h.runtime.wipe()
        assertEquals(0, queue.size())
    }
}
