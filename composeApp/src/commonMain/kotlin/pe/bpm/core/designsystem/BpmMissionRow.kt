package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip

/**
 * Fila de «misión» del estilo de juego (`.mission`): icono de 48 dp con el color suave de [tone], título y detalle y,
 * a la derecha, un círculo de 44 dp para marcarla como hecha sin abrirla. Borde de 2 dp con 4 dp abajo y radio 18.
 *
 * - [done] la pinta en verde (fondo suave, borde verde y círculo relleno con visto); [late] pone el [detail] en rojo
 *   («Venció a las 10:00»).
 * - [onCheck] marca o desmarca la tarea (el círculo mide 44 dp y su área táctil 48 dp; es una casilla `Role.Checkbox` con su estado marcado/no marcado); [onOpen] se dispara al tocar el
 *   resto de la fila. Son dos objetivos separados para que ninguno tape al otro.
 *
 * La fila mide lo que mide su contenido; nunca se estira en alto.
 */
@Composable
fun BpmMissionRow(
    title: String,
    detail: String,
    icon: BpmSymbol,
    tone: BpmPlayTone,
    done: Boolean,
    late: Boolean,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shade = BpmPlayColors.shade(tone)
    val frame = if (done) BpmPlayColors.doneRowBorder else BpmPlayColors.line
    val fill = if (done) BpmPlayColors.correct.soft else Color.White
    Box(modifier.fillMaxWidth().bpmPlayFrame(frame, fill, 18.dp)) {
        // Capa de apertura: debajo del contenido, para que el círculo conserve su propio objetivo táctil.
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp))
            .semantics { contentDescription = "$title. $detail" }
            .clickable(role = Role.Button, onClick = onOpen))
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(shade.soft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                BpmIcon(icon, tint = shade.ink, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = BpmPlayType.rowTitle, color = if (done) BpmPlayColors.correct.shadow else BpmPlayColors.ink,
                    maxLines = bpmTitleLines(), overflow = TextOverflow.Ellipsis)
                Text(detail, style = BpmPlayType.rowDetail, color = if (late && !done) BpmPlayColors.error.face else BpmPlayColors.muted)
            }
            Box(Modifier.size(BpmSpacing.touch).clip(CircleShape)
                .semantics { contentDescription = "Tarea hecha: $title" }
                .toggleable(value = done, role = Role.Checkbox, onValueChange = { onCheck() }), contentAlignment = Alignment.Center) {
                Box(Modifier.size(44.dp).then(
                    if (done) Modifier.background(BpmPlayColors.correct.face, CircleShape).border(3.dp, BpmPlayColors.correct.face, CircleShape)
                    else Modifier.border(3.dp, BpmPlayColors.line, CircleShape)), contentAlignment = Alignment.Center) {
                    if (done) BpmIcon(BpmSymbol.Check, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
