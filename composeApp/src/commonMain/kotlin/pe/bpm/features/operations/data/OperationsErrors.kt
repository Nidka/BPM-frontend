package pe.bpm.features.operations.data

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.features.operations.domain.*

@Serializable internal data class ErrorEnvelope(val errors: List<ErrorItem> = emptyList())

@Serializable
internal data class ErrorItem(val code: String = "", val fields: Map<String, String> = emptyMap())

/** Campos que el proxy puede devolver; cualquier otro nombre se descarta. */
private val publicFields =
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
        "shiftId",
        "startTime",
        "endTime",
        "lotId",
        "type",
        "quantity",
        "reason",
        "note",
    )

private val itemField =
    Regex(
        """items\.(?:[0-9]|[1-4][0-9])\.(?:productId|batchCode|expirationDate|quantity|temperatureCelsius|decision|rejectionReason|packagingCondition|storageLocation|label)"""
    )

/**
 * Traduce un rechazo HTTP a un motivo público. Los códigos de negocio conocidos (turno en uso,
 * saldo insuficiente, lote vencido, turno cerrado) se distinguen del conflicto genérico; el
 * mensaje del servidor nunca se refleja.
 */
internal suspend fun operationsFailure(
    response: HttpResponse,
    access: String,
    onSessionRejected: (String) -> Unit,
): OperationsResult.Failure {
    val status = response.status.value
    if (status == 401) onSessionRejected(access)
    val body =
        if (status in setOf(400, 409, 422))
            try {
                response.body<ErrorEnvelope>()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        else null
    val code = body?.errors?.firstOrNull()?.code.orEmpty()
    val reason =
        when (status) {
            400 -> OperationsFailure.INVALID
            401 -> OperationsFailure.SESSION
            403 -> OperationsFailure.DENIED
            404 -> OperationsFailure.NOT_FOUND
            409 ->
                when (code) {
                    "SHIFT_IN_USE" -> OperationsFailure.SHIFT_IN_USE
                    "INSUFFICIENT_STOCK" -> OperationsFailure.INSUFFICIENT_STOCK
                    "LOT_EXPIRED" -> OperationsFailure.LOT_EXPIRED
                    else -> OperationsFailure.CONFLICT
                }
            422 ->
                if (code == "SHIFT_OUT_OF_HOURS") OperationsFailure.SHIFT_OUT_OF_HOURS
                else OperationsFailure.INVALID
            429 -> OperationsFailure.RATE_LIMITED
            else -> OperationsFailure.UNAVAILABLE
        }
    val fields =
        if (reason == OperationsFailure.INVALID || reason == OperationsFailure.SHIFT_OUT_OF_HOURS)
            body
                ?.errors
                .orEmpty()
                .flatMap { it.fields.keys }
                .filter { it in publicFields || itemField.matches(it) }
                .associateWith { "Revisa este campo y vuelve a intentar." }
        else emptyMap()
    return OperationsResult.Failure(reason, fields)
}
