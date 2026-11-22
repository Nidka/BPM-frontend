package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rejilla de tarjetas del mockup «Gestión principal»: tantas columnas como quepan con al menos
 * [minCardWidth] por tarjeta (hasta [maxColumns]) y la misma altura dentro de cada fila.
 * Cada tarjeta aparece en cascada (`bpmAppear`). [content] recibe el modificador que debe aplicar a su tarjeta raíz.
 */
@Composable
fun <T> BpmCardGrid(
    items: List<T>,
    modifier: Modifier = Modifier,
    minCardWidth: Dp = 360.dp,
    maxColumns: Int = 3,
    fixedColumns: Int? = null,
    content: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = fixedColumns ?: ((maxWidth + BpmSpacing.grid) / (minCardWidth + BpmSpacing.grid)).toInt().coerceIn(1, maxColumns)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
            // Una columna: cada tarjeta mide lo que mide su contenido (sin igualar alturas ni estirar).
            if (columns == 1) { items.forEachIndexed { index, item -> content(item, Modifier.fillMaxWidth().bpmAppear(index)) }; return@Column }
            items.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
                    row.forEachIndexed { column, item -> content(item, Modifier.weight(1f).fillMaxHeight().bpmAppear(rowIndex * columns + column)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
