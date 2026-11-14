package pe.bpm.features.usage

import androidx.lifecycle.viewModelScope
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.usage.data.RemoteUsageEventSink
import pe.bpm.features.usage.domain.*
import pe.bpm.features.usage.presentation.UsageViewModel

private const val NOW = 1_794_322_800_000L

private class FakeSink(var result: UsageSendResult = UsageSendResult.Accepted) : UsageEventSink {
    val batches = mutableListOf<Pair<String, List<UsageEvent>>>()
    override suspend fun send(organizationId: String, events: List<UsageEvent>): UsageSendResult { batches += organizationId to events; return result }
}

private fun event(n: Int, at: Long = NOW) = UsageEvent("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}", "s", UsageEventType.MODULE_VISIT, UsageModule.HOME,
    kotlin.time.Instant.fromEpochMilliseconds(at).toString(), 1)

class UsageBatcherTest {
    private fun batcher(sink: UsageEventSink, queue: UsageEventQueue = InMemoryUsageEventQueue()) = UsageBatcher(queue, sink, { NOW }) to queue

    @Test fun sendsInBatchesOfAtMostFifty() = runTest {
        val sink = FakeSink()
        val (batcher, queue) = batcher(sink)
        repeat(120) { batcher.record("u", "org", event(it)) }
        val outcome = batcher.flush("u")
        assertEquals(listOf(50, 50, 20), sink.batches.map { it.second.size })
        assertEquals(120, outcome.sent); assertEquals(0, queue.size()); assertFalse(outcome.retryLater)
    }

    @Test fun networkFailureKeepsEventsForALaterRetry() = runTest {
        val sink = FakeSink(UsageSendResult.RetryLater)
        val (batcher, queue) = batcher(sink)
        repeat(3) { batcher.record("u", "org", event(it)) }
        val first = batcher.flush("u")
        assertTrue(first.retryLater); assertEquals(3, queue.size()); assertEquals(1, sink.batches.size)
        sink.result = UsageSendResult.Accepted
        val second = batcher.flush("u")
        assertEquals(3, second.sent); assertEquals(0, queue.size())
        // Los reintentos reenvían los mismos UUID (idempotencia).
        assertEquals(sink.batches[0].second.map { it.id }, sink.batches[1].second.map { it.id })
    }

    @Test fun definitiveRejectionDropsTheBatchInsteadOfLoopingForever() = runTest {
        val sink = FakeSink(UsageSendResult.Rejected)
        val (batcher, queue) = batcher(sink)
        repeat(2) { batcher.record("u", "org", event(it)) }
        val outcome = batcher.flush("u")
        assertEquals(2, outcome.dropped); assertEquals(0, queue.size())
    }

    @Test fun eventsOlderThanTheServerWindowAreDroppedWithoutSending() = runTest {
        val sink = FakeSink()
        val (batcher, _) = batcher(sink)
        batcher.record("u", "org", event(1, NOW - 8L * 24 * 3_600_000))
        batcher.record("u", "org", event(2))
        val outcome = batcher.flush("u")
        assertEquals(1, outcome.dropped); assertEquals(1, outcome.sent)
        assertEquals(1, sink.batches.single().second.size)
    }

    @Test fun onePersonNeverSendsAnotherPersonsEvents() = runTest {
        val sink = FakeSink()
        val (batcher, queue) = batcher(sink)
        batcher.record("a", "org-a", event(1)); batcher.record("b", "org-b", event(2))
        batcher.flush("b")
        assertEquals(listOf("org-b"), sink.batches.map { it.first })
        assertEquals(1, queue.size())
    }

    @Test fun batchesNeverMixOrganizations() = runTest {
        val sink = FakeSink()
        val (batcher, _) = batcher(sink)
        batcher.record("u", "org-1", event(1)); batcher.record("u", "org-2", event(2)); batcher.record("u", "org-1", event(3))
        batcher.flush("u")
        assertEquals(listOf("org-1", "org-2"), sink.batches.map { it.first })
    }

    @Test fun inMemoryQueueIsBounded() = runTest {
        val queue = InMemoryUsageEventQueue(capacity = 3)
        repeat(5) { queue.enqueue(QueuedUsageEvent("u", "o", event(it))) }
        assertEquals(3, queue.size())
        assertEquals(event(2).id, queue.peek("u", 10).first().event.id)
    }
}

