package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.operations.domain.CleaningExecution
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.domain.OperationsEntry

/**
 * Fila de un plan de limpieza de hoy: icono Clock (o Check si está hecho), título, frecuencia/turno/quién lo hizo y el
 * chip que decidió el servidor ([todayChip]); el tono de la fila sigue al del chip. Reutilizada por «Tareas de hoy» en Hoy.
 */
@Composable
internal fun TodayPlanRow(plan: CleaningPlan, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val chip = todayChip(plan)
    val done = plan.today?.done == true
    BpmTaskRow(
        title = "${plan.area} · ${plan.task}",
        symbol = if (done) BpmSymbol.Check else BpmSymbol.Clock,
        modifier = modifier,
        detail = todayDetail(plan),
        tone = chip?.tone?.takeIf { it != BpmTone.Neutral } ?: BpmTone.Info,
        trailing = chip?.let { c -> { BpmStatusChip(c.text, c.tone) } },
        onClick = onClick,
    )
}

/**
 * Fila de «Mis tareas» del personal: un plan de limpieza (toca para registrarlo) o una limpieza ya registrada
 * (toca para ver el detalle). El chip del plan viene del servidor ([todayChip]); no depende de la hora del dispositivo.
 */
@Composable
internal fun WorkerTaskItem(
    entry: OperationsEntry,
    viewModel: OperationsViewModel,
    onCleaningPhotos: ((CleaningExecution) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
        when (entry) {
            is OperationsEntry.PlanEntry -> {
                val plan = entry.value
                TodayPlanRow(plan, onClick = { if (plan.active) viewModel.newCleaningFor(plan) else viewModel.detail(entry) })
                BpmTextButton({ viewModel.detail(entry) }) { Text("Ver detalle") }
            }
            is OperationsEntry.ExecutionEntry -> {
                val tone = resultTone(entry.value.input.result)
                BpmTaskRow(
                    title = entry.title(),
                    symbol = BpmSymbol.Check,
                    detail = displayDateTime(entry.value.input.executedAt),
                    tone = tone,
                    trailing = { BpmStatusChip(resultLabel(entry.value.input.result), tone) },
                    onClick = { viewModel.detail(entry) },
                )
                if (onCleaningPhotos != null)
                    Row {
                        BpmTextButton({ onCleaningPhotos(entry.value) }) {
                            Text("Fotos de la limpieza")
                        }
                    }
            }
            else -> Unit
        }
    }
}
