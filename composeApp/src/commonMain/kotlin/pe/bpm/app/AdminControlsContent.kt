package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.BpmChunkyButton
import pe.bpm.core.designsystem.BpmChunkyTone
import pe.bpm.core.designsystem.BpmSpacing
import pe.bpm.features.checklists.domain.ChecklistAssignment
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsState

/**
 * «Controles» del administrador: los controles del periodo en todas sus sedes (con «Empezar» / «Continuar» / «Ver»), los dos
 * botones de programación («Programar control» elige la plantilla y la sede; «Controles programados» los consulta y detiene)
 * y los accesos de la sección: revisiones del equipo (antes «Seguimiento»), incidencias, insumos y limpieza, controles en el
 * dispositivo, historial y otras plantillas. Reutiliza la lista del jefe de cocina: el servidor decide qué sedes entrega.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminControlsContent(
    userId: String,
    assignments: ChecklistAssignmentsState,
    links: List<ChefControlLink>,
    onSchedule: () -> Unit,
    onScheduled: () -> Unit,
    onRefresh: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenControl: (ChecklistAssignment) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChefControlsContent(userId, assignments, links, onRefresh, onPrevious, onNext, onOpenControl, modifier,
        emptyMessage = "Programa controles con «Programar control»: elige una plantilla publicada y la sede.",
        title = "Controles de hoy · todas las sedes", moreTitle = "Más en Controles", alignStart = true,
        top = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
                BpmChunkyButton("Programar control", onSchedule)
                BpmChunkyButton("Controles programados", onScheduled, tone = BpmChunkyTone.White)
            }
        })
}
