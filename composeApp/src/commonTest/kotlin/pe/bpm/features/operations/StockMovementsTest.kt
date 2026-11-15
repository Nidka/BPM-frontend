package pe.bpm.features.operations

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.operations.data.RemoteStockRepository
import pe.bpm.features.operations.domain.*
import pe.bpm.features.operations.presentation.StockMovementsViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class StockMovementsTest {
    private val org = "22222222-2222-4222-8222-222222222222"
    private val site = "33333333-3333-4333-8333-333333333333"
    private val lotId = "55555555-5555-4555-8555-555555555555"
    private val id = "44444444-4444-4444-8444-444444444444"
    private val now = "2026-11-10T15:00:00Z"

    private fun lot(available: String = "1.125", expired: Boolean = false, status: String = "AVAILABLE") =
        StorageLot(lotId, site, lotId, lotId, "Leche", "L", "L-1", "2026-12-31", now, "Frío", "", available, status, expired, "2")

    private fun input(
        type: StockMovementType = StockMovementType.CONSUMPTION,
        quantity: String = "0.5",
        reason: DiscardReason? = null,
        note: String = "",
        created: String = now,
    ) = StockMovementInput(id, site, lotId, type, quantity, reason, note, id, created)

    @Test
    fun quantityIsExactDecimalAndNeverExceedsTheBalance() {
        assertEquals(1125L, StockRules.milli("1.125"))
        assertEquals(500L, StockRules.milli("0,5"))
        assertNull(StockRules.milli("1.1255"))
        assertNull(StockRules.milli("-1"))
        assertNull(StockRules.milli("1e3"))
        assertTrue(StockRules.errors(input(quantity = "1.125"), lot(), now).isEmpty())
        assertEquals(setOf("quantity"), StockRules.errors(input(quantity = "1.126"), lot(), now).keys)
        for (bad in listOf("0", "0.000", "-1", "1.2345", "abc", ""))
            assertEquals(setOf("quantity"), StockRules.errors(input(quantity = bad), lot(), now).keys, bad)
    }

    @Test
    fun expiredLotsAreDiscardedNotConsumed() {
        val expired = lot(expired = true)
        assertEquals(setOf("type"), StockRules.errors(input(), expired, now).keys)
        assertTrue(StockRules.errors(input(StockMovementType.DISCARD, reason = DiscardReason.EXPIRED), expired, now).isEmpty())
    }

    @Test
    fun discardNeedsReasonAndOtherNeedsANote() {
        assertEquals(setOf("reason"), StockRules.errors(input(StockMovementType.DISCARD), lot(), now).keys)
        assertEquals(setOf("note"), StockRules.errors(input(StockMovementType.DISCARD, reason = DiscardReason.OTHER), lot(), now).keys)
        assertTrue(StockRules.errors(input(StockMovementType.DISCARD, reason = DiscardReason.OTHER, note = "Se cayó"), lot(), now).isEmpty())
        assertEquals(setOf("reason"), StockRules.errors(input(reason = DiscardReason.EXPIRED), lot(), now).keys)
        assertEquals(setOf("note"), StockRules.errors(input(StockMovementType.DISCARD, reason = DiscardReason.OTHER, note = "x".repeat(501)), lot(), now).keys)
    }

    @Test
    fun exhaustedLotsAndFutureTimestampsAreRejected() {
        assertEquals(setOf("lotId"), StockRules.errors(input(), lot("0.000", status = "CONSUMED"), now).keys)
        assertEquals(setOf("clientCreatedAt"), StockRules.errors(input(created = "2026-11-10T15:10:00Z"), lot(), now).keys)
        assertTrue(StockRules.errors(input(created = "2026-11-10T15:04:00Z"), lot(), now).isEmpty())
    }

    @Test
    fun onlyChefsRecordAndInvalidMovementsNeverReachTransport() = runTest {
        val repo = FakeStock()
        val manage = ManageStock(repo)
        assertEquals(OperationsResult.Failure(OperationsFailure.DENIED), manage.record(org, "WORKER", input(), lot(), now, id))
        assertEquals(OperationsResult.Failure(OperationsFailure.DENIED), manage.record(org, "SUPER_ADMIN", input(), lot(), now, id))
        assertEquals(OperationsResult.Failure(OperationsFailure.DENIED), manage.history(org, site, "WORKER", StockFilter(), PageRequest()))
        val tooMuch = manage.record(org, "CHEF", input(quantity = "9"), lot(), now, id) as OperationsResult.Failure
        assertEquals(OperationsFailure.INVALID, tooMuch.reason)
        assertEquals(0, repo.calls)
    }

    @Test
    fun retryAfterAnUncertainResponseReusesTheSameMovementId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo = FakeStock()
            var counter = 0
            val vm = StockMovementsViewModel(ManageStock(repo), { "00000000-0000-4000-8000-" + (counter++).toString().padStart(12, '0') }, { now })
            models.put("vm", vm)
            vm.open("chef", org, "CHEF", site)
            advanceUntilIdle()
            vm.start(lot())
            vm.change(quantity = "0.5")
            repo.results += OperationsResult.Failure(OperationsFailure.CONNECTION)
            repo.results += OperationsResult.Success(repo.movement)
            vm.submit()
            advanceUntilIdle()
            assertTrue(vm.state.value.form!!.uncertain)
            vm.change(quantity = "0.9")
            assertEquals("0.5", vm.state.value.form!!.quantity)
            vm.submit()
            advanceUntilIdle()
            assertEquals(2, repo.recorded.size)
            assertEquals(repo.recorded[0].id, repo.recorded[1].id)
            assertEquals(repo.keys[0], repo.keys[1])
            assertEquals(repo.recorded[0], repo.recorded[1])
            assertNull(vm.state.value.form)
            assertNotNull(vm.state.value.saved)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun insufficientStockIsDefinitiveAndExpiredLotsSuggestAnExpiredDiscard() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo = FakeStock()
            var counter = 0
            val vm = StockMovementsViewModel(ManageStock(repo), { "00000000-0000-4000-8000-" + (counter++).toString().padStart(12, '0') }, { now })
            models.put("vm", vm)
            vm.open("chef", org, "CHEF", site)
            advanceUntilIdle()
            vm.start(lot())
            vm.change(quantity = "1")
            val firstId = vm.state.value.form!!.id
            repo.results += OperationsResult.Failure(OperationsFailure.INSUFFICIENT_STOCK)
            vm.submit()
            advanceUntilIdle()
            val afterRejection = vm.state.value
            assertEquals(OperationsFailure.INSUFFICIENT_STOCK, afterRejection.failure)
            assertFalse(afterRejection.form!!.uncertain)
            assertNotEquals(firstId, afterRejection.form.id)
            // El servidor dice que el lote venció: se propone el descarte con la misma cantidad.
            repo.results += OperationsResult.Failure(OperationsFailure.LOT_EXPIRED)
            vm.submit()
            advanceUntilIdle()
            assertEquals(OperationsFailure.LOT_EXPIRED, vm.state.value.failure)
            vm.suggestExpiredDiscard()
            val form = vm.state.value.form!!
            assertEquals(StockMovementType.DISCARD, form.type)
            assertEquals(DiscardReason.EXPIRED, form.reason)
            assertEquals("1", form.quantity)
            assertNull(vm.state.value.failure)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun remoteRepositoryOmitsReasonForConsumptionAndMapsBusinessCodes() = runTest {
        val bodies = mutableListOf<JsonObject>()
        var status = HttpStatusCode.OK
        var payload = ""
        val client =
            createHttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Post) {
                        assertEquals(id, request.headers["Idempotency-Key"])
                        bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    } else {
                        assertEquals(lotId, request.url.parameters["lotId"])
                        assertEquals("DISCARD", request.url.parameters["type"])
                    }
                    respond(payload, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
        val repo = RemoteStockRepository(client, AppConfig("https://example.test")) { "token" }
        fun movement(type: String, quantity: String, reason: String = "", note: String = "") =
            """{"id":"$id","locationId":"$site","lotId":"$lotId","type":"$type","quantity":"$quantity","reason":"$reason","note":"$note","clientOperationId":"$id","clientCreatedAt":"$now","productName":"Leche","unit":"L","batchCode":"L-1","balanceAfter":"0.625","performedBy":"u1","performedByName":"Ana","createdAt":"$now"}"""
        payload = """{"data":${movement("CONSUMPTION", "0.500")}}"""
        val consumed = repo.record(org, input(), id) as OperationsResult.Success
        assertEquals("0.625", consumed.value.balanceAfter)
        assertEquals(setOf("id", "locationId", "lotId", "type", "quantity", "clientOperationId", "clientCreatedAt"), bodies.last().keys)
        payload = """{"data":${movement("DISCARD", "0.5", "OTHER", "Se cayó")}}"""
        val discard = input(StockMovementType.DISCARD, reason = DiscardReason.OTHER, note = "Se cayó")
        assertIs<OperationsResult.Success<StockMovement>>(repo.record(org, discard, id))
        assertEquals("OTHER", bodies.last().getValue("reason").jsonPrimitive.content)
        assertEquals("Se cayó", bodies.last().getValue("note").jsonPrimitive.content)
        // Un movimiento con otra cantidad no confirma lo que se envió.
        payload = """{"data":${movement("CONSUMPTION", "0.6")}}"""
        assertEquals(OperationsFailure.INVALID_RESPONSE, (repo.record(org, input(), id) as OperationsResult.Failure).reason)
        status = HttpStatusCode.Conflict
        for ((code, reason) in listOf("INSUFFICIENT_STOCK" to OperationsFailure.INSUFFICIENT_STOCK, "LOT_EXPIRED" to OperationsFailure.LOT_EXPIRED, "RESOURCE_CONFLICT" to OperationsFailure.CONFLICT)) {
            payload = """{"errors":[{"code":"$code","message":"interno"}]}"""
            assertEquals(reason, (repo.record(org, input(), id) as OperationsResult.Failure).reason, code)
        }
        status = HttpStatusCode.OK
        payload = """{"data":[${movement("DISCARD", "0.5", "EXPIRED")}],"pagination":{"nextPageToken":""}}"""
        val page = repo.movements(org, site, StockFilter(lotId, StockMovementType.DISCARD), PageRequest()) as OperationsResult.Success
        assertEquals(1, page.value.items.size)
        client.close()
    }

    private inner class FakeStock : StockRepository {
        val results = ArrayDeque<OperationsResult<StockMovement>>()
        val recorded = mutableListOf<StockMovementInput>()
        val keys = mutableListOf<String>()
        var calls = 0
        val movement =
            StockMovement(input(), "Leche", "L", "L-1", "0.625", "u1", "Ana", now)

        override suspend fun movements(org: String, site: String, filter: StockFilter, page: PageRequest) =
            OperationsResult.Success(Page(emptyList<StockMovement>(), ""))

        override suspend fun record(org: String, input: StockMovementInput, key: String): OperationsResult<StockMovement> {
            calls++
            recorded += input
            keys += key
            return results.removeFirstOrNull() ?: OperationsResult.Success(movement)
        }
    }
}
