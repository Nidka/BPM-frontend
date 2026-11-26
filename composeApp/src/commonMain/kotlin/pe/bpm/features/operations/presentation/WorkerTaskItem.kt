package pe.bpm.features.operations.presentation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.operations.domain.CleaningExecution
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.domain.OperationsEntry

/**
 * Fila de un plan de limpieza de hoy (regla G6): icono Clock (o Check si está hecho), título = la tarea, meta
 * «Cocina · Diaria» ([todayMeta]) y el chip que decidió el servidor ([todayChip]); el tono de la fila sigue al del
 * chip. Con [framed] = false es plana (dentro de una tarjeta, separada con [BpmRowDivider]). Toda la fila es tocable.
 * Reutilizada por «Tareas de hoy» en Hoy.
 */
@Composable
internal fun TodayPlanRow(plan: CleaningPlan, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, framed: Boolean = true) {
    val chip = todayChip(plan)
    val done = plan.today?.done == true
    BpmTaskRow(
        title = plan.task,
        symbol = if (done) BpmSymbol.Check else BpmSymbol.Clock,
        modifier = modifier,
        detail = todayMeta(plan),
        tone = chip?.tone?.takeIf { it != BpmTone.Neutral } ?: BpmTone.Info,
        trailing = chip?.let { c -> { BpmStatusChip(c.text, c.tone) } },
        onClick = onClick,
        framed = framed,
    )
}

/**
 * Fila de «Mis tareas» del personal: un plan de limpieza (toca para registrarlo; sin enlace «Ver detalle») o una
 * limpieza ya registrada (toca para ver el detalle). El chip del plan viene del servidor ([todayChip]); no depende de
 * la hora del dispositivo.
 */
@Composable
internal fun WorkerTaskItem(
    entry: OperationsEntry,
    viewModel: OperationsViewModel,
    onCleaningPhotos: ((CleaningExecution) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    when (entry) {
        is OperationsEntry.PlanEntry -> {
            val plan = entry.value
            TodayPlanRow(plan, modifier, onClick = { if (plan.active && plan.today?.done != true) viewModel.newCleaningFor(plan) else viewModel.detail(entry) })
        }
        is OperationsEntry.ExecutionEntry -> {
            val tone = resultTone(entry.value.input.result)
            BpmTaskRow(
                title = entry.value.task,
                symbol = BpmSymbol.Check,
                modifier = modifier,
                detail = "${entry.value.area} · ${displayDateTime(entry.value.input.executedAt)}",
                tone = tone,
                trailing = { BpmStatusChip(resultLabel(entry.value.input.result), tone) },
                onClick = { viewModel.detail(entry) },
                below = onCleaningPhotos?.let { photos -> { BpmTextButton({ photos(entry.value) }) { Text("Fotos de la limpieza") } } },
            )
        }
        else -> Unit
    }
}
