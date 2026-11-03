package pe.bpm.features.operations.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.*
import pe.bpm.core.network.*
import pe.bpm.features.operations.domain.*

/** Transporte autenticado; el servidor deriva responsable, empresa y permisos del token. */
class RemoteOperationsRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : OperationsRepository, OperationsTransport {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun path(org: String, resource: String): String {
        require(org.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return "${config.apiBaseUrl}/api/v1/organizations/$org/$resource"
    }

    private suspend fun <T> request(
        block: suspend (String) -> OperationsResult<T>
    ): OperationsResult<T> =
        try {
            val access =
                token()?.takeIf { it.isNotBlank() }
                    ?: return OperationsResult.Failure(OperationsFailure.SESSION)
            block(access)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionAccessException) {
            OperationsResult.Failure(
                if (e.expired) OperationsFailure.SESSION else OperationsFailure.CONNECTION
            )
        } catch (_: SerializationException) {
            OperationsResult.Failure(OperationsFailure.INVALID_RESPONSE)
        } catch (_: JsonConvertException) {
            OperationsResult.Failure(OperationsFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            OperationsResult.Failure(OperationsFailure.INVALID_RESPONSE)
        } catch (_: Throwable) {
            OperationsResult.Failure(OperationsFailure.CONNECTION)
        }

    private suspend fun failure(response: HttpResponse, access: String): OperationsResult.Failure {
        if (response.status.value == 401) onSessionRejected(access)
        val reason =
            when (response.status.value) {
                400 -> OperationsFailure.INVALID
                401 -> OperationsFailure.SESSION
                403 -> OperationsFailure.DENIED
                404 -> OperationsFailure.NOT_FOUND
                409 -> OperationsFailure.CONFLICT
                422 -> OperationsFailure.INVALID
                429 -> OperationsFailure.RATE_LIMITED
                else -> OperationsFailure.UNAVAILABLE
            }
        val fields =
            if (reason == OperationsFailure.INVALID) {
                val body =
                    try {
                        response.body<ErrorEnvelope>()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                val allowed =
                    setOf(
                        "name",
                        "taxId",
                        "contactName",
                        "contactPhone",
                        "contactEmail",
                        "address",
                        "category",
                        "unit",
                        "requiresTemperature",
                        "minimumTemperature",
                        "maximumTemperature",
                        "rowVersion",
                        "locationId",
                        "supplierId",
                        "receivedAt",
                        "documentReference",
                        "notes",
                        "items",
                        "area",
                        "task",
                        "instructions",
                        "productName",
                        "frequency",
                        "dueTime",
                        "cleaningPlanId",
                        "executedAt",
                        "result",
                        "observation",
                        "clientCreatedAt",
                    )
                val itemField =
                    Regex(
                        """items\.(?:[0-9]|[1-4][0-9])\.(?:productId|batchCode|expirationDate|quantity|temperatureCelsius|decision|rejectionReason|packagingCondition|storageLocation|label)"""
                    )
                body
                    ?.errors
                    .orEmpty()
                    .flatMap { it.fields.keys }
                    .filter { it in allowed || itemField.matches(it) }
                    .associateWith { "Revisa este campo y vuelve a intentar." }
            } else emptyMap()
        return OperationsResult.Failure(reason, fields)
    }

    override suspend fun authorize(
        org: String,
        site: String?,
        role: String,
        resource: OperationsResource,
    ) = request { access ->
        val response = client.get(path(org, "workspace")) { bearerAuth(access) }
        onSessionResponse(response)
        if (!response.status.isSuccess()) failure(response, access)
        else {
            val workspace =
                response.body<JsonObject>()["data"]?.jsonObject ?: throw IllegalArgumentException()
            require(workspace["organization"]?.jsonObject?.get("id")?.jsonPrimitive?.content == org)
            val confirmedRole = workspace["role"]?.jsonPrimitive?.content
            if (confirmedRole != role || !OperationsAccess.canRead(role, resource))
                return@request OperationsResult.Failure(OperationsFailure.DENIED)
            if (site != null) {
                validId(site)
                val readResource =
                    if (
                        resource == OperationsResource.EXECUTIONS ||
                            resource == OperationsResource.PLANS
                    )
                        OperationsResource.PLANS
                    else OperationsResource.RECEIPTS
                val check =
                    client.get(path(org, readResource.path)) {
                        bearerAuth(access)
                        parameter("locationId", site)
                        parameter("pageSize", 1)
                    }
                onSessionResponse(check)
                if (!check.status.isSuccess()) return@request failure(check, access)
                require(check.body<JsonObject>()["data"] is JsonArray)
            }
            OperationsResult.Success(Unit)
        }
    }

    private suspend inline fun <reified D, T> list(
        org: String,
        resource: OperationsResource,
        site: String?,
        page: PageRequest,
        crossinline convert: (D) -> T,
    ): OperationsResult<Page<T>> = request { access ->
        val response =
            client.get(path(org, resource.path)) {
                bearerAuth(access)
                pageParameters(page)
                site?.let {
                    validId(it)
                    parameter("locationId", it)
                }
            }
        onSessionResponse(response)
        if (!response.status.isSuccess()) failure(response, access)
        else
            response.body<Envelope<List<D>>>().let {
                OperationsResult.Success(checkedPage(it.data.map(convert), it.pagination, page))
            }
    }

    override suspend fun suppliers(org: String, page: PageRequest) =
        list<SupplierDto, Supplier>(org, OperationsResource.SUPPLIERS, null, page) { it.domain() }

    override suspend fun products(org: String, page: PageRequest) =
        list<ProductDto, Product>(org, OperationsResource.PRODUCTS, null, page) { it.domain() }

    override suspend fun receipts(org: String, site: String, page: PageRequest) =
        list<ReceiptDto, SupplyReceipt>(org, OperationsResource.RECEIPTS, site, page) {
            it.domain().also { v -> require(v.input.locationId == site) }
        }

    override suspend fun lots(org: String, site: String, page: PageRequest) =
        list<LotDto, StorageLot>(org, OperationsResource.LOTS, site, page) {
            it.domain().also { v -> require(v.locationId == site) }
        }

    override suspend fun plans(org: String, site: String, page: PageRequest) =
        list<PlanDto, CleaningPlan>(org, OperationsResource.PLANS, site, page) {
            it.domain().also { v -> require(v.locationId == site) }
        }

    override suspend fun executions(org: String, site: String, page: PageRequest) =
        list<ExecutionDto, CleaningExecution>(org, OperationsResource.EXECUTIONS, site, page) {
            it.domain().also { v -> require(v.input.locationId == site) }
        }

    override suspend fun receipt(org: String, id: String): OperationsResult<SupplyReceipt> =
        request { access ->
            validId(id)
            val r = client.get(path(org, "supply-receipts/$id")) { bearerAuth(access) }
            onSessionResponse(r)
            if (!r.status.isSuccess()) failure(r, access)
            else
                OperationsResult.Success(
                    r.body<Envelope<ReceiptDto>>().data.domain().also { require(it.input.id == id) }
                )
        }

    override suspend fun execution(org: String, id: String): OperationsResult<CleaningExecution> =
        request { access ->
            validId(id)
            val r = client.get(path(org, "cleaning-executions/$id")) { bearerAuth(access) }
            onSessionResponse(r)
            if (!r.status.isSuccess()) failure(r, access)
            else
                OperationsResult.Success(
                    r.body<Envelope<ExecutionDto>>().data.domain().also {
                        require(it.input.id == id)
                    }
                )
        }

    override fun prepare(command: OperationsCommand, key: String) = encodeOperation(command, key)

    override suspend fun submit(
        org: String,
        pending: PendingOperation,
    ): OperationsResult<OperationsEntry> = request { access ->
        validId(pending.id)
        require(pending.key.length in 8..100 && pending.key.all { it.code in 33..126 })
        require(operationBodyFits(pending.body))
        val target =
            pending.resource.path + if (pending.policy) "/${pending.id}/temperature-policy" else ""
        require(!pending.policy || pending.resource == OperationsResource.PRODUCTS)
        val response =
            client.request(path(org, target)) {
                method = if (pending.policy) HttpMethod.Put else HttpMethod.Post
                bearerAuth(access)
                header("Idempotency-Key", pending.key)
                contentType(ContentType.Application.Json)
                setBody(pending.body)
            }
        onSessionResponse(response)
        if (!response.status.isSuccess()) failure(response, access)
        else {
            val entry: OperationsEntry =
                when (pending.resource) {
                    OperationsResource.SUPPLIERS ->
                        OperationsEntry.SupplierEntry(
                            response.body<Envelope<SupplierDto>>().data.domain()
                        )
                    OperationsResource.PRODUCTS ->
                        OperationsEntry.ProductEntry(
                            response.body<Envelope<ProductDto>>().data.domain()
                        )
                    OperationsResource.RECEIPTS ->
                        OperationsEntry.ReceiptEntry(
                            response.body<Envelope<ReceiptDto>>().data.domain().also {
                                require(it.items.none { item -> item.legacyPackaging })
                            }
                        )
                    OperationsResource.PLANS ->
                        OperationsEntry.PlanEntry(response.body<Envelope<PlanDto>>().data.domain())
                    OperationsResource.EXECUTIONS ->
                        OperationsEntry.ExecutionEntry(
                            response.body<Envelope<ExecutionDto>>().data.domain()
                        )
                    OperationsResource.LOTS -> error("Solo lectura")
                }
            require(entry.id == pending.id)
            val confirmed =
                when (entry) {
                    is OperationsEntry.SupplierEntry -> json.encodeToJsonElement(entry.value.dto())
                    is OperationsEntry.ProductEntry ->
                        entry.value.let {
                            if (pending.policy) {
                                val previous = json.decodeFromString<PolicyDto>(pending.body)
                                require(it.rowVersion > previous.rowVersion)
                                json.encodeToJsonElement(
                                    PolicyDto(
                                        previous.rowVersion,
                                        it.requiresTemperature,
                                        it.minimumTemperature,
                                        it.maximumTemperature,
                                    )
                                )
                            } else
                                json.encodeToJsonElement(
                                    ProductRequest(
                                        it.id,
                                        it.name,
                                        it.category,
                                        it.unit,
                                        it.requiresTemperature,
                                        it.minimumTemperature,
                                        it.maximumTemperature,
                                    )
                                )
                        }
                    is OperationsEntry.ReceiptEntry ->
                        json.encodeToJsonElement(entry.value.input.request())
                    is OperationsEntry.PlanEntry -> json.encodeToJsonElement(entry.value.request())
                    is OperationsEntry.ExecutionEntry ->
                        json.encodeToJsonElement(entry.value.input.request())
                    is OperationsEntry.LotEntry -> error("Solo lectura")
                }
            require(
                comparablePayload(confirmed) ==
                    comparablePayload(json.parseToJsonElement(pending.body))
            )
            OperationsResult.Success(entry)
        }
    }

    private suspend inline fun <reified T> create(
        org: String,
        command: OperationsCommand,
        key: String,
        crossinline convert: (OperationsEntry) -> T,
    ): OperationsResult<T> =
        when (val result = submit(org, prepare(command, key))) {
            is OperationsResult.Success -> OperationsResult.Success(convert(result.value))
            is OperationsResult.Failure -> result
        }

    override suspend fun createSupplier(org: String, input: Supplier, key: String) =
        create(org, OperationsCommand.AddSupplier(input), key) {
            (it as OperationsEntry.SupplierEntry).value
        }

    override suspend fun createProduct(org: String, input: Product, key: String) =
        create(org, OperationsCommand.AddProduct(input), key) {
            (it as OperationsEntry.ProductEntry).value
        }

    override suspend fun updatePolicy(
        org: String,
        id: String,
        input: TemperaturePolicy,
        key: String,
    ) =
        create(org, OperationsCommand.SetTemperaturePolicy(id, input), key) {
            (it as OperationsEntry.ProductEntry).value
        }

    override suspend fun createReceipt(org: String, input: ReceiptInput, key: String) =
        create(org, OperationsCommand.Receive(input), key) {
            (it as OperationsEntry.ReceiptEntry).value
        }

    override suspend fun createPlan(org: String, input: CleaningPlan, key: String) =
        create(org, OperationsCommand.AddPlan(input), key) {
            (it as OperationsEntry.PlanEntry).value
        }

    override suspend fun createExecution(org: String, input: CleaningExecutionInput, key: String) =
        create(org, OperationsCommand.Execute(input), key) {
            (it as OperationsEntry.ExecutionEntry).value
        }
}

@Serializable
private data class Envelope<T>(val data: T, val pagination: PageMetadata = PageMetadata())

@Serializable private data class ErrorEnvelope(val errors: List<ErrorItem> = emptyList())

@Serializable
private data class ErrorItem(val code: String = "", val fields: Map<String, String> = emptyMap())

/** Las marcas ISO equivalentes se comparan como instantes; el cuerpo duradero nunca se modifica. */
private fun comparablePayload(value: JsonElement): JsonElement =
    if (value is JsonObject)
        JsonObject(
            value.mapValues { (key, element) ->
                if (
                    key in setOf("receivedAt", "executedAt", "clientCreatedAt") &&
                        element is JsonPrimitive
                )
                    Instant.parse(element.content).let { instant ->
                        JsonPrimitive("${instant.epochSeconds}:${instant.nanosecondsOfSecond/1000}")
                    }
                else element
            }
        )
    else value
