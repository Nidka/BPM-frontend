package pe.bpm.features.operations.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DecimalInput
import pe.bpm.features.operations.domain.*

internal fun validId(id: String) {
    require(OperationsRules.uuid.matches(id))
}

internal fun validInstant(value: String) {
    require(OperationsRules.instant(value))
}

@Serializable
internal data class SupplierDto(
    val id: String,
    val name: String,
    val taxId: String = "",
    val contactName: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val address: String = "",
) {
    fun domain() =
        Supplier(id, name, taxId, contactName, contactPhone, contactEmail, address).also {
            validId(id)
            require(OperationsRules.supplier(it).isEmpty())
        }
}

internal fun Supplier.dto() =
    SupplierDto(id, name, taxId, contactName, contactPhone, contactEmail, address)

@Serializable
internal data class ProductDto(
    val id: String,
    val name: String,
    val category: String,
    val unit: String,
    val requiresTemperature: Boolean,
    val minimumTemperature: Double? = null,
    val maximumTemperature: Double? = null,
    val rowVersion: Long,
) {
    fun domain() =
        Product(
                id,
                name,
                category,
                unit,
                requiresTemperature,
                minimumTemperature,
                maximumTemperature,
                rowVersion,
            )
            .also {
                validId(id)
                require(rowVersion > 0)
                require(
                    name.operationCharacterCount() in 2..200 &&
                        unit.operationCharacterCount() in 1..24 &&
                        category.operationCharacterCount() <= 100
                )
                require(
                    (minimumTemperature == null && maximumTemperature == null) ||
                        OperationsRules.policy(
                                TemperaturePolicy(
                                    rowVersion,
                                    requiresTemperature,
                                    minimumTemperature,
                                    maximumTemperature,
                                )
                            )
                            .isEmpty()
                )
            }
}

@Serializable
internal data class ProductRequest(
    val id: String,
    val name: String,
    val category: String,
    val unit: String,
    val requiresTemperature: Boolean,
    val minimumTemperature: Double? = null,
    val maximumTemperature: Double? = null,
)

@Serializable
internal data class PolicyDto(
    val rowVersion: Long,
    val requiresTemperature: Boolean,
    val minimumTemperature: Double?,
    val maximumTemperature: Double?,
)

@Serializable
internal data class ReceiptItemDto(
    val id: String,
    val productId: String,
    val batchCode: String,
    val expirationDate: String? = null,
    val quantity: String,
    val temperatureCelsius: Double? = null,
    val decision: String,
    val rejectionReason: String = "",
    val packagingCondition: String? = null,
    val storageLocation: String = "",
    val label: String = "",
    val productName: String = "",
    val unit: String = "",
    val minimumTemperature: Double? = null,
    val maximumTemperature: Double? = null,
) {
    fun input() =
        ReceiptItemInput(
                id,
                productId,
                batchCode,
                expirationDate,
                quantity,
                temperatureCelsius?.let {
                    require(DecimalInput.isValid(it))
                    DecimalInput.format(it)
                },
                ReceiptDecision.valueOf(decision),
                rejectionReason,
                packagingCondition?.let(PackagingCondition::valueOf),
                storageLocation,
                label,
            )
            .also {
                validId(id)
                validId(productId)
                require(QuantityInput.normalize(quantity) != null)
                require(batchCode.operationCharacterCount() <= 100)
                require(expirationDate == null || OperationsRules.date(expirationDate))
                require(
                    productName.operationCharacterCount() <= 200 &&
                        unit.operationCharacterCount() <= 24 &&
                        label.operationCharacterCount() <= 160 &&
                        storageLocation.operationCharacterCount() <= 120 &&
                        rejectionReason.operationCharacterCount() <= 2000
                )
            }

    fun domain() =
        ReceiptItem(
            input(),
            productName,
            unit,
            minimumTemperature,
            maximumTemperature,
            packagingCondition == null,
        )
}

@Serializable
internal data class ReceiptItemRequest(
    val id: String,
    val productId: String,
    val batchCode: String,
    val expirationDate: String?,
    val quantity: String,
    val temperatureCelsius: Double?,
    val decision: String,
    val rejectionReason: String,
    val packagingCondition: String,
    val storageLocation: String,
    val label: String,
)

internal fun ReceiptItemInput.request() =
    ReceiptItemRequest(
        id,
        productId,
        batchCode,
        expirationDate,
        QuantityInput.normalize(quantity) ?: quantity,
        temperatureCelsius?.takeIf { it.isNotBlank() }?.let(DecimalInput::parse),
        decision.name,
        rejectionReason,
        requireNotNull(packagingCondition).name,
        storageLocation,
        label,
    )

