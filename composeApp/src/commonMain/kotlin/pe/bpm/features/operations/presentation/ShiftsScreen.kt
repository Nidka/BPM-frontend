package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.operations.domain.*

/** Turnos de la sede elegida; las horas son las de la sede, no las del dispositivo. */
@Composable
fun ShiftsScreen(viewModel: ShiftsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Turnos de la sede", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Las horas son las de la sede. Si el fin es anterior al inicio, el turno cruza la medianoche.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.siteId == null -> Text("Selecciona una sede para ver sus turnos.")
            state.editor != null -> ShiftForm(state, viewModel)
            else -> ShiftList(state, viewModel)
        }
    }
}

@Composable
private fun ShiftList(state: ShiftsState, vm: ShiftsViewModel) {
    if (state.canManage) {
        RaisedButton("Crear turno", vm::newShift, Modifier.fillMaxWidth(), enabled = !state.busy && !state.loading)
        BpmFilterChip(
            state.showInactive,
            { vm.toggleInactive() },
            label = { Text("Mostrar turnos inactivos") },
            enabled = !state.loading && !state.busy,
        )
    }
    state.deactivating?.let { shift ->
        BpmCard(Modifier.fillMaxWidth()) {
            Text("¿Desactivar el turno ${shift.name}?", style = MaterialTheme.typography.titleMedium)
            Text("Un turno desactivado no se reactiva; tendrás que crear otro. Si tiene controles programados activos, desactívalos antes.")
            RaisedButton(
                if (state.busy) "Desactivando…" else "Desactivar turno",
                vm::confirmDeactivate,
                Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            BpmTextButton(vm::cancelDeactivate, enabled = !state.busy) { Text("Conservar turno") }
        }
    }
    if (state.shifts.isEmpty() && !state.loading && state.failure == null)
        Text("Esta sede todavía no tiene turnos.")
    BpmGridLayout(state.shifts) { shift, cardModifier ->
        BpmCard(cardModifier) {
            Text(shift.name, style = MaterialTheme.typography.titleMedium)
            Text("${shift.startTime} a ${shift.endTime}" + if (shift.crossesMidnight) " · cruza la medianoche" else "")
            Text(
                when {
                    !shift.active -> "Inactivo"
                    shift.openNow -> "Abierto ahora"
                    else -> "Fuera de horario ahora"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.canManage && shift.active) {
                BpmSecondaryButton({ vm.edit(shift) }, enabled = !state.busy) { Text("Editar") }
                BpmTextButton({ vm.requestDeactivate(shift) }, enabled = !state.busy) { Text("Desactivar") }
            }
        }
    }
}

@Composable
private fun ShiftForm(state: ShiftsState, vm: ShiftsViewModel) {
    val editor = state.editor!!
    val enabled = !state.busy && !editor.uncertain
    Text(if (editor.editing == null) "Nuevo turno" else "Editar turno", style = MaterialTheme.typography.titleLarge)
    if (editor.uncertain)
        Text("No pudimos confirmar el guardado. Reintenta para comprobar el mismo turno.")
    BpmTextField(editor.name, { vm.change(name = it) }, "Nombre del turno", error = state.fields["name"], enabled = enabled, placeholder = "Ej.: Mañana")
    BpmTextField(editor.startTime, { vm.change(start = it) }, "Hora de inicio (HH:mm)", error = state.fields["startTime"], enabled = enabled, placeholder = "06:00")
    BpmTextField(editor.endTime, { vm.change(end = it) }, "Hora de fin (HH:mm)", error = state.fields["endTime"], enabled = enabled, placeholder = "14:00")
    if (ShiftRules.crossesMidnight(editor.startTime, editor.endTime))
        Text("Este turno cruza la medianoche.", style = MaterialTheme.typography.bodySmall)
    RaisedButton(
        if (state.busy) "Guardando…" else if (editor.uncertain) "Comprobar el mismo turno" else "Guardar turno",
        vm::save,
        Modifier.fillMaxWidth(),
        enabled = !state.busy,
    )
    BpmTextButton(vm::cancelEdit, enabled = enabled) { Text("Cancelar") }
}
