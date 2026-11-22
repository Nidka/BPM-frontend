package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.operations.domain.*

/**
 * Detalle de un registro de operación diaria (specs/LAYOUT.md §5): título, resumen y datos etiqueta/valor en
 * una o dos columnas según el ancho del panel. Con [onBack] muestra «Volver a la lista» cuando ocupa la pantalla.
 */
@Composable
internal fun OperationDetails(entry: OperationsEntry, onBack: (() -> Unit)? = null) {
    BpmDetailPane(entry.title(), subtitle = entry.subtitle(), onBack = onBack) {
        when (entry) {
            is OperationsEntry.SupplierEntry -> with(entry.value) {
                BpmSection("Datos del proveedor") {
                    BpmDetailFields(listOf(
                        BpmDetailField("Identificación", taxId), BpmDetailField("Contacto", contactName),
                        BpmDetailField("Teléfono", contactPhone), BpmDetailField("Correo", contactEmail),
                        BpmDetailField("Dirección", address),
                    ))
                }
            }
            is OperationsEntry.ProductEntry -> BpmSection("Datos del producto") {
                BpmDetailFields(listOf(BpmDetailField("Categoría", entry.value.category), BpmDetailField("Unidad", entry.value.unit)))
            }
            is OperationsEntry.ReceiptEntry -> with(entry.value) {
                BpmSection("Datos de la recepción") {
                    BpmDetailFields(listOf(
                        BpmDetailField("Referencia", input.documentReference),
                        BpmDetailField("Responsable", receivedByName.ifEmpty { "Responsable registrado" }),
                        BpmDetailField("Confirmado", displayDateTime(createdAt)),
                    ))
                    if (input.notes.isNotBlank()) Text(input.notes)
                }
                BpmSection(if (items.size == 1) "1 producto recibido" else "${items.size} productos recibidos") {
                    items.forEach {
                        ReceiptItemSummary(it.input, it.productName)
                        if (it.legacyPackaging) Text("Registro anterior: estado del envase sin información.")
                        if (it.minimumTemperature != null && it.maximumTemperature != null)
                            Text("Criterio al recibir: ${it.minimumTemperature} a ${it.maximumTemperature} °C")
                    }
                }
            }
            is OperationsEntry.LotEntry -> with(entry.value) {
                BpmSection("Datos del lote") {
                    BpmDetailFields(listOf(
                        BpmDetailField("Lote", batchCode), BpmDetailField("Estado", lotStatus(status)),
                        BpmDetailField("Cantidad recibida", receivedQuantity.takeIf { it.isNotEmpty() }?.let { "$it $unit" }),
                        BpmDetailField("Saldo actual", "$quantityAvailable $unit"),
                        BpmDetailField("Recepción", displayDateTime(receivedAt)), BpmDetailField("Vencimiento", displayDate(expirationDate)),
                        BpmDetailField("Ubicación", storageLocation), BpmDetailField("Etiqueta", label),
                    ))
                }
            }
            is OperationsEntry.PlanEntry -> with(entry.value) {
                BpmSection("Plan de limpieza") {
                    BpmDetailFields(listOf(
                        BpmDetailField("Frecuencia", frequencyLabel(frequency)), BpmDetailField("Estado", if (active) "Activo" else "Inactivo"),
                        BpmDetailField("Producto", productName), BpmDetailField("Hora", dueTime),
                        BpmDetailField("Instrucciones", instructions),
                    ))
                }
            }
            is OperationsEntry.ExecutionEntry -> with(entry.value) {
                BpmSection("Ejecución") {
                    BpmDetailFields(listOf(
                        BpmDetailField("Responsable", responsibleName.ifEmpty { "Responsable registrado" }), BpmDetailField("Turno", shiftName),
                        BpmDetailField("Ejecutado", displayDateTime(input.executedAt)), BpmDetailField("Confirmado", displayDateTime(createdAt)),
                        BpmDetailField("Observación", input.observation),
                    ))
                }
            }
        }
    }
}

internal fun OperationsEntry.title(): String =
    when (this) {
        is OperationsEntry.SupplierEntry -> value.name
        is OperationsEntry.ProductEntry -> value.name
        is OperationsEntry.ReceiptEntry -> value.supplierName
        is OperationsEntry.LotEntry -> value.productName
        is OperationsEntry.PlanEntry -> "${value.area} · ${value.task}"
        is OperationsEntry.ExecutionEntry -> "${value.area} · ${value.task}"
    }

internal fun OperationsEntry.subtitle(): String =
    when (this) {
        is OperationsEntry.SupplierEntry -> value.contactName.ifEmpty { value.taxId }
        is OperationsEntry.ProductEntry ->
            if (value.policyMissing) "Pendiente de configurar criterio térmico"
            else if (value.requiresTemperature)
                "${value.minimumTemperature} a ${value.maximumTemperature} °C · ${value.unit}"
            else "Sin control térmico · ${value.unit}"
        is OperationsEntry.ReceiptEntry ->
            "${displayDateTime(value.input.receivedAt)} · ${value.items.size} productos"
        is OperationsEntry.LotEntry ->
            "Saldo ${value.quantityAvailable}${if(value.receivedQuantity.isEmpty()) "" else " de ${value.receivedQuantity}"} ${value.unit} · ${if(value.expired)"Vencido" else "Vencimiento: ${displayDate(value.expirationDate)}"}"
        is OperationsEntry.PlanEntry ->
            "${frequencyLabel(value.frequency)} · ${if(value.active)"Activo" else "Inactivo"}"
        is OperationsEntry.ExecutionEntry ->
            "${resultLabel(value.input.result)} · ${displayDateTime(value.input.executedAt)}"
    }