@Serializable
internal data class ReceiptDto(
    val id: String,
    val locationId: String,
    val supplierId: String,
    val receivedAt: String,
    val documentReference: String = "",
    val notes: String = "",
    val clientOperationId: String,
    val clientCreatedAt: String,
    val items: List<ReceiptItemDto>,
    val supplierName: String,
    val receivedBy: String,
    val createdAt: String,
    val receivedByName: String = "",
) {
    fun domain(): SupplyReceipt {
        validId(id)
        validId(locationId)
        validId(supplierId)
        validId(clientOperationId)
        validInstant(receivedAt)
        validInstant(clientCreatedAt)
        validInstant(createdAt)
        require(
            items.size in 1..50 &&
                items.map { it.id }.distinct().size == items.size &&
                receivedByName.operationCharacterCount() <= 200 &&
                supplierName.operationCharacterCount() <= 200 &&
                receivedBy.isNotBlank() &&
                receivedBy.length <= 128 &&
                documentReference.operationCharacterCount() <= 100 &&
                notes.operationCharacterCount() <= 2000
        )
        return SupplyReceipt(
            ReceiptInput(
                id,
                locationId,
                supplierId,
                receivedAt,
                documentReference,
                notes,
                clientOperationId,
                clientCreatedAt,
                items.map { it.input() },
            ),
            supplierName,
            receivedBy,
            createdAt,
            items.map { it.domain() },
            receivedByName,
        )
    }
}

@Serializable
internal data class ReceiptRequest(
    val id: String,
    val locationId: String,
    val supplierId: String,
    val receivedAt: String,
    val documentReference: String,
    val notes: String,
    val clientOperationId: String,
    val clientCreatedAt: String,
    val items: List<ReceiptItemRequest>,
)

internal fun ReceiptInput.request() =
    ReceiptRequest(
        id,
        locationId,
        supplierId,
        receivedAt,
        documentReference,
        notes,
        clientOperationId,
        clientCreatedAt,
        items.map { it.request() },
    )

@Serializable
internal data class LotDto(
    val id: String,
    val locationId: String,
    val receiptItemId: String,
    val productId: String,
    val productName: String,
    val unit: String,
    val batchCode: String,
    val expirationDate: String? = null,
    val receivedAt: String,
    val storageLocation: String,
    val label: String,
    val quantityAvailable: String,
    val status: String,
    val expired: Boolean,
) {
    fun domain() =
        StorageLot(
                id,
                locationId,
                receiptItemId,
                productId,
                productName,
                unit,
                batchCode,
                expirationDate,
                receivedAt,
                storageLocation,
                label,
                quantityAvailable,
                status,
                expired,
            )
            .also {
                listOf(id, locationId, receiptItemId, productId).forEach(::validId)
                validInstant(receivedAt)
                require(
                    QuantityInput.normalize(quantityAvailable) != null ||
                        Regex("0+(?:\\.0{1,3})?").matches(quantityAvailable)
                )
                require(status in setOf("AVAILABLE", "CONSUMED", "DISCARDED"))
                require(
                    productName.operationCharacterCount() <= 200 &&
                        unit.operationCharacterCount() <= 24 &&
                        batchCode.operationCharacterCount() <= 100 &&
                        storageLocation.operationCharacterCount() <= 120 &&
                        label.operationCharacterCount() <= 160
                )
                require(expirationDate == null || OperationsRules.date(expirationDate))
            }
}

@Serializable
internal data class PlanDto(
    val id: String,
    val locationId: String,
    val area: String,
    val task: String,
    val instructions: String = "",
    val productName: String = "",
    val frequency: String,
    val dueTime: String = "",
    val active: Boolean = true,
) {
    fun domain() =
        CleaningPlan(
                id,
                locationId,
                area,
                task,
                instructions,
                productName,
                CleaningFrequency.valueOf(frequency),
                dueTime,
                active,
            )
            .also {
                validId(id)
                require(OperationsRules.plan(it).isEmpty())
            }
}

@Serializable
internal data class PlanRequest(
    val id: String,
    val locationId: String,
    val area: String,
    val task: String,
    val instructions: String,
    val productName: String,
    val frequency: String,
    val dueTime: String,
)

internal fun CleaningPlan.request() =
    PlanRequest(id, locationId, area, task, instructions, productName, frequency.name, dueTime)

@Serializable
internal data class ExecutionDto(
    val id: String,
    val locationId: String,
    val cleaningPlanId: String,
    val executedAt: String,
    val result: String,
    val observation: String = "",
    val clientOperationId: String,
    val clientCreatedAt: String,
    val area: String,
    val task: String,
    val responsibleUserId: String,
    val responsibleName: String,
    val createdAt: String,
) {
    fun domain(): CleaningExecution {
        val input =
            CleaningExecutionInput(
                id,
                locationId,
                cleaningPlanId,
                executedAt,
                CleaningResult.valueOf(result),
                observation,
                clientOperationId,
                clientCreatedAt,
            )
        validId(id)
        validId(clientOperationId)
        validInstant(clientCreatedAt)
        validInstant(createdAt)
        require(OperationsRules.execution(input).isEmpty())
        require(
            area.operationCharacterCount() <= 100 &&
                task.operationCharacterCount() <= 240 &&
                responsibleName.operationCharacterCount() <= 160 &&
                responsibleUserId.isNotBlank() &&
                responsibleUserId.length <= 128
        )
        return CleaningExecution(input, area, task, responsibleUserId, responsibleName, createdAt)
    }
}

@Serializable
internal data class ExecutionRequest(
    val id: String,
    val locationId: String,
    val cleaningPlanId: String,
    val executedAt: String,
    val result: String,
    val observation: String,
    val clientOperationId: String,
    val clientCreatedAt: String,
)

internal fun CleaningExecutionInput.request() =
    ExecutionRequest(
        id,
        locationId,
        cleaningPlanId,
        executedAt,
        result.name,
        observation,
        clientOperationId,
        clientCreatedAt,
    )
