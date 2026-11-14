package pe.bpm.features.operations

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.operations.data.RemoteOperationsRepository
import pe.bpm.features.operations.domain.*

/** Planes por turno, turno en ejecuciones de limpieza y saldo de lotes. */
class CleaningShiftTest {
    private val org = "22222222-2222-4222-8222-222222222222"
    private val site = "33333333-3333-4333-8333-333333333333"
    private val id = "44444444-4444-4444-8444-444444444444"
    private val plan = "66666666-6666-4666-8666-666666666666"
    private val shift = "77777777-7777-4777-8777-777777777777"
    private val now = "2026-11-10T15:00:00Z"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun execution(shiftId: String? = null) =
        CleaningExecutionInput(id, site, plan, now, CleaningResult.COMPLETED, "", id, now, shiftId)

    private fun executionJson(shiftId: String? = null, name: String = "Mañana") =
        """{"id":"$id","locationId":"$site","cleaningPlanId":"$plan","executedAt":"$now","result":"COMPLETED","observation":"","clientOperationId":"$id","clientCreatedAt":"$now","area":"Cocina","task":"Limpiar mesa","responsibleUserId":"u1","responsibleName":"Ana","createdAt":"$now"""" +
            (if (shiftId == null) "" else ""","shiftId":"$shiftId","shiftName":"$name"""") + "}"

    @Test
    fun perShiftPlansAreAcceptedWithOrWithoutReferenceTime() {
        val base = CleaningPlan(id, site, "Cocina", "Limpiar mesa", frequency = CleaningFrequency.PER_SHIFT)
        assertTrue(OperationsRules.plan(base).isEmpty())
        // El dominio del backend admite una hora de referencia también en planes por turno.
        assertTrue(OperationsRules.plan(base.copy(dueTime = "08:30")).isEmpty())
        assertEquals(setOf("dueTime"), OperationsRules.plan(base.copy(dueTime = "8:30")).keys)
    }

    @Test
    fun formCommandCarriesTheChosenShift() {
        val form = OperationsForm(id, now, mapOf("cleaningPlanId" to plan, "result" to "COMPLETED", "shiftId" to shift))
        val command = form.command(OperationsResource.EXECUTIONS, site) as OperationsCommand.Execute
        assertEquals(shift, command.input.shiftId)
        val withoutShift = OperationsForm(id, now, mapOf("cleaningPlanId" to plan)).command(OperationsResource.EXECUTIONS, site) as OperationsCommand.Execute
        assertNull(withoutShift.input.shiftId)
        val perShift = OperationsForm(id, now, mapOf("frequency" to "PER_SHIFT", "area" to "Cocina", "task" to "Limpiar")).command(OperationsResource.PLANS, site) as OperationsCommand.AddPlan
        assertEquals(CleaningFrequency.PER_SHIFT, perShift.input.frequency)
    }

    @Test
    fun executionBodyOmitsShiftWhenAbsentAndConfirmsItWhenPresent() = runTest {
        val bodies = mutableListOf<JsonObject>()
        var status = HttpStatusCode.OK
        var payload = ""
        val client =
            createHttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Post) bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    respond(payload, status, jsonHeaders)
                }
            )
        val repo = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
        payload = """{"data":${executionJson()}}"""
        assertIs<OperationsResult.Success<CleaningExecution>>(repo.createExecution(org, execution(), id))
        assertFalse("shiftId" in bodies.last().keys)
        payload = """{"data":${executionJson(shift)}}"""
        val done = repo.createExecution(org, execution(shift), id) as OperationsResult.Success
        assertEquals(shift, bodies.last().getValue("shiftId").jsonPrimitive.content)
        assertEquals("Mañana", done.value.shiftName)
        assertEquals(shift, done.value.input.shiftId)
        // Si el servidor responde con otro turno, no se anuncia éxito.
        payload = """{"data":${executionJson("88888888-8888-4888-8888-888888888888")}}"""
        assertEquals(OperationsFailure.INVALID_RESPONSE, (repo.createExecution(org, execution(shift), id) as OperationsResult.Failure).reason)
        client.close()
    }

    @Test
    fun closedShiftIsReportedAsItsOwnDefinitiveFailure() = runTest {
        var status = HttpStatusCode.UnprocessableEntity
        var payload = """{"errors":[{"code":"SHIFT_OUT_OF_HOURS","message":"interno","fields":{"shiftId":"x"}}]}"""
        val client = createHttpClient(MockEngine { respond(payload, status, jsonHeaders) })
        val repo = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
        val rejected = repo.createExecution(org, execution(shift), id) as OperationsResult.Failure
        assertEquals(OperationsFailure.SHIFT_OUT_OF_HOURS, rejected.reason)
        assertEquals(setOf("shiftId"), rejected.fields.keys)
        assertTrue(rejected.reason.definitive)
        payload = """{"errors":[{"code":"VALIDATION_ERROR","fields":{"observation":"x"}}]}"""
        assertEquals(OperationsFailure.INVALID, (repo.createExecution(org, execution(shift), id) as OperationsResult.Failure).reason)
        assertFalse(OperationsFailure.CONNECTION.definitive)
        client.close()
    }

    @Test
    fun lotsExposeReceivedQuantityAndCurrentBalance() = runTest {
        val lot = """{"id":"$id","locationId":"$site","receiptItemId":"$id","productId":"$plan","productName":"Leche","unit":"L","batchCode":"L-1","expirationDate":"2026-12-31","receivedAt":"$now","storageLocation":"Frío","label":"","quantityAvailable":"0.625","receivedQuantity":"2","status":"AVAILABLE","expired":false}"""
        val legacy = lot.replace(",\"receivedQuantity\":\"2\"", "").replace(id, shift)
        val client = createHttpClient(MockEngine { respond("""{"data":[$lot,$legacy],"pagination":{"nextPageToken":""}}""", HttpStatusCode.OK, jsonHeaders) })
        val repo = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
        val page = repo.lots(org, site, PageRequest()) as OperationsResult.Success
        assertEquals("2", page.value.items[0].receivedQuantity)
        assertEquals("0.625", page.value.items[0].quantityAvailable)
        assertEquals("", page.value.items[1].receivedQuantity)
        assertTrue(StockRules.available(page.value.items[0]))
        client.close()
    }

    @Test
    fun perShiftFrequencyRoundTripsThroughThePlanEndpoint() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val client =
            createHttpClient(
                MockEngine { request ->
                    bodies += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    respond(
                        """{"data":{"id":"$id","locationId":"$site","area":"Cocina","task":"Limpiar mesa","instructions":"","productName":"","frequency":"PER_SHIFT","dueTime":"","active":true}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            )
        val repo = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
        val created = repo.createPlan(org, CleaningPlan(id, site, "Cocina", "Limpiar mesa", frequency = CleaningFrequency.PER_SHIFT), id) as OperationsResult.Success
        assertEquals(CleaningFrequency.PER_SHIFT, created.value.frequency)
        assertEquals("PER_SHIFT", bodies.single().getValue("frequency").jsonPrimitive.content)
        client.close()
    }
}
