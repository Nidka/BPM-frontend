package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.checklists.domain.*

@Composable
fun ChecklistAssignmentsScreen(
    state: ChecklistAssignmentsState,
    management: Boolean,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: (ChecklistAssignment) -> Unit,
    onDeactivate: (String?) -> Unit,
    onConfirmDeactivate: () -> Unit,
    title: String = "Controles de tus sedes",
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!management)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        Text(
            if (management)
                "Asigna una plantilla publicada desde Gestionar plantillas. Aquí puedes consultar y detener su programación."
            else
                "Consulta lo que corresponde a este período. Si ya empezaste una revisión, continúa con ese mismo registro."
        )
        BpmSearchField(state.query, onSearch, "Buscar por control o sede")
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.failure != null -> {
                Text(
                    assignmentFailureMessage(state.failure),
                    color = MaterialTheme.colorScheme.error,
                )
                BpmSecondaryButton(onClick = onRefresh) { Text("Reintentar") }
            }
            state.items.isEmpty() ->
                EmptyState(
                    if (state.query.isBlank()) "No hay controles programados"
                    else "No encontramos coincidencias",
                    if (state.query.isBlank())
                        if (management)
                            "Abre una plantilla publicada y elige Programar en una sede."
                        else "Tu administrador puede programar los controles de tus sedes."
                    else "Prueba con otro nombre de control o sede.",
                )
            else ->
                BpmGridLayout(state.items) { v, cardModifier ->
                    BpmCard(cardModifier) {
                        Text(
                            v.templateName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(v.locationName)
                        Text(frequencyLabel(v.frequency))
                        AssignmentShiftInfo(v)
                        if (v.dueTime.isNotEmpty())
                            Text(
                                "Hora de referencia en la sede: ${v.dueTime}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        Text(
                            when (v.run?.status) {
                                "DRAFT" -> "En curso · ${v.run.performedByName}"
                                "SUBMITTED" -> "Finalizado en este período"
                                else -> "Pendiente en este período"
                            },
                            style = BpmType.bodyStrong,
                        )
                        RaisedButton(
                            if (management) "Ver programación"
                            else
                                when {
                                    v.run == null -> "Revisar e iniciar"
                                    v.run.status == "DRAFT" && v.run.performedBy == state.userId ->
                                        "Continuar revisión"
                                    else -> "Ver revisión"
                                },
                            { onOpen(v) },
                            Modifier.fillMaxWidth(),
                            enabled = !state.actionBusy,
                        )
                        if (management)
                            BpmTextButton(
                                onClick = { onDeactivate(v.id) },
                                enabled = !state.actionBusy,
                            ) {
                                Text("Dejar de programar")
                            }
                        if (state.confirmDeactivate == v.id) {
                            Text(
                                "Se dejarán de programar revisiones nuevas. Las revisiones ya iniciadas y el historial se conservan."
                            )
                            state.actionFailure?.let {
                                Text(
                                    assignmentFailureMessage(it),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            BpmSecondaryButton(
                                onClick = onConfirmDeactivate,
                                enabled = !state.actionBusy,
                            ) {
                                Text(
                                    if (state.actionBusy) "Confirmando…"
                                    else "Confirmar fin de programación"
                                )
                            }
                            BpmTextButton(
                                onClick = { onDeactivate(null) },
                                enabled = !state.actionBusy,
                            ) {
                                Text("Conservar programación")
                            }
                        }
                    }
                }
        }
        PageNavigation(
            state.previous.size + 1,
            state.previous.isNotEmpty(),
            state.nextToken.isNotEmpty(),
            state.loading || state.failure != null,
            onPrevious,
            onNext,
        )
    }
}