class UsageTrackerTest {
    private var now = NOW
    private var n = 0
    private fun tracker(sink: FakeSink): UsageTracker {
        val batcher = UsageBatcher(InMemoryUsageEventQueue(), sink, { now })
        return UsageTracker(batcher, { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { now })
    }

    @Test fun recordsSessionModulesAndEndWithDurations() = runTest {
        val sink = FakeSink(); val tracker = tracker(sink)
        tracker.startSession("u", "org")
        tracker.enterModule(UsageModule.HOME); now += 42_000
        tracker.enterModule(UsageModule.CHECKLISTS); now += 10_000
        tracker.endSession(); tracker.flush("u")
        val events = sink.batches.flatMap { it.second }
        assertEquals(listOf(UsageEventType.SESSION_START, UsageEventType.MODULE_VISIT, UsageEventType.MODULE_VISIT, UsageEventType.SESSION_END), events.map { it.type })
        assertEquals(42, events[1].durationSeconds); assertEquals(UsageModule.HOME, events[1].module)
        assertEquals(10, events[2].durationSeconds); assertEquals(52, events[3].durationSeconds)
        assertEquals(1, events.map { it.sessionId }.distinct().size)
        assertEquals(events.size, events.map { it.id }.distinct().size)
    }

    @Test fun sameModuleTwiceAndEventsWithoutSessionAreIgnored() = runTest {
        val sink = FakeSink(); val tracker = tracker(sink)
        tracker.enterModule(UsageModule.HOME); tracker.alertViewed()
        tracker.startSession("u", "org")
        tracker.enterModule(UsageModule.HOME); tracker.enterModule(UsageModule.HOME); now += 5_000
        tracker.endSession(); tracker.flush("u")
        assertEquals(1, sink.batches.flatMap { it.second }.count { it.type == UsageEventType.MODULE_VISIT })
    }

    @Test fun alertEventsUseTheAlertsModuleAndNoExtraData() = runTest {
        val sink = FakeSink(); val tracker = tracker(sink)
        tracker.startSession("u", "org"); tracker.alertViewed(); tracker.alertAttended(); tracker.flush("u")
        val events = sink.batches.flatMap { it.second }.filter { it.type == UsageEventType.ALERT_VIEWED || it.type == UsageEventType.ALERT_ATTENDED }
        assertEquals(listOf(UsageEventType.ALERT_VIEWED, UsageEventType.ALERT_ATTENDED), events.map { it.type })
        assertTrue(events.all { it.module == UsageModule.ALERTS && it.durationSeconds == null })
    }

    @Test fun changingOrganizationClosesTheFirstSession() = runTest {
        val sink = FakeSink(); val tracker = tracker(sink)
        tracker.startSession("u", "org-1"); now += 1_000
        tracker.startSession("u", "org-2"); tracker.flush("u")
        val types = sink.batches.flatMap { b -> b.second.map { b.first to it.type } }
        assertEquals(listOf("org-1" to UsageEventType.SESSION_START, "org-1" to UsageEventType.SESSION_END, "org-2" to UsageEventType.SESSION_START), types)
    }

}

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {
    private suspend fun TestScope.vm(sink: FakeSink, block: suspend (UsageViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val batcher = UsageBatcher(InMemoryUsageEventQueue(), sink, { NOW })
        var n = 0
        val model = UsageViewModel(UsageTracker(batcher, { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { NOW }), flushIntervalMillis = 1_000)
        try { block(model) } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun superAdminWithoutMembershipRecordsNothing() = runTest {
        val sink = FakeSink()
        vm(sink) { model ->
            model.configure("u", "org", eligible = false); model.moduleChanged(UsageModule.HOME); advanceTimeBy(5_000); runCurrent()
            assertTrue(sink.batches.isEmpty())
        }
    }

    @Test fun startsSessionFlushesOnScreenChangeAndPeriodically() = runTest {
        val sink = FakeSink()
        vm(sink) { model ->
            model.configure("u", "org", true); runCurrent()
            assertEquals(UsageEventType.SESSION_START, sink.batches.first().second.first().type)
            model.moduleChanged(UsageModule.HOME); runCurrent()
            model.moduleChanged(UsageModule.CHECKLISTS); runCurrent()
            val visits = sink.batches.flatMap { it.second }.count { it.type == UsageEventType.MODULE_VISIT }
            assertEquals(1, visits)
            val before = sink.batches.size
            advanceTimeBy(1_100); runCurrent()
            // Sin eventos nuevos el temporizador no envía lotes vacíos.
            assertEquals(before, sink.batches.size)
        }
    }

    @Test fun networkFailureDoesNotBreakAndRetriesLater() = runTest {
        val sink = FakeSink(UsageSendResult.RetryLater)
        vm(sink) { model ->
            model.configure("u", "org", true); runCurrent()
            model.moduleChanged(UsageModule.HOME); runCurrent()
            sink.result = UsageSendResult.Accepted
            advanceTimeBy(1_100); runCurrent()
            assertTrue(sink.batches.last().second.any { it.type == UsageEventType.SESSION_START })
        }
    }

    @Test fun endSessionSendsSessionEnd() = runTest {
        val sink = FakeSink()
        vm(sink) { model ->
            model.configure("u", "org", true); runCurrent()
            model.endSession(); runCurrent()
            assertTrue(sink.batches.flatMap { it.second }.any { it.type == UsageEventType.SESSION_END })
        }
    }

    @Test fun alertEventsOnlyOnTransitions() = runTest {
        val sink = FakeSink()
        vm(sink) { model ->
            model.configure("u", "org", true); runCurrent()
            model.alertObserved("a1", viewed = false, attended = false)
            model.alertObserved("a1", viewed = true, attended = false)
            model.alertObserved("a1", viewed = true, attended = true)
            model.alertObserved("a2", viewed = true, attended = true) // ya atendida al abrirla: sin eventos
            model.flushNow(); runCurrent()
            val types = sink.batches.flatMap { it.second }.map { it.type }
            assertEquals(1, types.count { it == UsageEventType.ALERT_VIEWED })
            assertEquals(1, types.count { it == UsageEventType.ALERT_ATTENDED })
        }
    }
}

class RemoteUsageEventSinkTest {
    private val config = AppConfig("https://api.example.com")
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test fun bodyHasOnlyContractFields() = runTest {
        var captured = ""
        val sink = RemoteUsageEventSink(createHttpClient(MockEngine { request ->
            assertEquals("/api/v1/organizations/org/usage/events", request.url.encodedPath)
            captured = (request.body as io.ktor.http.content.TextContent).text
            respond("""{"data":{"accepted":1,"duplicates":0}}""", HttpStatusCode.OK, json)
        }), config, token = { "t" })
        assertEquals(UsageSendResult.Accepted, sink.send("org", listOf(event(1))))
        val sent = Json.parseToJsonElement(captured).jsonObject["events"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "sessionId", "type", "module", "occurredAt", "durationSeconds"), sent.keys)
    }

    @Test fun statusCodesAreClassified() = runTest {
        suspend fun send(status: HttpStatusCode, token: String? = "t") =
            RemoteUsageEventSink(createHttpClient(MockEngine { respond("{}", status, json) }), config, token = { token }).send("org", listOf(event(1)))
        assertEquals(UsageSendResult.RetryLater, send(HttpStatusCode.ServiceUnavailable))
        assertEquals(UsageSendResult.RetryLater, send(HttpStatusCode.Unauthorized))
        assertEquals(UsageSendResult.RetryLater, send(HttpStatusCode.TooManyRequests))
        assertEquals(UsageSendResult.Rejected, send(HttpStatusCode.Forbidden))
        assertEquals(UsageSendResult.Rejected, send(HttpStatusCode.Conflict))
        assertEquals(UsageSendResult.Rejected, send(HttpStatusCode.UnprocessableEntity))
        assertEquals(UsageSendResult.RetryLater, send(HttpStatusCode.OK, token = null))
    }

    @Test fun transportExceptionsNeverPropagate() = runTest {
        val sink = RemoteUsageEventSink(createHttpClient(MockEngine { throw RuntimeException("sin red") }), config, token = { "t" })
        assertEquals(UsageSendResult.RetryLater, sink.send("org", listOf(event(1))))
    }

    @Test fun oversizedBatchesAreNeverSent() = runTest {
        var called = false
        val sink = RemoteUsageEventSink(createHttpClient(MockEngine { called = true; respond("{}", HttpStatusCode.OK, json) }), config, token = { "t" })
        assertEquals(UsageSendResult.Rejected, sink.send("org", (1..51).map { event(it) }))
        assertFalse(called)
    }
}
