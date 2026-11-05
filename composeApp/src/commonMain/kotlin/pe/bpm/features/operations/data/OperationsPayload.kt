package pe.bpm.features.operations.data

import kotlinx.serialization.json.Json
import pe.bpm.features.operations.domain.*

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

internal fun encodeOperation(command: OperationsCommand, key: String): PendingOperation {
    require(key.length in 8..100 && key.all { it.code in 33..126 })
    return when (command) {
        is OperationsCommand.AddSupplier ->
            PendingOperation(
                OperationsResource.SUPPLIERS,
                command.input.id,
                key,
                json.encodeToString(command.input.dto()),
            )
        is OperationsCommand.AddProduct ->
            command.input.let {
                PendingOperation(
                    OperationsResource.PRODUCTS,
                    it.id,
                    key,
                    json.encodeToString(
                        ProductRequest(
                            it.id,
                            it.name,
                            it.category,
                            it.unit,
                            it.requiresTemperature,
                            it.minimumTemperature,
                            it.maximumTemperature,
                        )
                    ),
                )
            }
        is OperationsCommand.SetTemperaturePolicy ->
            command.input.let {
                PendingOperation(
                    OperationsResource.PRODUCTS,
                    command.productId,
                    key,
                    json.encodeToString(
                        PolicyDto(
                            it.rowVersion,
                            it.requiresTemperature,
                            it.minimumTemperature,
                            it.maximumTemperature,
                        )
                    ),
                    true,
                )
            }
        is OperationsCommand.Receive ->
            PendingOperation(
                OperationsResource.RECEIPTS,
                command.input.id,
                key,
                json.encodeToString(command.input.request()),
            )
        is OperationsCommand.AddPlan ->
            PendingOperation(
                OperationsResource.PLANS,
                command.input.id,
                key,
                json.encodeToString(command.input.request()),
            )
        is OperationsCommand.Execute ->
            PendingOperation(
                OperationsResource.EXECUTIONS,
                command.input.id,
                key,
                json.encodeToString(command.input.request()),
            )
    }.also { validId(it.id) }
}
