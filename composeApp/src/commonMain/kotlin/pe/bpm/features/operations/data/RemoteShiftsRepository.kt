package pe.bpm.features.operations.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.*
import pe.bpm.core.network.*
import pe.bpm.features.operations.domain.*

/** Transporte común de turnos y movimientos: sesión, rutas validadas y errores públicos. */
internal class PlusTransport(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit,
    private val onSessionResponse: (HttpResponse) -> Unit,
    private val token: suspend () -> String?,
) {
    fun url(org: String, path: String): String {
        require(org.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return "${config.apiBaseUrl}/api/v1/organizations/$org/$path"
    }

    /** Ejecuta una llamada autenticada y traduce cualquier excepción a un fallo público. */
    suspend fun <T> run(
        block: suspend (access: String, check: suspend (HttpResponse) -> OperationsResult.Failure?) -> OperationsResult<T>
    ): OperationsResult<T> =
        try {
            val access =
                token()?.takeIf { it.isNotBlank() }
                    ?: return OperationsResult.Failure(OperationsFailure.SESSION)
            block(access) { response ->
                onSessionResponse(response)
                if (response.status.isSuccess()) null
                else operationsFailure(response, access, onSessionRejected)
            }
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

    val http: HttpClient
        get() = client
}

@Serializable private data class ShiftEnvelope(val data: ShiftDto)

@Serializable
private data class ShiftList(val data: List<ShiftDto>, val pagination: PageMetadata = PageMetadata())

@Serializable
internal data class ShiftDto(
    val id: String,
    val locationId: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val timezone: String = "",
    val crossesMidnight: Boolean = false,
    val active: Boolean = true,
    val openNow: Boolean = false,
    val rowVersion: Long,
    val createdAt: String = "",
) {
    fun domain(): Shift {
        validId(id)
        validId(locationId)
        require(rowVersion > 0)
        require(ShiftRules.errors(name, startTime, endTime).isEmpty())
        require(timezone.length <= 100)
        if (createdAt.isNotEmpty()) Instant.parse(createdAt)
        return Shift(
            id,
            locationId,
            name,
            startTime,
            endTime,
            timezone,
            crossesMidnight,
            active,
            openNow,
            rowVersion,
            createdAt,
        )
    }
}

@Serializable
private data class ShiftCreateRequest(
    val id: String,
    val locationId: String,
    val name: String,
    val startTime: String,
    val endTime: String,
)

@Serializable
private data class ShiftUpdateRequest(
    val name: String,
    val startTime: String,
    val endTime: String,
    val rowVersion: Long,
)

@Serializable private data class RowVersionRequest(val rowVersion: Long)

/** Turnos por sede; el servidor valida rol, sede, horario y solapes. */
class RemoteShiftsRepository
internal constructor(private val transport: PlusTransport) : ShiftRepository {
    constructor(
        client: HttpClient,
        config: AppConfig,
        onSessionRejected: (String) -> Unit = {},
        onSessionResponse: (HttpResponse) -> Unit = {},
        token: suspend () -> String?,
    ) : this(PlusTransport(client, config, onSessionRejected, onSessionResponse, token))

    override suspend fun shifts(
        org: String,
        site: String,
        includeInactive: Boolean,
        page: PageRequest,
    ): OperationsResult<Page<Shift>> = transport.run { access, check ->
        validId(site)
        val response =
            transport.http.get(transport.url(org, "shifts")) {
                bearerAuth(access)
                pageParameters(page)
                parameter("locationId", site)
                parameter("status", if (includeInactive) "ALL" else "ACTIVE")
            }
        check(response)
            ?: response.body<ShiftList>().let { list ->
                val items = list.data.map { it.domain().also { s -> require(s.locationId == site) } }
                require(items.map { it.id }.distinct().size == items.size)
                OperationsResult.Success(checkedPage(items, list.pagination, page))
            }
    }

    override suspend fun create(org: String, input: ShiftInput, key: String) =
        write(org, "shifts", HttpMethod.Post, key, ShiftCreateRequest(input.id, input.locationId, input.name, input.startTime, input.endTime)) { shift ->
            require(
                shift.id == input.id &&
                    shift.locationId == input.locationId &&
                    shift.name == input.name &&
                    shift.startTime == input.startTime &&
                    shift.endTime == input.endTime &&
                    shift.active
            )
        }

    override suspend fun update(org: String, id: String, change: ShiftChange, key: String) =
        write(org, "shifts/$id", HttpMethod.Put, key, ShiftUpdateRequest(change.name, change.startTime, change.endTime, change.rowVersion)) { shift ->
            require(
                shift.id == id &&
                    shift.name == change.name &&
                    shift.startTime == change.startTime &&
                    shift.endTime == change.endTime &&
                    shift.rowVersion > change.rowVersion
            )
        }

    override suspend fun deactivate(org: String, id: String, rowVersion: Long, key: String) =
        write(org, "shifts/$id/deactivate", HttpMethod.Post, key, RowVersionRequest(rowVersion)) { shift ->
            require(shift.id == id && !shift.active)
        }

    private suspend inline fun <reified B : Any> write(
        org: String,
        path: String,
        verb: HttpMethod,
        key: String,
        body: B,
        crossinline confirm: (Shift) -> Unit,
    ): OperationsResult<Shift> = transport.run { access, check ->
        require(key.length in 8..100 && key.all { it.code in 33..126 })
        require(path.split('/').drop(1).firstOrNull()?.let { OperationsRules.uuid.matches(it) } != false)
        val response =
            transport.http.request(transport.url(org, path)) {
                method = verb
                bearerAuth(access)
                header("Idempotency-Key", key)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        check(response)
            ?: OperationsResult.Success(
                response.body<ShiftEnvelope>().data.domain().also { confirm(it) }
            )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class StockMovementRequest(
    val id: String,
    val locationId: String,
    val lotId: String,
    val type: String,
    val quantity: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val reason: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: String? = null,
    val clientOperationId: String,
    val clientCreatedAt: String,
)

internal fun StockMovementInput.request() =
    StockMovementRequest(
        id,
        locationId,
        lotId,
        type.name,
        quantity,
        reason?.name,
        note.takeIf { it.isNotBlank() },
        clientOperationId,
        clientCreatedAt,
    )

@Serializable
internal data class StockMovementDto(
    val id: String,
    val locationId: String,
    val lotId: String,
    val type: String,
    val quantity: String,
    val reason: String = "",
    val note: String = "",
    val clientOperationId: String,
    val clientCreatedAt: String,
    val productName: String = "",
    val unit: String = "",
    val batchCode: String = "",
    val balanceAfter: String,
    val performedBy: String,
    val performedByName: String = "",
    val createdAt: String,
) {
    fun domain(): StockMovement {
        listOf(id, locationId, lotId, clientOperationId).forEach(::validId)
        validInstant(clientCreatedAt)
        validInstant(createdAt)
        require(QuantityInput.normalize(quantity) != null)
        require(StockRules.milli(balanceAfter) != null)
        require(performedBy.isNotBlank() && performedBy.length <= 128)
        require(note.operationCharacterCount() <= 500 && productName.operationCharacterCount() <= 200)
        val movementType = StockMovementType.valueOf(type)
        val discard = reason.takeIf { it.isNotEmpty() }?.let(DiscardReason::valueOf)
        require(movementType == StockMovementType.DISCARD || discard == null)
        return StockMovement(
            StockMovementInput(
                id,
                locationId,
                lotId,
                movementType,
                quantity,
                discard,
                note,
                clientOperationId,
                clientCreatedAt,
            ),
            productName,
            unit,
            batchCode,
            balanceAfter,
            performedBy,
            performedByName,
            createdAt,
        )
    }
}

@Serializable private data class StockEnvelope(val data: StockMovementDto)

@Serializable
private data class StockList(
    val data: List<StockMovementDto>,
    val pagination: PageMetadata = PageMetadata(),
)

/** Consumos y descartes: el servidor descuenta el saldo y garantiza la idempotencia. */
class RemoteStockRepository
internal constructor(private val transport: PlusTransport) : StockRepository {
    constructor(
        client: HttpClient,
        config: AppConfig,
        onSessionRejected: (String) -> Unit = {},
        onSessionResponse: (HttpResponse) -> Unit = {},
        token: suspend () -> String?,
    ) : this(PlusTransport(client, config, onSessionRejected, onSessionResponse, token))

    override suspend fun movements(
        org: String,
        site: String,
        filter: StockFilter,
        page: PageRequest,
    ): OperationsResult<Page<StockMovement>> = transport.run { access, check ->
        validId(site)
        filter.lotId?.let(::validId)
        val response =
            transport.http.get(transport.url(org, "stock-movements")) {
                bearerAuth(access)
                pageParameters(page)
                parameter("locationId", site)
                filter.lotId?.let { parameter("lotId", it) }
                filter.type?.let { parameter("type", it.name) }
            }
        check(response)
            ?: response.body<StockList>().let { list ->
                val items =
                    list.data.map {
                        it.domain().also { m ->
                            require(m.input.locationId == site)
                            require(filter.lotId == null || m.input.lotId == filter.lotId)
                            require(filter.type == null || m.input.type == filter.type)
                        }
                    }
                require(items.map { it.input.id }.distinct().size == items.size)
                OperationsResult.Success(checkedPage(items, list.pagination, page))
            }
    }

    override suspend fun record(org: String, input: StockMovementInput, key: String) =
        transport.run<StockMovement> { access, check ->
            require(key.length in 8..100 && key.all { it.code in 33..126 })
            listOf(input.id, input.locationId, input.lotId, input.clientOperationId).forEach(::validId)
            require(QuantityInput.normalize(input.quantity) == input.quantity)
            val response =
                transport.http.post(transport.url(org, "stock-movements")) {
                    bearerAuth(access)
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody(input.request())
                }
            check(response)
                ?: response.body<StockEnvelope>().data.domain().let { movement ->
                    val sent = input
                    val got = movement.input
                    require(
                        got.id == sent.id &&
                            got.locationId == sent.locationId &&
                            got.lotId == sent.lotId &&
                            got.type == sent.type &&
                            QuantityInput.normalize(got.quantity) == sent.quantity &&
                            got.reason == sent.reason &&
                            got.note == sent.note &&
                            got.clientOperationId == sent.clientOperationId
                    )
                    OperationsResult.Success(movement)
                }
        }
}
