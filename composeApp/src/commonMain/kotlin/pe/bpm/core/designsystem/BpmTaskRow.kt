package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Ancho de fila por debajo del cual el chip de estado baja bajo el título (regla G6). */
private val NarrowRow = 360.dp

private enum class RowSlot { Icon, Title, Detail, Status, Below }

/**
 * Fila de una tarea o elemento de lista: icono en un cuadro de 40 dp con el color del [tone], título en negrita
 * (máximo 2 líneas) y [detail] atenuado («Cocina · Diaria»). [trailing] (por ejemplo un [BpmStatusChip]) va a la
 * derecha; si la fila mide menos de 360 dp va debajo del título, en la línea del [detail] (o bajo ella si no cabe).
 * Si la fila es clicable y no hay [trailing] se muestra una flecha. [below] dibuja contenido extra bajo el texto
 * (por ejemplo una barra de avance). [selected] dibuja un borde primario de 2 dp.
 *
 * Con [framed] = true (por defecto) la fila es una tarjeta con borde. Dentro de una tarjeta usa [framed] = false:
 * la fila es plana y se separa de la siguiente con [BpmRowDivider] (regla G5, nada de tarjetas dentro de tarjetas).
 *
 * El ancho de la fila se mide en un diseño propio (sin `BoxWithConstraints`) para que siga funcionando dentro de
 * rejillas que piden medidas intrínsecas ([BpmCardGrid]).
 */
@Composable
fun BpmTaskRow(
    title: String,
    symbol: BpmSymbol,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: BpmTone = BpmTone.Info,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    framed: Boolean = true,
    detailMaxLines: Int = Int.MAX_VALUE,
    below: (@Composable () -> Unit)? = null,
) {
    val (toneBackground, toneContent) = tone.colors()
    val inset = if (framed) BpmSpacing.md else 0.dp
    // El umbral se expresa sobre el ancho total de la fila; el diseño recibe el ancho sin el relleno.
    val narrowBelow = NarrowRow - inset * 2
    val row: @Composable () -> Unit = {
        Layout(
            content = {
                Box(Modifier.layoutId(RowSlot.Icon).size(40.dp).clip(RoundedCornerShape(12.dp)).background(toneBackground), contentAlignment = Alignment.Center) {
                    BpmIcon(symbol, tint = toneContent, modifier = Modifier.size(22.dp))
                }
                Text(title, Modifier.layoutId(RowSlot.Title), style = MaterialTheme.typography.titleMedium, maxLines = bpmTitleLines(), overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, Modifier.layoutId(RowSlot.Detail), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = detailMaxLines, overflow = TextOverflow.Ellipsis)
                if (trailing != null) Box(Modifier.layoutId(RowSlot.Status)) { trailing() }
                else if (onClick != null) BpmIcon(BpmSymbol.ChevronRight, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.layoutId(RowSlot.Status).size(20.dp))
                if (below != null) Box(Modifier.layoutId(RowSlot.Below)) { below() }
            },
            modifier = Modifier.fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = inset, vertical = if (framed) BpmSpacing.md else BpmSpacing.sm),
        ) { measurables, constraints ->
            fun slot(s: RowSlot) = measurables.firstOrNull { it.layoutId == s }
            val gap = BpmSpacing.md.roundToPx()
            val small = BpmSpacing.xs.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val icon = slot(RowSlot.Icon)!!.measure(loose)
            val status = slot(RowSlot.Status)
            // Con chip de estado y fila estrecha, el chip baja bajo el título; el flecha de «abrir» siempre va a la derecha.
            val statusBelow = trailing != null && status != null && constraints.maxWidth < narrowBelow.roundToPx()
            val statusEnd = if (status != null && !statusBelow) status.measure(loose) else null
            val textWidth = (constraints.maxWidth - icon.width - gap - (statusEnd?.let { it.width + gap } ?: 0)).coerceAtLeast(0)
            val textLoose = Constraints(maxWidth = textWidth)
            val titleP = slot(RowSlot.Title)!!.measure(textLoose)
            val detailP = slot(RowSlot.Detail)?.measure(textLoose)
            val chipP = if (statusBelow) status!!.measure(textLoose) else null
            val belowP = slot(RowSlot.Below)?.measure(textLoose)
            // Línea de meta (detalle + chip): en la misma línea si caben; si no, el chip va debajo.
            val sameLine = chipP != null && detailP != null && detailP.width + small * 2 + chipP.width <= textWidth
            val parts = buildList {
                add(titleP.height)
                if (chipP == null) detailP?.let { add(it.height) }
                else if (detailP == null) add(chipP.height)
                else if (sameLine) add(maxOf(detailP.height, chipP.height))
                else { add(detailP.height); add(chipP.height) }
                belowP?.let { add(it.height) }
            }
            val columnHeight = parts.sum() + small * (parts.size - 1)
            val height = maxOf(BpmSpacing.touch.roundToPx(), icon.height, columnHeight, statusEnd?.height ?: 0)
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else icon.width + gap + maxOf(titleP.width, detailP?.width ?: 0)
            layout(width, height) {
                icon.placeRelative(0, (height - icon.height) / 2)
                val x = icon.width + gap
                var y = (height - columnHeight) / 2
                titleP.placeRelative(x, y); y += titleP.height + small
                if (chipP == null) detailP?.let { it.placeRelative(x, y); y += it.height + small }
                else if (detailP == null) { chipP.placeRelative(x, y); y += chipP.height + small }
                else if (sameLine) {
                    val line = maxOf(detailP.height, chipP.height)
                    detailP.placeRelative(x, y + (line - detailP.height) / 2)
                    chipP.placeRelative(x + detailP.width + small * 2, y + (line - chipP.height) / 2)
                    y += line + small
                } else {
                    detailP.placeRelative(x, y); y += detailP.height + small
                    chipP.placeRelative(x, y); y += chipP.height + small
                }
                belowP?.placeRelative(x, y)
                statusEnd?.placeRelative(width - statusEnd.width, (height - statusEnd.height) / 2)
            }
        }
    }
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        if (framed) {
            val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Surface(modifier.fillMaxWidth().semantics { this.selected = selected }, shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, border)) { row() }
        } else Box(modifier.fillMaxWidth().semantics { this.selected = selected }) { row() }
    }
}

/** Divisor fino entre filas planas ([BpmTaskRow] con `framed = false`) dentro de una misma tarjeta. */
@Composable
fun BpmRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * Fila de curso para listas y catálogos: icono, [title], [description] (máximo 2 líneas), barra de [progress]
 * opcional (0 a 1) y [status] (por ejemplo un [BpmStatusChip]) que sigue las reglas de [BpmTaskRow]. Toda la fila es
 * el botón. Sustituye a la tarjeta con banda «CURSO» ([BpmCourseCard]) en las listas compactas.
 */
@Composable
fun BpmCourseRow(
    title: String,
    description: String?,
    symbol: BpmSymbol,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    status: (@Composable () -> Unit)? = null,
    framed: Boolean = true,
) = BpmTaskRow(
    title, symbol, modifier, detail = description?.takeIf { it.isNotBlank() }, trailing = status, onClick = onClick, framed = framed,
    detailMaxLines = 2, below = progress?.let { value -> { BpmProgressBar(value) } },
)
