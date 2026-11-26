package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.time.Clock
import pe.bpm.core.designsystem.*
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done
import pe.bpm.features.operations.domain.CleaningExecution
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.domain.OperationsEntry
import pe.bpm.features.operations.domain.OperationsResource

/**
 * Cuerpo de la pantalla Tareas del personal (la cabecera con el título y la sede la pone quien la aloja): la tarjeta del
 * turno con su barra y las tareas en tres grupos, **Ahora**, **Más tarde** y **Hechas** ([taskGroups]), como filas de
 * misión con el círculo para registrar al instante (abre el registro rápido con ese plan). Al pie, «Ver historial» lleva a
 * las limpiezas ya registradas (con sus fotos), que es la lista de siempre.
 */
@Composable
internal fun WorkerTasksBody(state: OperationsState, viewModel: OperationsViewModel, onCleaningPhotos: ((CleaningExecution) -> Unit)?) {
    var history by rememberSaveable { mutableStateOf(false) }
    LoadToday(state, viewModel)
    LaunchedEffect(history, state.resource) { if (history && state.resource != OperationsResource.EXECUTIONS) viewModel.resource(OperationsResource.EXECUTIONS) }
    state.failure?.let { Text(failureText(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow) }
    state.notice?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.correct.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    pe.bpm.core.offline.PendingOperationsNotice("supply-receipts", "cleaning-executions")
    if (state.siteId == null) {
        Text("Elige una sede para ver tus tareas.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        return
    }
    if (history) {
        BpmChunkyButton("Volver a las tareas de hoy", { history = false }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) { WorkerDoneList(state, viewModel, onCleaningPhotos) }
        return
    }
    if (state.todayLoading && !state.todayLoaded) BpmSkeletonRecordCards(3)
    state.todayFailure?.let { failure ->
        Text(failureText(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow)
        BpmChunkyButton("Reintentar", viewModel::refreshToday, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !state.todayLoading)
    }
    val plans = state.todayPlans
    if (state.todayLoaded && state.todayFailure == null && plans.isEmpty()) {
        BpmPlayEmpty(Res.drawable.mascot_done, "¡Todo al día!", "Hoy esta sede no tiene tareas de limpieza. Cuando haya planes activos, aparecerán aquí.")
    }
    if (plans.isNotEmpty()) {
        val progress = shiftProgress(plans)
        BpmPlayCard {
            Box(Modifier.fillMaxWidth()) {
                Text(shiftTitle(plans), style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.align(Alignment.CenterStart))
                BpmPlayChip(progress.label, BpmPlayTone.Correct, Modifier.align(Alignment.CenterEnd))
            }
            BpmChunkyProgress(progress.progress, Modifier.fillMaxWidth(), contentDescription = "Avance del turno: ${progress.label}")
        }
        val now = remember(plans) { Clock.System.now().toEpochMilliseconds() }
        val groups = taskGroups(plans, now)
        TaskGroup("Ahora", groups.now, viewModel)
        TaskGroup("Más tarde", groups.later, viewModel)
        TaskGroup("Hechas", groups.done, viewModel)
        if (state.todayLoaded && groups.now.isEmpty() && groups.later.isEmpty()) Text("¡Terminaste las tareas del turno!", style = BpmPlayType.sub, color = BpmPlayColors.correct.shadow)
    }
    BpmChunkyButton("Ver historial", { history = true }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
}

/** Un grupo de tareas con su título en mayúsculas; sin tareas no ocupa espacio. */
@Composable
private fun TaskGroup(title: String, plans: List<CleaningPlan>, viewModel: OperationsViewModel) {
    if (plans.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = BpmPlayType.label, color = BpmPlayColors.muted, modifier = Modifier.semantics { heading() })
        plans.forEach { plan -> PlanMission(plan, viewModel) }
    }
}

/**
 * Una tarea como misión: el círculo registra la limpieza (si aún no está hecha) y tocar la fila abre el mismo registro
 * rápido. Un plan inactivo abre su detalle, como siempre.
 */
@Composable
internal fun PlanMission(plan: CleaningPlan, viewModel: OperationsViewModel, modifier: Modifier = Modifier) {
    val icon = missionIcon(plan)
    val done = plan.today?.done == true
    val open = { if (plan.active) viewModel.newCleaningFor(plan) else viewModel.detail(OperationsEntry.PlanEntry(plan)) }
    BpmMissionRow(plan.task, missionDetail(plan), icon.symbol, icon.tone, done, isLate(plan), onCheck = { if (!done) open() }, onOpen = open, modifier = modifier)
}

/**
 * Botón flotante coral «Reportar» (`.btn.c` de la maqueta): abajo a la derecha de la columna de Tareas, siempre visible.
 * Se alinea con el ancho de la columna central (640 dp, 600 dp desde la clase expandida) y deja 16 dp al borde.
 */
@Composable
internal fun WorkerReportButton(onReport: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val column = if (currentWindowClass == BpmWindowClass.Expanded || currentWindowClass == BpmWindowClass.Large) 600.dp else BpmPatternRules.mediumColumn
    Box(modifier.widthIn(max = column).fillMaxWidth().padding(bottom = 16.dp), contentAlignment = Alignment.BottomEnd) {
        BpmChunkyButton("Reportar", onReport, tone = BpmChunkyTone.Report, icon = BpmSymbol.Alert, enabled = enabled)
    }
}