@Composable
fun ChecklistAssignmentDetailScreen(
    value: ChecklistAssignment,
    userId: String,
    management: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onOpenRun: (AssignedChecklistRun) -> Unit,
) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text(
            value.templateName,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(value.locationName, style = BpmType.bodyStrong)
        Text(frequencyLabel(value.frequency))
        Text(frequencyExplanation(value.frequency))
        AssignmentShiftInfo(value)
        if (value.dueTime.isNotEmpty()) Text("Hora de referencia en la sede: ${value.dueTime}")
        val run = value.run
        when {
            !value.active -> Text("Esta programación ya no está activa.")
            run == null -> {
                Text("Pendiente en este período.")
                if (!management)
                    RaisedButton(
                        "Comenzar revisión",
                        onStart,
                        Modifier.fillMaxWidth(),
                        enabled = !busy,
                    )
            }
            else -> {
                Text(
                    if (run.status == "SUBMITTED") "El control de este período ya está finalizado."
                    else "Revisión en curso por ${run.performedByName}."
                )
                RaisedButton(
                    if (!management && run.status == "DRAFT" && run.performedBy == userId)
                        "Continuar revisión"
                    else "Ver revisión",
                    { onOpenRun(run) },
                    Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
        }
    }
}

@Composable
fun ChecklistAssignmentFormScreen(
    state: AssignmentFormState,
    template: ChecklistTemplate?,
    locations: List<ChecklistRunLocation>,
    query: String,
    page: Int,
    loadingLocations: Boolean,
    failedLocations: Boolean,
    previous: Boolean,
    next: Boolean,
    onSearch: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetryLocations: () -> Unit,
    onLocation: (String, String) -> Unit,
    onFrequency: (ChecklistFrequency) -> Unit,
    onTime: (String) -> Unit,
    shifts: List<ChecklistShiftOption> = emptyList(),
    shiftsLoading: Boolean = false,
    shiftsFailed: Boolean = false,
    onShift: (String?) -> Unit = {},
    onSave: () -> Unit,
    onProtect: () -> Unit,
    onRetryOpen: () -> Unit,
    onDiscard: () -> Unit,
) {
    var discarding by remember(state.input?.id) { mutableStateOf(false) }
    val input = state.input
    val navigation = rememberBpmFormNavigation(listOf("dueTime"))
    Column(
        Modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg),
    ) {
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        if (input == null) {
            Text("No pudimos recuperar este formulario.")
            BpmSecondaryButton(onClick = onRetryOpen) { Text("Reintentar") }
            return@Column
        }
        state.failure?.let {
            Text(assignmentFailureMessage(it), color = MaterialTheme.colorScheme.error)
        }
        if (state.failure in setOf(AssignmentFailure.SESSION, AssignmentFailure.DENIED))
            return@Column
        if (template == null) {
            Text("Cargando la plantilla…")
            return@Column
        }
        Text(template.name, style = MaterialTheme.typography.titleLarge)
        Text("El chef de la sede verá este control en su sección Control.")
        DraftSaveNotice(state.localStatus, onProtect)
        if (state.pending)
            Text(
                "Hay una programación pendiente de confirmar. Comprueba su resultado antes de cambiar los datos."
            )
        Text("¿En qué sede se realizará?", style = BpmType.bodyStrong)
        ChecklistLocationSelector(
            locations,
            input.locationId,
            state.locationName,
            query,
            page,
            loadingLocations,
            failedLocations,
            previous,
            next,
            state.editable,
            onSearch,
            onPrevious,
            onNext,
            onRetryLocations,
            { onLocation(it.id, it.name) },
        )
        state.fields["locationId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("¿Cuándo se repetirá?", style = BpmType.bodyStrong)
        Column(Modifier.selectableGroup()) {
            ChecklistFrequency.entries.forEach { frequency ->
                BpmChoiceRow(
                    frequencyLabel(frequency),
                    input.frequency == frequency,
                    state.editable,
                ) {
                    onFrequency(frequency)
                }
                if (input.frequency == frequency)
                    Text(
                        frequencyExplanation(frequency),
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
        state.fields["frequency"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (input.frequency == ChecklistFrequency.DAILY && input.locationId.isNotEmpty()) {
            Text("¿Para un turno específico?", style = BpmType.bodyStrong)
            when {
                shiftsLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                shiftsFailed -> Text("No pudimos cargar los turnos de la sede. Vuelve a elegir la sede para reintentar.", color = MaterialTheme.colorScheme.error)
                else -> Column(Modifier.selectableGroup()) {
                    BpmChoiceRow("Todo el día (sin turno)", input.shiftId == null, state.editable) { onShift(null) }
                    shifts.forEach { shift ->
                        BpmChoiceRow(
                            "${shift.name} (${shift.startTime}-${shift.endTime})",
                            input.shiftId == shift.id,
                            state.editable,
                            supportingText = if (shift.openNow) "Abierto ahora" else null,
                        ) { onShift(shift.id) }
                    }
                    if (shifts.isEmpty()) Text("La sede no tiene turnos activos; la programación será de día completo.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Con un turno, la revisión solo puede iniciarse mientras el turno está abierto, y cada turno tiene su propia revisión del día.", style = MaterialTheme.typography.bodySmall)
            state.fields["shiftId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (input.shiftId == null) BpmTextField(
            input.dueTime,
            onTime,
            "Hora de referencia (opcional)",
            enabled = state.editable,
            error = state.fields["dueTime"],
            rule = AssignmentInputRules.time,
            placeholder = "08:30",
            supportingText = "Formato de 24 horas, en la zona horaria de la sede.",
            navigation = navigation["dueTime"],
        )
        BpmFormActions(
            if (state.pending) "Comprobar programación" else "Confirmar programación",
            onSave,
            enabled = state.saved == null && state.localStatus != DraftSaveStatus.FAILED,
            busy = state.busy,
            busyLabel = "Confirmando…",
        )
        if (!state.pending) {
            if (discarding) DiscardDraftConfirmation(onKeep = { discarding = false }, onDiscard = onDiscard, enabled = !state.busy)
            else
                BpmTextButton(onClick = { discarding = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = BpmSpacing.touch)) {
                    Text("Descartar borrador")
                }
        }
    }
}

/** Turno, estado del turno y versión de plantilla de una programación. */
@Composable
internal fun AssignmentShiftInfo(value: ChecklistAssignment) {
    if (value.templateVersionNumber > 0)
        Text("Plantilla versión ${value.templateVersionNumber}", style = MaterialTheme.typography.bodySmall)
    if (value.shiftId.isNotEmpty()) {
        Text(
            "Turno ${value.shiftName} (${value.shiftStartTime}-${value.shiftEndTime}) · ${shiftStateLabel(value.shiftState)}",
            style = BpmType.bodyStrong,
        )
        if (value.shiftState != ShiftState.OPEN && value.active)
            Text(
                if (value.shiftState == ShiftState.CLOSED) "El turno de hoy ya terminó; la revisión solo se inicia con el turno abierto."
                else "El turno todavía no empieza; la revisión se inicia cuando abra.",
                style = MaterialTheme.typography.bodySmall,
            )
    }
}

internal fun shiftStateLabel(state: ShiftState?) =
    when (state) {
        ShiftState.OPEN -> "Abierto"
        ShiftState.CLOSED -> "Cerrado"
        ShiftState.UPCOMING -> "Próximo"
        null -> ""
    }

internal fun frequencyLabel(value: ChecklistFrequency) =
    when (value) {
        ChecklistFrequency.ONCE -> "Una sola vez"
        ChecklistFrequency.DAILY -> "Cada día"
        ChecklistFrequency.WEEKLY -> "Cada semana"
        ChecklistFrequency.MONTHLY -> "Cada mes"
    }

private fun frequencyExplanation(value: ChecklistFrequency) =
    when (value) {
        ChecklistFrequency.ONCE -> "Una revisión para esta programación."
        ChecklistFrequency.DAILY -> "Una revisión por día de la sede."
        ChecklistFrequency.WEEKLY ->
            "Una revisión por semana, de lunes a domingo, en la zona de la sede."
        ChecklistFrequency.MONTHLY -> "Una revisión por mes calendario de la sede."
    }

internal fun assignmentFailureMessage(reason: AssignmentFailure) =
    when (reason) {
        AssignmentFailure.SESSION -> "Tu sesión terminó. Vuelve a iniciar sesión."
        AssignmentFailure.DENIED ->
            "No tienes permiso para esta programación. Solicita acceso al administrador."
        AssignmentFailure.NOT_FOUND -> "La programación ya no está disponible en esta organización."
        AssignmentFailure.INVALID -> "Revisa los campos señalados."
        AssignmentFailure.CONFLICT ->
            "Ya existe una programación para esa plantilla y sede, o cambió su estado. Consulta los controles programados."
        AssignmentFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentar."
        AssignmentFailure.CONNECTION -> "Revisa tu conexión y vuelve a intentar."
        AssignmentFailure.UNAVAILABLE -> "El servicio no está disponible. Inténtalo de nuevo."
        AssignmentFailure.INVALID_RESPONSE ->
            "No pudimos confirmar la programación. Vuelve a comprobar el resultado."
        AssignmentFailure.INVALID_PAGE -> "La lista cambió. Vuelve a consultar la primera página."
    }
