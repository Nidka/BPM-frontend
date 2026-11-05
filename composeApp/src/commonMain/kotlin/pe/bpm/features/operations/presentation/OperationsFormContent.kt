package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.*
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.operations.domain.*

private data class OperationField(
    val key: String,
    val label: String,
    val max: Int,
    val min: Int = 0,
    val kind: BpmInputKind = BpmInputKind.Text,
    val placeholder: String? = null,
)

private fun fields(state: OperationsState): List<OperationField> =
    when (state.resource) {
        OperationsResource.SUPPLIERS ->
            listOf(
                OperationField("name", "Nombre del proveedor", 200, 2),
                OperationField("taxId", "Identificación tributaria (opcional)", 20),
                OperationField(
                    "contactName",
                    "Nombre del contacto (opcional)",
                    160,
                    kind = BpmInputKind.PersonName,
                ),
                OperationField(
                    "contactPhone",
                    "Teléfono (opcional)",
                    30,
                    kind = BpmInputKind.Phone,
                ),
                OperationField("contactEmail", "Correo (opcional)", 320, kind = BpmInputKind.Email),
                OperationField("address", "Dirección (opcional)", 300),
            )
        OperationsResource.PRODUCTS ->
            (if (state.form?.policyProductId == null)
                listOf(
                    OperationField("name", "Nombre del producto", 200, 2),
                    OperationField("category", "Categoría (opcional)", 100),
                    OperationField(
                        "unit",
                        "Unidad de medida",
                        24,
                        1,
                        placeholder = "Nombre de la unidad",
                    ),
                )
            else emptyList()) +
                if (state.form?.values?.get("requiresTemperature") == "true")
                    listOf(
                        OperationField(
                            "minimumTemperature",
                            "Temperatura mínima (°C)",
                            16,
                            1,
                            BpmInputKind.Decimal,
                        ),
                        OperationField(
                            "maximumTemperature",
                            "Temperatura máxima (°C)",
                            16,
                            1,
                            BpmInputKind.Decimal,
                        ),
                    )
                else emptyList()
        OperationsResource.RECEIPTS ->
            listOf(
                OperationField("documentReference", "Referencia del documento (opcional)", 100),
                OperationField("notes", "Notas (opcional)", 2000),
            )
        OperationsResource.PLANS ->
            listOf(
                OperationField("area", "Área", 100, 2),
                OperationField("task", "Tarea", 240, 2),
                OperationField("instructions", "Instrucciones (opcional)", 2000),
                OperationField("productName", "Producto de limpieza (opcional)", 160),
                OperationField(
                    "dueTime",
                    "Hora de referencia en la sede (opcional)",
                    5,
                    placeholder = "HH:mm",
                ),
            )
        OperationsResource.EXECUTIONS ->
            listOf(
                OperationField(
                    "observation",
                    "Observación",
                    2000,
                    if (state.form?.values?.get("result") in setOf("OBSERVED", "NOT_COMPLETED")) 1
                    else 0,
                )
            )
        OperationsResource.LOTS -> emptyList()
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OperationForm(state: OperationsState, vm: OperationsViewModel) {
    val form = state.form!!
    var discarding by remember(form.id) { mutableStateOf(false) }
    val specs = fields(state)
    val navigation = rememberBpmFormNavigation(specs.map { it.key })
    LaunchedEffect(state.fields) {
        if (state.fields.isNotEmpty()) navigation.focusFirstError(state.fields.keys)
    }
    Text(
        if (form.policyProductId != null) "Criterio térmico del producto" else state.resource.title,
        style = MaterialTheme.typography.titleLarge,
    )
    if (state.pending != null)
        Text(
            "Hay un envío sin confirmar. El contenido está protegido: vuelve a comprobar el mismo registro antes de comenzar otro."
        )
    Text(
        when (state.localStatus) {
            DraftSaveStatus.SAVING -> "Guardando borrador en este dispositivo…"
            DraftSaveStatus.SAVED -> "Borrador protegido en este dispositivo."
            DraftSaveStatus.FAILED -> "No se pudo proteger el borrador. Reintenta antes de enviar."
            DraftSaveStatus.IDLE -> "Los cambios se protegen en este dispositivo."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.resource in setOf(OperationsResource.RECEIPTS, OperationsResource.EXECUTIONS))
        Text(
            "Fecha y hora: ${displayDateTime(form.values["dateTime"]?:form.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
        )
    if (
        state.fields.keys.any {
            it in setOf("dateTime", "receivedAt", "executedAt", "clientCreatedAt")
        }
    )
        Text(
            "No se pudo validar la fecha del registro. Comprueba la fecha y hora del dispositivo.",
            color = MaterialTheme.colorScheme.error,
        )
    if (state.review) {
        Text("Revisar antes de confirmar", style = MaterialTheme.typography.titleMedium)
        if (state.resource == OperationsResource.RECEIPTS) {
            Text("Proveedor: ${form.values["supplierName"].orEmpty()}")
            form.items.forEachIndexed { i, item ->
                ReceiptItemSummary(
                    item,
                    state.products.find { it.id == item.productId }?.name ?: "Producto ${i+1}",
                )
            }
        } else if (state.resource == OperationsResource.EXECUTIONS)
            Text("Plan: ${form.values["planName"].orEmpty()}")
        form.values
            .filterKeys { it !in setOf("supplierId", "cleaningPlanId") }
            .forEach { (key, value) ->
                if (value.isNotBlank()) Text("${fieldLabel(key)}: ${displayField(key,value)}")
            }
        RaisedButton(
            if (state.busy) "Confirmando…"
            else if (state.pending != null) "Comprobar el mismo registro" else "Confirmar registro",
            vm::save,
            Modifier.fillMaxWidth(),
            enabled =
                !state.busy &&
                    state.failure !in setOf(OperationsFailure.SESSION, OperationsFailure.DENIED),
        )
        if (state.pending == null)
            BpmSecondaryButton(vm::editReview, enabled = !state.busy) { Text("Volver a editar") }
    } else {
        if (state.resource == OperationsResource.PRODUCTS) {
            Text("¿El producto requiere control de temperatura?")
            Choices(
                listOf("false" to "No requiere", "true" to "Sí requiere"),
                form.values["requiresTemperature"] ?: "false",
                state.editable,
            ) {
                vm.field("requiresTemperature", it)
                if (it == "false") {
                    vm.field("minimumTemperature", "")
                    vm.field("maximumTemperature", "")
                }
            }
            if (form.policyProductId != null)
                Text(
                    "Si otro administrador cambia el criterio mientras editas, tendrás que consultar el producto de nuevo.",
                    style = MaterialTheme.typography.bodySmall,
                )
        }
        if (state.resource == OperationsResource.RECEIPTS) {
            Text("Proveedor: ${form.values["supplierName"]?:"Sin seleccionar"}")
            BpmSecondaryButton(
                { vm.pick(OperationsResource.SUPPLIERS) },
                enabled = state.editable,
            ) {
                Text("Buscar proveedor")
            }
            state.fields["supplierId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (state.resource == OperationsResource.EXECUTIONS) {
            Text("Plan: ${form.values["planName"]?:"Sin seleccionar"}")
            state.selectedPlan?.let {
                Text(
                    "${it.instructions}\nFrecuencia: ${frequencyLabel(it.frequency)} · Hora: ${it.dueTime.ifEmpty{"Sin hora"}}"
                )
            }
            BpmSecondaryButton({ vm.pick(OperationsResource.PLANS) }, enabled = state.editable) {
                Text("Buscar plan de limpieza")
            }
            state.fields["cleaningPlanId"]?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Text("Resultado")
            Choices(
                CleaningResult.entries.map { it.name to resultLabel(it) },
                form.values["result"] ?: "COMPLETED",
                state.editable,
            ) {
                vm.field("result", it)
            }
        }
        if (state.resource == OperationsResource.PLANS) {
            Text("Frecuencia")
            Choices(
                CleaningFrequency.entries.map { it.name to frequencyLabel(it) },
                form.values["frequency"] ?: "DAILY",
                state.editable,
            ) {
                vm.field("frequency", it)
            }
        }
        specs.forEach { spec ->
            val value = form.values[spec.key].orEmpty()
            val rule =
                when (spec.key) {
                    "contactName" -> InputRules.personName(160, false)
                    "contactPhone" -> InputRules.phone()
                    "contactEmail" -> InputRules.email(false)
                    "minimumTemperature",
                    "maximumTemperature" -> OperationsRules.temperature
                    "dueTime" -> OperationsRules.time
                    else -> InputRules.text(spec.min, spec.max)
                }
            BpmTextField(
                value,
                { vm.field(spec.key, it) },
                spec.label,
                error = state.fields[spec.key],
                enabled = state.editable,
                inputKind = spec.kind,
                placeholder = spec.placeholder,
                navigation = navigation[spec.key],
                rule = rule,
            )
        }
        if (state.resource == OperationsResource.RECEIPTS) {
            Text(
                "Productos de la recepción (${form.items.size}/50)",
                style = MaterialTheme.typography.titleMedium,
            )
            form.items.forEachIndexed { i, item -> ReceiptItemEditor(i, item, state, vm) }
            state.fields["items"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BpmSecondaryButton(
                { vm.pick(OperationsResource.PRODUCTS) },
                enabled = state.editable && form.items.size < 50,
            ) {
                Text("Buscar y agregar producto")
            }
        }
        RaisedButton(
            "Revisar registro",
            vm::review,
            Modifier.fillMaxWidth(),
            enabled = state.editable,
        )
    }
    BpmSecondaryButton(vm::closeForm, enabled = !state.busy) { Text("Guardar y salir") }
    if (state.pending == null) {
        if (discarding) {
            Text("¿Descartar los datos de este borrador?")
            BpmSecondaryButton(vm::discard, enabled = !state.busy) { Text("Confirmar descarte") }
            BpmTextButton({ discarding = false }) { Text("Seguir editando") }
        } else
            BpmTextButton({ discarding = true }, enabled = !state.busy) {
                Text("Descartar borrador")
            }
    }
}

@Composable
private fun ReceiptItemEditor(
    index: Int,
    item: ReceiptItemInput,
    state: OperationsState,
    vm: OperationsViewModel,
) {
    val product = state.products.find { it.id == item.productId }
    val prefix = "items.$index."
    val nav =
        rememberBpmFormNavigation(
            listOf(
                "batchCode",
                "expirationDate",
                "quantity",
                "temperatureCelsius",
                "rejectionReason",
                "storageLocation",
                "label",
            )
        )
    fun error(key: String) = state.fields[prefix + key]
    BpmCard(Modifier.fillMaxWidth()) {
        Column(Modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "${index+1}. ${product?.name?:"Producto"} · ${product?.unit.orEmpty()}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (product?.requiresTemperature == true)
                Text(
                    if (product.policyMissing)
                        "Criterio térmico pendiente de configurar. Solo se podrá registrar observado o rechazado con temperatura."
                    else
                        "Criterio: ${product.minimumTemperature} a ${product.maximumTemperature} °C",
                    style = MaterialTheme.typography.bodySmall,
                )
            BpmTextField(
                item.batchCode,
                { value -> vm.item(index) { it.copy(batchCode = value) } },
                "Código de lote",
                enabled = state.editable,
                error = error("batchCode"),
                rule = InputRules.text(1, 100),
                navigation = nav["batchCode"],
            )
            BpmTextField(
                item.expirationDate.orEmpty(),
                { value ->
                    vm.item(index) { it.copy(expirationDate = value.takeIf(String::isNotBlank)) }
                },
                "Vencimiento (opcional, yyyy-MM-dd)",
                placeholder = "yyyy-MM-dd",
                enabled = state.editable,
                error = error("expirationDate"),
                rule =
                    InputRule {
                        if (it.isEmpty() || OperationsRules.date(it)) null
                        else "Usa una fecha válida yyyy-MM-dd."
                    },
                navigation = nav["expirationDate"],
            )
            BpmTextField(
                item.quantity,
                { value -> vm.item(index) { it.copy(quantity = value) } },
                "Cantidad (${product?.unit.orEmpty()})",
                enabled = state.editable,
                error = error("quantity"),
                inputKind = BpmInputKind.Decimal,
                rule = QuantityInput,
                navigation = nav["quantity"],
            )
            BpmTextField(
                item.temperatureCelsius.orEmpty(),
                { value ->
                    vm.item(index) {
                        it.copy(temperatureCelsius = value.takeIf(String::isNotBlank))
                    }
                },
                "Temperatura (°C${if(product?.requiresTemperature==true)"" else ", opcional"})",
                enabled = state.editable,
                error = error("temperatureCelsius"),
                inputKind = BpmInputKind.Decimal,
                rule =
                    InputRule {
                        if (it.isEmpty() && product?.requiresTemperature != true) null
                        else OperationsRules.temperature.error(it)
                    },
                navigation = nav["temperatureCelsius"],
            )
            Text("Estado del envase")
            Choices(
                PackagingCondition.entries.map {
                    it.name to if (it == PackagingCondition.INTACT) "Íntegro" else "Dañado"
                },
                item.packagingCondition?.name.orEmpty(),
                state.editable,
            ) { value ->
                vm.item(index) { it.copy(packagingCondition = PackagingCondition.valueOf(value)) }
            }
            error("packagingCondition")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Decisión")
            Choices(
                ReceiptDecision.entries.map { it.name to decisionLabel(it) },
                item.decision.name,
                state.editable,
            ) { value ->
                vm.item(index) { it.copy(decision = ReceiptDecision.valueOf(value)) }
            }
            error("decision")?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BpmTextField(
                item.rejectionReason,
                { value -> vm.item(index) { it.copy(rejectionReason = value) } },
                "Motivo / explicación${if(item.decision==ReceiptDecision.ACCEPTED)" (opcional)" else ""}",
                enabled = state.editable,
                error = error("rejectionReason"),
                rule =
                    InputRules.text(if (item.decision == ReceiptDecision.ACCEPTED) 0 else 1, 2000),
                navigation = nav["rejectionReason"],
            )
            BpmTextField(
                item.storageLocation,
                { value -> vm.item(index) { it.copy(storageLocation = value) } },
                "Ubicación de almacenamiento (solo aceptados)",
                enabled = state.editable,
                error = error("storageLocation"),
                rule =
                    InputRules.text(if (item.decision == ReceiptDecision.ACCEPTED) 1 else 0, 120),
                navigation = nav["storageLocation"],
            )
            BpmTextField(
                item.label,
                { value -> vm.item(index) { it.copy(label = value) } },
                "Etiqueta (solo aceptados, opcional)",
                enabled = state.editable,
                error = error("label"),
                rule = InputRules.text(0, 160),
                navigation = nav["label"],
            )
            BpmTextButton({ vm.removeItem(index) }, enabled = state.editable) {
                Text("Quitar este producto")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(
    choices: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        choices.forEach { (value, label) ->
            FilterChip(
                selected == value,
                { onSelect(value) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

@Composable
internal fun ReceiptItemSummary(item: ReceiptItemInput, name: String) {
    BpmCard(Modifier.fillMaxWidth()) {
        Column(Modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text("Lote: ${item.batchCode} · Cantidad: ${item.quantity}")
            Text(
                "Vencimiento: ${displayDate(item.expirationDate)} · Temperatura: ${item.temperatureCelsius?:"No registrada"}"
            )
            Text(
                "${decisionLabel(item.decision)} · Envase: ${when(item.packagingCondition){PackagingCondition.INTACT->"Íntegro"
PackagingCondition.DAMAGED->"Dañado"
null->"Sin información registrada"}}"
            )
            if (item.rejectionReason.isNotBlank()) Text(item.rejectionReason)
            if (item.storageLocation.isNotBlank()) Text("Ubicación: ${item.storageLocation}")
            if (item.label.isNotBlank()) Text("Etiqueta: ${item.label}")
        }
    }
}