internal fun decisionLabel(v: ReceiptDecision) =
    when (v) {
        ReceiptDecision.ACCEPTED -> "Aceptado"
        ReceiptDecision.REJECTED -> "Rechazado"
        ReceiptDecision.OBSERVED -> "Observado"
    }

internal fun resultLabel(v: CleaningResult) =
    when (v) {
        CleaningResult.COMPLETED -> "Completada"
        CleaningResult.NOT_COMPLETED -> "No completada"
        CleaningResult.OBSERVED -> "Observada"
    }

internal fun frequencyLabel(v: CleaningFrequency) =
    when (v) {
        CleaningFrequency.DAILY -> "Diaria"
        CleaningFrequency.WEEKLY -> "Semanal"
        CleaningFrequency.MONTHLY -> "Mensual"
        CleaningFrequency.PER_SHIFT -> "Por turno"
    }

internal fun fieldLabel(key: String) =
    when (key) {
        "dateTime" -> "Fecha y hora"
        "name" -> "Nombre"
        "taxId" -> "Identificación tributaria"
        "contactName" -> "Contacto"
        "contactPhone" -> "Teléfono"
        "contactEmail" -> "Correo"
        "address" -> "Dirección"
        "category" -> "Categoría"
        "unit" -> "Unidad"
        "requiresTemperature" -> "Requiere temperatura"
        "minimumTemperature" -> "Temperatura mínima"
        "maximumTemperature" -> "Temperatura máxima"
        "documentReference" -> "Referencia"
        "notes" -> "Notas"
        "area" -> "Área"
        "task" -> "Tarea"
        "instructions" -> "Instrucciones"
        "productName" -> "Producto"
        "dueTime" -> "Hora"
        "observation" -> "Observación"
        "result" -> "Resultado"
        "frequency" -> "Frecuencia"
        "shiftId" -> "Turno"
        "shiftName" -> "Turno"
        "supplierName" -> "Proveedor"
        "planName" -> "Plan"
        else -> key
    }

internal fun failureText(failure: OperationsFailure) =
    when (failure) {
        OperationsFailure.SESSION -> "Tu sesión terminó. Vuelve a iniciar sesión."
        OperationsFailure.DENIED -> "No tienes permisos para esta acción."
        OperationsFailure.NOT_FOUND -> "El recurso ya no está disponible. Actualiza la información."
        OperationsFailure.INVALID -> "Revisa los campos indicados antes de continuar."
        OperationsFailure.CONFLICT ->
            "El registro cambió. Comprueba el envío o actualiza el criterio antes de continuar."
        OperationsFailure.CONNECTION ->
            "No pudimos conectar. Conservamos el borrador; revisa tu conexión y reintenta."
        OperationsFailure.INVALID_RESPONSE ->
            "No pudimos confirmar la respuesta. Comprueba el mismo registro."
        OperationsFailure.INVALID_PAGE -> "La consulta cambió. Actualiza la lista."
        OperationsFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentar."
        OperationsFailure.LOCAL ->
            "No se pudo proteger el borrador en este dispositivo. Reintenta el guardado."
        OperationsFailure.UNAVAILABLE ->
            "El servicio no está disponible. Reintenta para comprobar el mismo registro."
        OperationsFailure.SHIFT_IN_USE ->
            "Hay controles programados activos en este turno. Desactívalos primero."
        OperationsFailure.SHIFT_OUT_OF_HOURS ->
            "El turno elegido no está abierto ahora. Elige el turno en curso o registra la limpieza dentro de su horario."
        OperationsFailure.INSUFFICIENT_STOCK ->
            "El lote no tiene saldo suficiente. Actualiza el saldo y corrige la cantidad."
        OperationsFailure.LOT_EXPIRED ->
            "El lote está vencido y no se puede consumir. Registra un descarte por vencimiento."
        OperationsFailure.QUEUED -> "Guardado en este dispositivo. Se enviará cuando recuperes la conexión."
    }

internal fun displayDate(value: String?) =
    value?.let { "${it.takeLast(2)}/${it.substring(5,7)}/${it.take(4)}" } ?: "Sin fecha"

private fun lotStatus(value: String) =
    when (value) {
        "AVAILABLE" -> "Disponible"
        "CONSUMED" -> "Consumido"
        "DISCARDED" -> "Descartado"
        else -> "Sin información"
    }

internal fun displayField(key: String, value: String) =
    when (key) {
        "dateTime" -> displayDateTime(value)
        "result" -> resultLabel(CleaningResult.valueOf(value))
        "frequency" -> frequencyLabel(CleaningFrequency.valueOf(value))
        "requiresTemperature" -> if (value == "true") "Sí" else "No"
        else -> value
    }
