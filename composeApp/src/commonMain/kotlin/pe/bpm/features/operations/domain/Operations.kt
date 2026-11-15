package pe.bpm.features.operations.domain

import pe.bpm.core.domain.*

// Los identificadores y permisos se vuelven a comprobar en el servidor.
enum class OperationsResource(val path: String, val title: String, val needsSite: Boolean) {
    SUPPLIERS("suppliers", "Proveedores", false),
    PRODUCTS("products", "Productos", false),
    RECEIPTS("supply-receipts", "Recepciones", true),
    LOTS("storage-lots", "Stock PEPS", true),
    PLANS("cleaning-plans", "Planes de limpieza", true),
    EXECUTIONS("cleaning-executions", "Limpieza realizada", true),
}

object OperationsAccess {
    fun canRead(role: String, resource: OperationsResource) =
        when (role) {
            "SUPER_ADMIN",
            "ADMIN",
            "CHEF" -> true
            "WORKER" -> resource in setOf(OperationsResource.PLANS, OperationsResource.EXECUTIONS)
            else -> false
        }

    fun canCreate(role: String, resource: OperationsResource) =
        when (resource) {
            OperationsResource.SUPPLIERS,
            OperationsResource.PRODUCTS,
            OperationsResource.PLANS -> role in setOf("ADMIN", "SUPER_ADMIN")
            OperationsResource.RECEIPTS -> role in setOf("CHEF", "ADMIN")
            OperationsResource.EXECUTIONS -> role in setOf("CHEF", "ADMIN", "WORKER")
            OperationsResource.LOTS -> false
        }
}

enum class ReceiptDecision {
    ACCEPTED,
    REJECTED,
    OBSERVED,
}

enum class PackagingCondition {
    INTACT,
    DAMAGED,
}

enum class CleaningFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    /** Se ejecuta una vez por turno; al registrar la limpieza el turno es obligatorio. */
    PER_SHIFT,
}

enum class CleaningResult {
    COMPLETED,
    NOT_COMPLETED,
    OBSERVED,
}

data class Supplier(
    val id: String,
    val name: String,
    val taxId: String = "",
    val contactName: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val address: String = "",
)

data class Product(
    val id: String,
    val name: String,
    val category: String,
    val unit: String,
    val requiresTemperature: Boolean,
    val minimumTemperature: Double? = null,
    val maximumTemperature: Double? = null,
    val rowVersion: Long = 1,
) {
    val policyMissing
        get() = requiresTemperature && (minimumTemperature == null || maximumTemperature == null)
}

data class TemperaturePolicy(
    val rowVersion: Long,
    val requiresTemperature: Boolean,
    val minimumTemperature: Double?,
    val maximumTemperature: Double?,
)

data class ReceiptItemInput(
    val id: String,
    val productId: String,
    val batchCode: String = "",
    val expirationDate: String? = null,
    val quantity: String = "",
    val temperatureCelsius: String? = null,
    val decision: ReceiptDecision = ReceiptDecision.ACCEPTED,
    val rejectionReason: String = "",
    val packagingCondition: PackagingCondition? = null,
    val storageLocation: String = "",
    val label: String = "",
)

data class ReceiptInput(
    val id: String,
    val locationId: String,
    val supplierId: String,
    val receivedAt: String,
    val documentReference: String = "",
    val notes: String = "",
    val clientOperationId: String,
    val clientCreatedAt: String,
    val items: List<ReceiptItemInput>,
)

data class ReceiptItem(
    val input: ReceiptItemInput,
    val productName: String,
    val unit: String,
    val minimumTemperature: Double?,
    val maximumTemperature: Double?,
    val legacyPackaging: Boolean = false,
)

data class SupplyReceipt(
    val input: ReceiptInput,
    val supplierName: String,
    val receivedBy: String,
    val createdAt: String,
    val items: List<ReceiptItem>,
    val receivedByName: String = "",
)

data class StorageLot(
    val id: String,
    val locationId: String,
    val receiptItemId: String,
    val productId: String,
    val productName: String,
    val unit: String,
    val batchCode: String,
    val expirationDate: String?,
    val receivedAt: String,
    val storageLocation: String,
    val label: String,
    /** Saldo actual tras consumos y descartes. */
    val quantityAvailable: String,
    val status: String,
    val expired: Boolean,
    /** Cantidad aceptada en la recepción; vacía en servidores anteriores. */
    val receivedQuantity: String = "",
)

