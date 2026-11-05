package pe.bpm.features.operations

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.storage.DraftStorage
import pe.bpm.features.operations.data.*
import pe.bpm.features.operations.domain.*

class OperationsDataTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val org = "22222222-2222-4222-8222-222222222222"

    private class Memory : DraftStorage {
        val data = mutableMapOf<String, String>()

        override suspend fun read(key: String) = data[key]

        override suspend fun write(key: String, value: String) {
            data[key] = value
        }

        override suspend fun remove(key: String) {
            data.remove(key)
        }
    }

    @Test
    fun fiftyUnicodeObservationsFitTheSharedRequestAndDurableCheckpointLimits() = runTest {
        val memory = Memory()
        val drafts = LocalOperationsDraftRepository(memory, "origin")
        val reason = "🧼".repeat(2000)
        val time = "2026-11-02T10:00:00Z"
        val items =
            (1..50).map { index ->
                ReceiptItemInput(
                    "00000000-0000-4000-8000-${index.toString().padStart(12,'0')}",
                    id,
                    "Lote",
                    quantity = "1",
                    decision = ReceiptDecision.OBSERVED,
                    rejectionReason = reason,
                    packagingCondition = PackagingCondition.DAMAGED,
                )
            }
        var receivedBody = ""
        val client =
            HttpClient(
                MockEngine { request ->
                    receivedBody = request.body.toByteArray().decodeToString()
                    val confirmed = Json.parseToJsonElement(receivedBody).jsonObject.toMutableMap()
                    confirmed["createdAt"] = JsonPrimitive(time)
                    confirmed["supplierName"] = JsonPrimitive("Proveedor")
                    confirmed["receivedBy"] = JsonPrimitive("user")
                    confirmed["items"] =
                        JsonArray(
                            confirmed.getValue("items").jsonArray.map {
                                JsonObject(
                                    it.jsonObject +
                                        mapOf(
                                            "productName" to JsonPrimitive("Producto"),
                                            "unit" to JsonPrimitive("kg"),
                                        )
                                )
                            }
                        )
                    respond(
                        JsonObject(mapOf("data" to JsonObject(confirmed))).toString(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        try {
            val repo =
                RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
            val form =
                OperationsForm(
                    id,
                    time,
                    mapOf("supplierId" to id, "supplierName" to "Proveedor"),
                    items,
                )
            val pending = repo.prepare(form.command(OperationsResource.RECEIPTS, org), id)
            assertTrue(pending.body.encodeToByteArray().size > 128 * 1024)
            assertTrue(pending.body.encodeToByteArray().size <= 512 * 1024)
            drafts.write(
                DraftOwner("user", org),
                OperationsResource.RECEIPTS,
                org,
                OperationsDraft(OperationsResource.RECEIPTS, org, form, pending),
            )
            assertEquals(
                pending,
                drafts.read(DraftOwner("user", org), OperationsResource.RECEIPTS, org)?.pending,
            )
            assertIs<OperationsResult.Success<OperationsEntry>>(repo.submit(org, pending))
            assertEquals(pending.body, receivedBody)
        } finally {
            client.close()
        }
    }

    @Test
    fun existingOrganizationIdentifiersRemainValid() = runTest {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        "{\"data\":[],\"pagination\":{\"nextPageToken\":\"\"}}",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                install(ContentNegotiation) { json() }
            }
        try {
            val repo =
                RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
            assertIs<OperationsResult.Success<*>>(repo.suppliers("legacy-org", PageRequest()))
        } finally {
            client.close()
        }
    }

    @Test
    fun legacyLotsKeepTheirIndependentIdentity() {
        val lot =
            LotDto(
                    id,
                    org,
                    org,
                    id,
                    "Producto",
                    "kg",
                    "",
                    null,
                    "2026-11-02T10:00:00Z",
                    "Almacén",
                    "",
                    "1",
                    "AVAILABLE",
                    false,
                )
                .domain()
        assertEquals(id, lot.id)
        assertEquals(org, lot.receiptItemId)
    }

    @Test
    fun confirmedTimestampsUseDatabaseMicrosecondsWithoutChangingTheRequest() = runTest {
        var shifted = false
        val bodies = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                    val raw = request.body.toByteArray().decodeToString()
                    bodies += raw
                    val confirmed = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
                    listOf("receivedAt", "executedAt", "clientCreatedAt").forEach { key ->
                        if (key in confirmed)
                            confirmed[key] =
                                JsonPrimitive(
                                    if (shifted) "2026-11-02T10:00:00.123458Z"
                                    else "2026-11-02T10:00:00.123456Z"
                                )
                    }
                    confirmed["createdAt"] = JsonPrimitive("2026-11-02T10:00:01Z")
                    if (request.url.encodedPath.endsWith("supply-receipts")) {
                        confirmed["supplierName"] = JsonPrimitive("Proveedor")
                        confirmed["receivedBy"] = JsonPrimitive("user")
                        confirmed["receivedByName"] = JsonPrimitive("Responsable")
                        confirmed["items"] =
                            JsonArray(
                                confirmed.getValue("items").jsonArray.map {
                                    JsonObject(
                                        it.jsonObject +
                                            mapOf(
                                                "productName" to JsonPrimitive("Producto"),
                                                "unit" to JsonPrimitive("kg"),
                                            )
                                    )
                                }
                            )
                    } else {
                        confirmed["area"] = JsonPrimitive("Área")
                        confirmed["task"] = JsonPrimitive("Limpiar")
                        confirmed["responsibleUserId"] = JsonPrimitive("user")
                        confirmed["responsibleName"] = JsonPrimitive("Responsable")
                    }
                    respond(
                        JsonObject(mapOf("data" to JsonObject(confirmed))).toString(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        try {
            val remote =
                RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
            val time = "2026-11-02T10:00:00.123456789Z"
            val commands =
                listOf(
                    OperationsCommand.Execute(
                        CleaningExecutionInput(
                            id,
                            org,
                            id,
                            time,
                            CleaningResult.COMPLETED,
                            clientOperationId = id,
                            clientCreatedAt = time,
                        )
                    ),
                    OperationsCommand.Receive(
                        ReceiptInput(
                            id,
                            org,
                            id,
                            time,
                            clientOperationId = id,
                            clientCreatedAt = time,
                            items =
                                listOf(
                                    ReceiptItemInput(
                                        id,
                                        id,
                                        "Lote",
                                        quantity = "1",
                                        packagingCondition = PackagingCondition.INTACT,
                                        storageLocation = "Almacén",
                                    )
                                ),
                        )
                    ),
                )
            commands.forEach { command ->
                val pending = remote.prepare(command, id)
                assertIs<OperationsResult.Success<OperationsEntry>>(remote.submit(org, pending))
                assertTrue(bodies.last().contains("123456789Z"))
                shifted = true
                assertEquals(
                    OperationsFailure.INVALID_RESPONSE,
                    assertIs<OperationsResult.Failure>(remote.submit(org, pending)).reason,
                )
                assertEquals(pending.body, bodies.last())
                shifted = false
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun legacyPackagingRemainsUnknown() {
        val item =
            ReceiptItemDto(
                    id,
                    id,
                    "",
                    quantity = "1",
                    decision = "ACCEPTED",
                    packagingCondition = null,
                    productName = "Producto",
                    unit = "kg",
                )
                .domain()
        assertNull(item.input.packagingCondition)
        assertTrue(item.legacyPackaging)
    }

    @Test
    fun pendingPayloadMustMatchTheProtectedForm() = runTest {
        val memory = Memory()
        val drafts = LocalOperationsDraftRepository(memory, "https://example.test")
        val client = HttpClient(MockEngine { respond("{}") })
        try {
            val remote =
                RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
            val payload =
                remote.prepare(OperationsCommand.AddSupplier(Supplier(id, "Otra entrada")), id)
            val form = OperationsForm(id, "2026-11-02T10:00:00Z", mapOf("name" to "Proveedor"))
            assertFailsWith<IllegalArgumentException> {
                drafts.write(
                    DraftOwner("user", org),
                    OperationsResource.SUPPLIERS,
                    null,
                    OperationsDraft(OperationsResource.SUPPLIERS, null, form, payload),
                )
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun serverValidationUsesOnlyKnownFieldNames() = runTest {
        val client =
            HttpClient(
                MockEngine {
                    respond(
                        """{"errors":[{"code":"VALIDATION_ERROR","fields":{"name":"internal secret","actor":"internal","items.0.quantity":"db message"}}]}""",
                        HttpStatusCode.UnprocessableEntity,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        try {
            val remote =
                RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
            val result =
                assertIs<OperationsResult.Failure>(
                    remote.createSupplier(org, Supplier(id, "Proveedor"), id)
                )
            assertEquals("Revisa este campo y vuelve a intentar.", result.fields["name"])
            assertEquals(
                "Revisa este campo y vuelve a intentar.",
                result.fields["items.0.quantity"],
            )
            assertFalse("actor" in result.fields)
            assertFalse(result.fields.values.any { it.contains("secret") })
        } finally {
            client.close()
        }
    }

    @Test
    fun draftsStayPartitionedByAccountOrganizationSiteAndOrigin() = runTest {
        val memory = Memory()
        val repo = LocalOperationsDraftRepository(memory, "origin-a")
        val owner = DraftOwner("a", org)
        val form =
            OperationsForm(id, "2026-11-02T10:00:00Z", mapOf("area" to "Área", "task" to "Limpiar"))
        repo.write(
            owner,
            OperationsResource.PLANS,
            id,
            OperationsDraft(OperationsResource.PLANS, id, form),
        )
        assertNotNull(repo.read(owner, OperationsResource.PLANS, id))
        assertNull(repo.read(DraftOwner("b", org), OperationsResource.PLANS, id))
        assertNull(repo.read(owner, OperationsResource.PLANS, org))
        assertNull(
            LocalOperationsDraftRepository(memory, "origin-b")
                .read(owner, OperationsResource.PLANS, id)
        )
    }
}
