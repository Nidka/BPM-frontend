package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fila de persona (`.prow` de la maqueta): iniciales en un círculo de 40 dp, nombre (Nunito 900), [detail] gris (rol y
 * sede) y, a la derecha, el anillo de avance ([progress] de 0 a 1 con su [progressLabel], «62 %») o el chip de estado
 * ([status]); [trailing] añade más a la derecha (una acción). [footer] va debajo del nombre, dentro de la fila.
 * Borde de 2 dp con 4 dp abajo y radio 16; con [selected] (persona abierta junto a la lista) fondo teal suave. Con
 * [onClick] toda la fila es el botón (mínimo 56 dp de alto). Mide lo que mide su contenido; no usa medidas intrínsecas
 * ni `BoxWithConstraints`, así que cabe en cualquier rejilla.
 */
@Composable
fun BpmPersonRow(
    name: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    status: BpmRecordStatus? = null,
    progress: Float? = null,
    progressLabel: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val inner = RoundedCornerShape(14.dp)
    val frame = modifier.fillMaxWidth().bpmPlayFrame(
        if (selected) BpmPlayColors.selectedBorder else BpmPlayColors.line, if (selected) BpmPlayColors.action.soft else Color.White, 16.dp)
        .clip(inner)
    BpmFeedback(BpmPlayColors.action.face) {
        Row(
            (if (onClick != null) frame.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else frame)
                .semantics { this.selected = selected }
                .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(BpmPlayColors.action.soft, CircleShape), contentAlignment = Alignment.Center) {
                Text(initialsOf(name), style = BpmPlayType.key, color = BpmPlayColors.action.face)
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, maxLines = bpmTitleLines(1), overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted, maxLines = bpmTitleLines(), overflow = TextOverflow.Ellipsis)
                footer?.invoke(this)
            }
            if (progress != null) BpmProgressRing(progress, progressLabel ?: "${(progress.coerceIn(0f, 1f) * 100).toInt()} %",
                "Avance de $name: ${progressLabel ?: "${(progress.coerceIn(0f, 1f) * 100).toInt()} %"}", size = 44.dp, strokeWidth = 5.dp)
            else if (status != null) BpmStatusChip(status.text, status.tone)
            trailing?.invoke(this)
        }
    }
}
