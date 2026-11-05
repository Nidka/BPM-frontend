package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.operations.domain.*

@Composable
internal fun OperationDetails(entry: OperationsEntry) {
    Text(entry.title(), style = MaterialTheme.typography.titleLarge)
    Text(entry.subtitle())
    when (entry) {
        is OperationsEntry.SupplierEntry ->
            with(entry.value) {
                Text(
                    "Identificación: ${taxId.ifEmpty{"Sin registrar"}}\nContacto: ${contactName.ifEmpty{"Sin registrar"}}\nTeléfono: ${contactPhone.ifEmpty{"Sin registrar"}}\nCorreo: ${contactEmail.ifEmpty{"Sin registrar"}}\nDirección: ${address.ifEmpty{"Sin registrar"}}"
                )
            }
        is OperationsEntry.ProductEntry ->
            Text(
                "Categoría: ${entry.value.category.ifEmpty{"Sin categoría"}}\nUnidad: ${entry.value.unit}"
            )
        is OperationsEntry.ReceiptEntry ->
            with(entry.value) {
                Text(
                    "Referencia: ${input.documentReference.ifEmpty{"Sin referencia"}}\nResponsable: ${receivedByName.ifEmpty{"Responsable registrado"}}\nConfirmado: ${displayDateTime(createdAt)}"
                )
                if (input.notes.isNotBlank()) Text(input.notes)
                items.forEach {
                    ReceiptItemSummary(it.input, it.productName)
                    if (it.legacyPackaging)
                        Text("Registro anterior: estado del envase sin información.")
                    if (it.minimumTemperature != null && it.maximumTemperature != null)
                        Text(
                            "Criterio al recibir: ${it.minimumTemperature} a ${it.maximumTemperature} °C"
                        )
                }
            }
        is OperationsEntry.LotEntry ->
            with(entry.value) {
                Text(
                    "Lote: $batchCode\nRecepción: ${displayDateTime(receivedAt)}\nVencimiento: ${displayDate(expirationDate)}\nUbicación: $storageLocation\nEtiqueta: ${label.ifEmpty{"Sin etiqueta"}}\nEstado: ${lotStatus(status)}"
                )
            }
        is OperationsEntry.PlanEntry ->
            with(entry.value) {
                Text(
                    "Instrucciones: ${instructions.ifEmpty{"Sin instrucciones adicionales"}}\nProducto: ${productName.ifEmpty{"Sin producto indicado"}}\nHora: ${dueTime.ifEmpty{"Sin hora"}}\n${if(active)"Activo" else "Inactivo"}"
                )
            }
        is OperationsEntry.ExecutionEntry ->
            with(entry.value) {
                Text(
                    "Responsable: ${responsibleName.ifEmpty{"Responsable registrado"}}\nEjecutado: ${displayDateTime(input.executedAt)}\nConfirmado: ${displayDateTime(createdAt)}\nObservación: ${input.observation.ifEmpty{"Sin observaciones"}}"
                )
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
            "${value.quantityAvailable} ${value.unit} · ${if(value.expired)"Vencido" else "Vencimiento: ${displayDate(value.expirationDate)}"}"
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
