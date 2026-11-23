package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.operations.domain.*

/** Historial de consumos y descartes, más el formulario para registrar uno sobre un lote. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StockMovementsScreen(viewModel: StockMovementsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Consumos y descartes", style = MaterialTheme.typography.headlineSmall)
        state.failure?.let { Text(stockFailureText(it), color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
        pe.bpm.core.offline.PendingOperationsNotice("stock-movements")
        if (state.loading) BpmSkeletonList(rows = 3, card = false)
        val form = state.form
        if (state.siteId == null) {
            Text("Selecciona una sede para consultar los movimientos.")
        } else if (form != null) {
            StockFormContent(state, form, viewModel)
        } else {
            state.saved?.let { saved ->
                BpmCard(Modifier.fillMaxWidth()) {
                    Text("Movimiento registrado", style = MaterialTheme.typography.titleMedium)
                    Text("${saved.productName}: saldo del lote ${saved.balanceAfter} ${saved.unit}.")
                    BpmTextButton(viewModel::consumeSaved) { Text("Entendido") }
                }
            }
            if (!state.canRecord)
                Text("Los movimientos se registran desde el lote, en Stock PEPS, por el responsable de cocina de la sede.", style = MaterialTheme.typography.bodySmall)
            else
                Text("Para registrar un consumo o descarte, abre Stock PEPS y elige el lote.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmFilterChip(state.filter.type == null, { viewModel.filterType(null) }, label = { Text("Todos") }, enabled = !state.loading)
                BpmFilterChip(state.filter.type == StockMovementType.CONSUMPTION, { viewModel.filterType(StockMovementType.CONSUMPTION) }, label = { Text("Consumos") }, enabled = !state.loading)
                BpmFilterChip(state.filter.type == StockMovementType.DISCARD, { viewModel.filterType(StockMovementType.DISCARD) }, label = { Text("Descartes") }, enabled = !state.loading)
            }
            if (state.filter.lotId != null) {
                Text("Lote: ${state.lotLabel.ifEmpty { "seleccionado" }}")
                BpmTextButton(viewModel::clearLot, enabled = !state.loading) { Text("Ver todos los lotes") }
            }
            if (state.failure != null) BpmTextButton(viewModel::refresh, enabled = !state.loading) { Text("Reintentar consulta") }
            if (state.movements.isEmpty() && !state.loading && state.failure == null) EmptyState("Sin movimientos", "Todavía no hay movimientos para esta consulta. Cambia el filtro de tipo para ver otros.")
            BpmGridLayout(state.movements) { movement, cardModifier ->
                BpmCard(cardModifier) {
                    Text("${typeLabel(movement.input)} · ${movement.productName}", style = MaterialTheme.typography.titleMedium)
                    Text("Cantidad: ${movement.input.quantity} ${movement.unit} · Saldo del lote: ${movement.balanceAfter} ${movement.unit}")
                    Text("Lote ${movement.batchCode} · ${displayDateTime(movement.createdAt)}")
                    Text("Responsable: ${movement.performedByName.ifEmpty { "Responsable registrado" }}", style = MaterialTheme.typography.bodySmall)
                    if (movement.input.note.isNotBlank()) Text(movement.input.note, style = MaterialTheme.typography.bodySmall)
                }
            }
            PageNavigation(state.page, state.previous.isNotEmpty(), state.nextToken.isNotEmpty(), state.loading, viewModel::previous, viewModel::next)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockFormContent(state: StockState, form: StockForm, vm: StockMovementsViewModel) {
    val enabled = !state.busy && !form.uncertain
    val lot = form.lot
    BpmFormLayout(actions = { BpmFormActions(if (form.uncertain) "Comprobar el mismo movimiento" else "Registrar", vm::submit, busy = state.busy, busyLabel = "Registrando…", onCancel = vm::closeForm) }) {
        Text("Registrar movimiento", style = MaterialTheme.typography.titleLarge)
        BpmSection(lot.productName) {
            Text("Lote ${lot.batchCode} · Recibido: ${lot.receivedQuantity.ifEmpty { "sin dato" }} ${lot.unit}")
            Text("Saldo disponible: ${lot.quantityAvailable} ${lot.unit}" + if (lot.expired) " · Vencido" else "")
        }
        BpmSection("Movimiento") {
            if (form.uncertain)
                Text("No pudimos confirmar el registro. Reintenta para comprobar el mismo movimiento; no se descontará dos veces.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmFilterChip(form.type == StockMovementType.CONSUMPTION, { vm.change(type = StockMovementType.CONSUMPTION) }, label = { Text("Consumo") }, enabled = enabled)
                BpmFilterChip(form.type == StockMovementType.DISCARD, { vm.change(type = StockMovementType.DISCARD) }, label = { Text("Descarte") }, enabled = enabled)
            }
            state.fields["type"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BpmTextField(form.quantity, { vm.change(quantity = it) }, "Cantidad (${lot.unit})", error = state.fields["quantity"], enabled = enabled, inputKind = BpmInputKind.Decimal, placeholder = "Ej.: 0,5")
            if (form.type == StockMovementType.DISCARD) {
                Text("Motivo del descarte")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiscardReason.entries.forEach { reason ->
                        BpmFilterChip(form.reason == reason, { vm.change(reason = reason) }, label = { Text(reasonLabel(reason)) }, enabled = enabled)
                    }
                }
                state.fields["reason"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                BpmTextField(
                    form.note,
                    { vm.change(note = it) },
                    if (form.reason == DiscardReason.OTHER) "Explica el motivo" else "Nota (opcional)",
                    error = state.fields["note"],
                    enabled = enabled,
                    singleLine = false,
                )
            }
            state.fields["lotId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.failure == OperationsFailure.LOT_EXPIRED)
                BpmChunkyButton("Registrar descarte por vencido", vm::suggestExpiredDiscard, tone = BpmChunkyTone.White, enabled = !state.busy)
        }
    }
}

internal fun reasonLabel(reason: DiscardReason) =
    when (reason) {
        DiscardReason.EXPIRED -> "Vencido"
        DiscardReason.DETERIORATED -> "Deteriorado"
        DiscardReason.CONTAMINATION -> "Contaminación"
        DiscardReason.OTHER -> "Otro"
    }

private fun typeLabel(input: StockMovementInput) =
    if (input.type == StockMovementType.CONSUMPTION) "Consumo"
    else "Descarte (${reasonLabel(input.reason ?: DiscardReason.OTHER).lowercase()})"

/** Mensajes propios del stock; el resto reutiliza los de operación diaria. */
internal fun stockFailureText(failure: OperationsFailure) =
    when (failure) {
        OperationsFailure.INSUFFICIENT_STOCK ->
            "El saldo del lote cambió y ya no alcanza. Actualiza Stock PEPS y registra una cantidad menor."
        OperationsFailure.LOT_EXPIRED ->
            "El lote está vencido y no se puede consumir. Registra un descarte por vencimiento."
        else -> failureText(failure)
    }
