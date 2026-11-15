package pe.bpm.features.operations.domain

import kotlin.time.Instant
import pe.bpm.core.domain.*

enum class StockMovementType {
    CONSUMPTION,
    DISCARD,
}

enum class DiscardReason {
    EXPIRED,
    DETERIORATED,
    CONTAMINATION,
    OTHER,
}

/** Operación del cliente: `id` y `clientOperationId` son UUID estables para reintentar sin duplicar. */
data class StockMovementInput(
    val id: String,
    val locationId: String,
    val lotId: String,
    val type: StockMovementType,
    val quantity: String,
    val reason: DiscardReason? = null,
    val note: String = "",
    val clientOperationId: String,
    val clientCreatedAt: String,
)

data class StockMovement(
    val input: StockMovementInput,
    val productName: String,
    val unit: String,
    val batchCode: String,
    /** Saldo del lote tras el movimiento, en decimal canónico. */
    val balanceAfter: String,
    val performedBy: String,
    val performedByName: String,
    val createdAt: String,
)

data class StockFilter(val lotId: String? = null, val type: StockMovementType? = null)

object StockAccess {
    /** Registran el CHEF de su sede y el ADMIN miembro (todas las sedes); SUPER_ADMIN sin membresía no opera. */
    fun canRecord(role: String) = role in setOf("CHEF", "ADMIN")

    fun canView(role: String) = role in setOf("SUPER_ADMIN", "ADMIN", "CHEF")
}

object StockRules {
    /** Convierte un decimal con hasta tres cifras a milésimas; evita coma flotante. */
    fun milli(value: String): Long? {
        val text = value.trim().replace(',', '.')
        if (!Regex("[0-9]{1,9}(?:\\.[0-9]{1,3})?").matches(text)) return null
        val whole = text.substringBefore('.').toLong()
        val fraction = text.substringAfter('.', "").padEnd(3, '0').toLong()
        return whole * 1000 + fraction
    }

    /** Un lote admite movimientos solo si está disponible y tiene saldo. */
    fun available(lot: StorageLot) = lot.status == "AVAILABLE" && (milli(lot.quantityAvailable) ?: 0) > 0

    /**
     * Errores por campo. Con el lote conocido se anticipan saldo insuficiente y lote vencido, pero
     * el servidor mantiene la última palabra (otro usuario pudo mover el saldo antes).
     */
    fun errors(input: StockMovementInput, lot: StorageLot?, now: String): Map<String, String> =
        buildMap {
            if (!OperationsRules.uuid.matches(input.lotId) || lot == null)
                put("lotId", "Selecciona un lote.")
            if (!OperationsRules.uuid.matches(input.locationId))
                put("locationId", "Selecciona una sede.")
            val requested = QuantityInput.normalize(input.quantity)?.let(::milli)
            if (requested == null) put("quantity", requireNotNull(QuantityInput.error("")))
            if (lot != null) {
                if (!available(lot)) put("lotId", "El lote ya no tiene saldo disponible.")
                else if (requested != null && requested > (milli(lot.quantityAvailable) ?: 0))
                    put(
                        "quantity",
                        "La cantidad supera el saldo del lote (${lot.quantityAvailable} ${lot.unit}).",
                    )
                if (input.type == StockMovementType.CONSUMPTION && lot.expired)
                    put(
                        "type",
                        "Un lote vencido no se consume. Registra un descarte por vencimiento.",
                    )
            }
            when (input.type) {
                StockMovementType.CONSUMPTION ->
                    if (input.reason != null) put("reason", "El consumo no lleva motivo.")
                StockMovementType.DISCARD -> {
                    if (input.reason == null) put("reason", "Elige el motivo del descarte.")
                    if (input.reason == DiscardReason.OTHER && input.note.isBlank())
                        put("note", "Explica el motivo del descarte.")
                }
            }
            InputRules.text(0, 500).error(input.note)?.let { put("note", it) }
            val current = runCatching { Instant.parse(now) }.getOrNull()
            val created = runCatching { Instant.parse(input.clientCreatedAt) }.getOrNull()
            if (
                created == null || current == null || created.epochSeconds - current.epochSeconds > 300
            )
                put("clientCreatedAt", "Comprueba la fecha y hora del dispositivo.")
        }
}

interface StockRepository {
    suspend fun movements(
        org: String,
        site: String,
        filter: StockFilter,
        page: PageRequest,
    ): OperationsResult<Page<StockMovement>>

    suspend fun record(
        org: String,
        input: StockMovementInput,
        key: String,
    ): OperationsResult<StockMovement>
}

class ManageStock(private val repository: StockRepository) {
    suspend fun history(
        org: String,
        site: String,
        role: String,
        filter: StockFilter,
        page: PageRequest,
    ): OperationsResult<Page<StockMovement>> =
        if (!StockAccess.canView(role)) OperationsResult.Failure(OperationsFailure.DENIED)
        else repository.movements(org, site, filter, page)

    fun errors(input: StockMovementInput, lot: StorageLot?, now: String) =
        StockRules.errors(input, lot, now)

    suspend fun record(
        org: String,
        role: String,
        input: StockMovementInput,
        lot: StorageLot?,
        now: String,
        key: String,
    ): OperationsResult<StockMovement> {
        if (!StockAccess.canRecord(role)) return OperationsResult.Failure(OperationsFailure.DENIED)
        val errors = errors(input, lot, now)
        if (errors.isNotEmpty()) return OperationsResult.Failure(OperationsFailure.INVALID, errors)
        return repository.record(
            org,
            input.copy(
                quantity = QuantityInput.normalize(input.quantity) ?: input.quantity,
                note = input.note.trim(),
            ),
            key,
        )
    }
}
