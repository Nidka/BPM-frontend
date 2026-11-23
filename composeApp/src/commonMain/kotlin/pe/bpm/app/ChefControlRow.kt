package pe.bpm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.checklists.domain.ChecklistAssignment
import pe.bpm.features.operations.presentation.missionIconFor

/**
 * Fila de un control programado (`.mission` con botón de la maqueta del jefe de cocina): icono por el nombre del control,
 * nombre, línea de apoyo ([controlDetail]; en rojo si venció) y el botón «Empezar» / «Continuar» / «Ver». Un control hecho
 * va en verde con una marca y toda la fila lo abre. La fila mide lo que mide su contenido.
 */
@Composable
internal fun ChefControlRow(item: ChecklistAssignment, state: ChefControlState, detail: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val icon = missionIconFor(item.templateName)
    val shade = BpmPlayColors.shade(icon.tone)
    val done = state == ChefControlState.Done
    val frame = if (done) BpmPlayColors.doneRowBorder else BpmPlayColors.line
    val fill = if (done) BpmPlayColors.correct.soft else Color.White
    val base = modifier.fillMaxWidth().bpmPlayFrame(frame, fill, 18.dp)
    Row(
        (if (done) base.clickable(role = Role.Button, onClickLabel = "Ver el control", onClick = onOpen) else base).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(shade.soft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            BpmIcon(icon.symbol, tint = shade.ink, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.templateName, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, maxLines = 2)
            Text(detail, style = BpmPlayType.rowDetail, color = if (state == ChefControlState.Late) BpmPlayColors.error.shadow else BpmPlayColors.muted, maxLines = 2)
        }
        if (done) Box(Modifier.size(40.dp).background(BpmPlayColors.correct.face, CircleShape).semantics { contentDescription = "Hecho" }, contentAlignment = Alignment.Center) {
            BpmIcon(BpmSymbol.Check, tint = Color.White, modifier = Modifier.size(24.dp))
        } else BpmChunkyButton(controlAction(state), onOpen, tone = if (state == ChefControlState.OthersInProgress) BpmChunkyTone.White else BpmChunkyTone.Action)
    }
}
