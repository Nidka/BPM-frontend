package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Lista de filas ([BpmTaskRow], [BpmCourseRow]) en [columns] columnas de igual ancho, alineadas arriba y separadas por
 * [BpmSpacing.sm]. A diferencia de [BpmCardGrid] no iguala las alturas con medidas intrínsecas, que estiran las filas
 * planas de más. Con una columna es una simple lista vertical.
 */
@Composable
fun <T> BpmRowGrid(items: List<T>, columns: Int, modifier: Modifier = Modifier, content: @Composable (T, Modifier) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        // Con una columna no hay nada que igualar: cada fila mide lo que necesita. Pedir medidas intrínsecas aquí estiraba
        // las filas de curso a unos 250 dp en 390 dp, donde el chip de estado baja bajo el título.
        if (columns <= 1) { items.forEach { item -> content(item, Modifier.fillMaxWidth()) }; return@Column }
        items.chunked(columns.coerceAtLeast(1)).forEach { row ->
            // Las filas de una misma fila de la rejilla miden lo mismo: cada celda llena la altura de la más alta.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                row.forEach { item -> content(item, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
