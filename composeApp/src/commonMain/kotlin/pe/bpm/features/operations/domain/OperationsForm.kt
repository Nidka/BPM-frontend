package pe.bpm.features.operations.domain

import pe.bpm.core.domain.DecimalInput

/** Construcción pura del comando usado tanto al enviar como al verificar el borrador. */
fun OperationsForm.command(resource: OperationsResource, siteId: String?): OperationsCommand {
    val f = this
    fun v(key: String) = f.values[key].orEmpty().trim()
    val date = v("dateTime").ifEmpty { f.createdAt }
    return when (resource) {
        OperationsResource.SUPPLIERS ->
            OperationsCommand.AddSupplier(
                Supplier(
                    f.id,
                    v("name"),
                    v("taxId"),
                    v("contactName"),
                    v("contactPhone"),
                    v("contactEmail"),
                    v("address"),
                )
            )
        OperationsResource.PRODUCTS -> {
            val required = v("requiresTemperature") == "true"
            val min = v("minimumTemperature").takeIf { it.isNotBlank() }?.let(DecimalInput::parse)
            val max = v("maximumTemperature").takeIf { it.isNotBlank() }?.let(DecimalInput::parse)
            if (f.policyProductId != null)
                OperationsCommand.SetTemperaturePolicy(
                    f.policyProductId,
                    TemperaturePolicy(f.rowVersion, required, min, max),
                )
            else
                OperationsCommand.AddProduct(
                    Product(f.id, v("name"), v("category"), v("unit"), required, min, max)
                )
        }
        OperationsResource.RECEIPTS ->
            OperationsCommand.Receive(
                ReceiptInput(
                    f.id,
                    siteId.orEmpty(),
                    v("supplierId"),
                    date,
                    v("documentReference"),
                    v("notes"),
                    f.id,
                    f.createdAt,
                    f.items.map {
                        it.copy(
                            batchCode = it.batchCode.trim(),
                            expirationDate = it.expirationDate?.trim(),
                            rejectionReason = it.rejectionReason.trim(),
                            storageLocation = it.storageLocation.trim(),
                            label = it.label.trim(),
                        )
                    },
                )
            )
        OperationsResource.PLANS ->
            OperationsCommand.AddPlan(
                CleaningPlan(
                    f.id,
                    siteId.orEmpty(),
                    v("area"),
                    v("task"),
                    v("instructions"),
                    v("productName"),
                    v("frequency").takeIf { it.isNotEmpty() }?.let(CleaningFrequency::valueOf)
                        ?: CleaningFrequency.DAILY,
                    v("dueTime"),
                )
            )
        OperationsResource.EXECUTIONS ->
            OperationsCommand.Execute(
                CleaningExecutionInput(
                    f.id,
                    siteId.orEmpty(),
                    v("cleaningPlanId"),
                    date,
                    v("result").takeIf { it.isNotEmpty() }?.let(CleaningResult::valueOf)
                        ?: CleaningResult.COMPLETED,
                    v("observation"),
                    f.id,
                    f.createdAt,
                )
            )
        OperationsResource.LOTS -> error("Solo lectura")
    }
}
