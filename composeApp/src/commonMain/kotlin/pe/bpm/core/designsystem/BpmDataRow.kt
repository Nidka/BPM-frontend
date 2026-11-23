package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Separador entre filas de una tabla (`.trow`). */
private val RowDivider = Color(0xFFEEF2F2)
/** Ancho de la columna de cifra y de la de estado, iguales en todas las filas para que las barras queden alineadas. */
private val ValueWidth: Dp = 56.dp
private val StatusWidth: Dp = 112.dp

/**
 * Fila de tabla (`.trow` de la maqueta) para sedes, organizaciones y reportes: [title] con [subtitle], una barra de
 * avance ([progress] de 0 a 1, del color de [tone]), la cifra [value] («78 %») y el chip de estado ([status]). Las
 * columnas tienen la misma medida en todas las filas, así las barras quedan alineadas; filas sucesivas se separan con una
 * línea de 2 dp ([divider]). Con [stacked] (por defecto en teléfono, según la clase de ancho vigente) se apila: título
 * con estado arriba y, debajo, la barra con su cifra. Con [onClick] toda la fila es el botón (mínimo 48 dp) y con
 * [selected] queda resaltada. Pensada para ir dentro de una tarjeta; no usa medidas intrínsecas.
 */
@Composable
fun BpmDataRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    value: String? = null,
    tone: BpmPlayTone = BpmPlayTone.Correct,
    status: BpmRecordStatus? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    divider: Boolean = true,
    stacked: Boolean = currentWindowClass == BpmWindowClass.Compact,
) {
    val shade = BpmPlayColors.shade(tone)
    val bar: @Composable (Modifier) -> Unit = { m ->
        if (progress != null) BpmProgressBar(progress, m, color = shade.face, trackColor = BpmPlayColors.lock)
    }
    val names: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(title, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val number: @Composable () -> Unit = {
        if (value != null) Text(value, style = BpmPlayType.key, color = BpmPlayColors.ink, textAlign = TextAlign.End, maxLines = 1)
    }
    val base = modifier.fillMaxWidth()
        .then(if (divider) Modifier.drawBehind { drawRect(RowDivider, Offset.Zero, Size(size.width, BpmPlayBorder.toPx())) } else Modifier)
        .then(if (selected) Modifier.background(BpmPlayColors.action.soft, RoundedCornerShape(12.dp)) else Modifier)
        .clip(RoundedCornerShape(12.dp))
    BpmFeedback(BpmPlayColors.action.face) {
        val row = if (onClick != null) base.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else base
        val content = row.semantics { this.selected = selected }.heightIn(min = BpmSpacing.touch).padding(horizontal = 4.dp, vertical = 8.dp)
        if (stacked) Column(content, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                names(Modifier.weight(1f))
                if (status != null) BpmStatusChip(status.text, status.tone)
            }
            if (progress != null || value != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                bar(Modifier.weight(1f))
                Box(Modifier.width(ValueWidth), contentAlignment = Alignment.CenterEnd) { number() }
            }
        } else Row(content, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            names(Modifier.weight(1.1f))
            if (progress != null) bar(Modifier.weight(1f)) else if (value != null || status != null) Box(Modifier.weight(1f))
            Box(Modifier.width(ValueWidth), contentAlignment = Alignment.CenterEnd) { number() }
            Box(Modifier.width(StatusWidth), contentAlignment = Alignment.CenterEnd) { if (status != null) BpmStatusChip(status.text, status.tone) }
        }
    }
}