data class CleaningPlan(
    val id: String,
    val locationId: String,
    val area: String,
    val task: String,
    val instructions: String = "",
    val productName: String = "",
    val frequency: CleaningFrequency = CleaningFrequency.DAILY,
    val dueTime: String = "",
    val active: Boolean = true,
)

data class CleaningExecutionInput(
    val id: String,
    val locationId: String,
    val cleaningPlanId: String,
    val executedAt: String,
    val result: CleaningResult,
    val observation: String = "",
    val clientOperationId: String,
    val clientCreatedAt: String,
    val shiftId: String? = null,
)

data class CleaningExecution(
    val input: CleaningExecutionInput,
    val area: String,
    val task: String,
    val responsibleUserId: String,
    val responsibleName: String,
    val createdAt: String,
    val shiftName: String = "",
)

enum class OperationsFailure {
    SESSION,
    DENIED,
    NOT_FOUND,
    INVALID,
    CONFLICT,
    CONNECTION,
    UNAVAILABLE,
    INVALID_RESPONSE,
    INVALID_PAGE,
    RATE_LIMITED,
    LOCAL,
    /** El turno tiene controles programados activos. */
    SHIFT_IN_USE,
    /** El turno elegido no está abierto en este momento. */
    SHIFT_OUT_OF_HOURS,
    /** El lote no tiene saldo suficiente. */
    INSUFFICIENT_STOCK,
    /** El lote está vencido y no admite consumo. */
    LOT_EXPIRED,
    /** El registro quedó guardado en este dispositivo y se enviará al recuperar la conexión (éxito local pendiente). */
    QUEUED,
}

/** Rechazos que cierran la operación: el cuerpo enviado no volverá a reintentarse tal cual. */
val OperationsFailure.definitive: Boolean
    get() =
        this in
            setOf(
                OperationsFailure.INVALID,
                OperationsFailure.DENIED,
                OperationsFailure.NOT_FOUND,
                OperationsFailure.CONFLICT,
                OperationsFailure.SHIFT_IN_USE,
                OperationsFailure.SHIFT_OUT_OF_HOURS,
                OperationsFailure.INSUFFICIENT_STOCK,
                OperationsFailure.LOT_EXPIRED,
            )

sealed interface OperationsResult<out T> {
    data class Success<T>(val value: T) : OperationsResult<T>

    data class Failure(
        val reason: OperationsFailure,
        val fields: Map<String, String> = emptyMap(),
    ) : OperationsResult<Nothing>
}

interface OperationsRepository {
    suspend fun authorize(
        org: String,
        site: String?,
        role: String,
        resource: OperationsResource,
    ): OperationsResult<Unit>

    suspend fun suppliers(org: String, page: PageRequest): OperationsResult<Page<Supplier>>

    suspend fun products(org: String, page: PageRequest): OperationsResult<Page<Product>>

    suspend fun receipts(
        org: String,
        site: String,
        page: PageRequest,
    ): OperationsResult<Page<SupplyReceipt>>

    suspend fun lots(
        org: String,
        site: String,
        page: PageRequest,
    ): OperationsResult<Page<StorageLot>>

    suspend fun plans(
        org: String,
        site: String,
        page: PageRequest,
    ): OperationsResult<Page<CleaningPlan>>

    suspend fun executions(
        org: String,
        site: String,
        page: PageRequest,
    ): OperationsResult<Page<CleaningExecution>>

    suspend fun receipt(org: String, id: String): OperationsResult<SupplyReceipt>

    suspend fun execution(org: String, id: String): OperationsResult<CleaningExecution>

    suspend fun createSupplier(
        org: String,
        input: Supplier,
        key: String,
    ): OperationsResult<Supplier>

    suspend fun createProduct(org: String, input: Product, key: String): OperationsResult<Product>

    suspend fun updatePolicy(
        org: String,
        id: String,
        input: TemperaturePolicy,
        key: String,
    ): OperationsResult<Product>

    suspend fun createReceipt(
        org: String,
        input: ReceiptInput,
        key: String,
    ): OperationsResult<SupplyReceipt>

    suspend fun createPlan(
        org: String,
        input: CleaningPlan,
        key: String,
    ): OperationsResult<CleaningPlan>

    suspend fun createExecution(
        org: String,
        input: CleaningExecutionInput,
        key: String,
    ): OperationsResult<CleaningExecution>
}
