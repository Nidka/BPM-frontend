package pe.bpm.features.reports.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.features.reports.domain.DayCompliance
import pe.bpm.features.reports.domain.ReportDates

/**
 * Gráfico propio de cumplimiento por día (Canvas, sin librerías). Solo se dibujan los días con
 * ejecuciones. Los colores salen del tema, por lo que se leen en claro y oscuro; la información no
 * depende del color: hay etiquetas de eje, una descripción accesible y siempre una tabla equivalente.
 */
@Composable
fun ComplianceChart(days: List<DayCompliance>, line: Boolean, modifier: Modifier = Modifier) {
    val points = days.mapNotNull { day -> day.average?.let { ReportDates.parse(day.date)?.let { epoch -> epoch to it } } }.sortedBy { it.first }
    val summary = chartDescription(days)
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().semantics { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                listOf("100", "50", "0").forEach { Text("$it %", style = MaterialTheme.typography.labelSmall, color = secondary) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val w = size.width
                val h = size.height
                listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1.dp.toPx()) }
                if (points.isEmpty()) return@Canvas
                val start = points.first().first
                val span = (points.last().first - start).coerceAtLeast(1)
                fun x(epoch: Long) = if (points.size == 1) w / 2 else (epoch - start).toFloat() / span * (w - 8.dp.toPx()) + 4.dp.toPx()
                fun y(value: Double) = h - (value.coerceIn(0.0, 100.0) / 100.0).toFloat() * h
                if (line) {
                    val path = Path()
                    points.forEachIndexed { i, (epoch, value) -> if (i == 0) path.moveTo(x(epoch), y(value)) else path.lineTo(x(epoch), y(value)) }
                    drawPath(path, bar, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                    points.forEach { (epoch, value) -> drawCircle(bar, 4.dp.toPx(), Offset(x(epoch), y(value))) }
                } else {
                    val slot = w / points.size.coerceAtLeast(1)
                    val barWidth = (slot * 0.6f).coerceIn(2.dp.toPx(), 28.dp.toPx())
                    points.forEachIndexed { i, (_, value) ->
                        val top = y(value)
                        val left = slot * i + (slot - barWidth) / 2
                        drawRect(bar, Offset(left, top), Size(barWidth, h - top))
                    }
                }
            }
        }
        if (points.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(shortDate(ReportDates.format(points.first().first)), style = MaterialTheme.typography.labelSmall, color = secondary)
            if (points.size > 1) Text(shortDate(ReportDates.format(points.last().first)), style = MaterialTheme.typography.labelSmall, color = secondary)
        }
    }
}

/** Texto equivalente para lectores de pantalla. */
internal fun chartDescription(days: List<DayCompliance>): String {
    val values = days.mapNotNull { it.average }
    if (values.isEmpty()) return "Gráfico de cumplimiento diario sin datos en el período."
    return "Gráfico de cumplimiento diario con ${values.size} días con controles. Mínimo ${formatPercent(values.min())}, máximo ${formatPercent(values.max())}."
}

/** Barra horizontal simple para comparar sedes, plantillas o cursos (0 a 100 %). */
@Composable
fun PercentBar(value: Double?, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        drawRect(track, size = size)
        if (value != null) drawRect(fill, size = Size(size.width * (value.coerceIn(0.0, 100.0) / 100.0).toFloat(), size.height))
    }
}
