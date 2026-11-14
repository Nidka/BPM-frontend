package pe.bpm.core.offline

import kotlinx.coroutines.CancellationException

/** Reloj y servidor simulados: el servidor deduplica por clave de idempotencia como el backend real. */
class FakeServer {
    /** Resultados por clave: lo que el servidor ya procesó. */
    val processed = linkedMapOf<String, Int>()
    val calls = mutableListOf<String>()
    var script = ArrayDeque<SendOutcome>()
    var crashAfterProcessing = false

    fun handler() = object : OutboxHandler {
        override suspend fun send(operation: OutboxOperation): SendOutcome {
            calls += operation.id
            val scripted = script.removeFirstOrNull()
            if (scripted != null) return scripted
            // Idempotente: una repetición responde igual sin repetir el efecto.
            val first = operation.idempotencyKey !in processed
            processed[operation.idempotencyKey] = (processed[operation.idempotencyKey] ?: 0) + if (first) 1 else 0
            if (crashAfterProcessing) { crashAfterProcessing = false; throw CancellationException("proceso terminado") }
            return SendOutcome.Sent(if (first) 201 else 200)
        }
    }
}

class OfflineHarness(
    val database: OfflineDatabase = InMemoryOfflineDatabase(),
    val connectivity: ManualConnectivityMonitor = ManualConnectivityMonitor(true),
    var now: Long = 1_000_000,
    val user: String = "user-1",
) {
    private var counter = 0
    val server = FakeServer()
    val runtime = OfflineRuntime(OfflinePlatform(database = database, connectivity = connectivity), { now }, { "op-${++counter}-uuid" })
        .also { it.register("HTTP", server.handler()) }

    suspend fun login(userId: String = user) = runtime.activate(OfflineScope("https://api.test", userId))

    suspend fun enqueue(key: String, entity: String = "", org: String = "org1", payload: String = "{}") =
        runtime.outbox.enqueue(OutboxDraft(runtime.newId(), "HTTP", org, entity, key, payload, now))
}
