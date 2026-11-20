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
import pe.bpm.features.operations.domain.OperationsEntry

/**
 * Fila de «Mis tareas» del personal: un plan de limpieza (toca para registrarlo) o una limpieza ya registrada
 * (toca para ver el detalle). No hay hora local disponible en `commonMain` sin dependencias nuevas, por eso el
 * chip de vencimiento se muestra Neutral (ver [dueTone]).
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
                val due = dueChipText(plan.dueTime)
                BpmTaskRow(
                    title = entry.title(),
                    symbol = BpmSymbol.Clock,
                    detail = entry.subtitle(),
                    tone = BpmTone.Info,
                    trailing = due?.let { text -> { BpmStatusChip(text, dueTone(plan.dueTime, localMinutes = null)) } },
                    onClick = { if (plan.active) viewModel.newCleaningFor(plan) else viewModel.detail(entry) },
                )
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
