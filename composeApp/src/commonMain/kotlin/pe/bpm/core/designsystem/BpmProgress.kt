package pe.bpm.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Anillo de avance. [progress] va de 0 a 1 (se acota); [label] es el texto centrado (por ejemplo «3/5» o «60 %»)
 * y [contentDescription] lo que lee el lector de pantalla. La cifra usa números tabulares para que no baile.
 */
@Composable
fun BpmProgressRing(
    progress: Float,
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    strokeWidth: Dp = 7.dp,
) {
    val value = progress.coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.outlineVariant
    val arc = MaterialTheme.colorScheme.primary
    Box(
        modifier.size(size).clearAndSetSemantics {
            this.contentDescription = contentDescription
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
            if (value > 0f) drawArc(arc, -90f, 360f * value, useCenter = false, topLeft = Offset(inset, inset),
                size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(
            label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
            style = TextStyle(fontSize = (size.value * 0.26f).sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum"),
        )
    }
}

/**
 * Avance por pasos: [total] segmentos redondeados de 6 dp. Los primeros [completed] van en color primario y el
 * segmento [current] (índice desde 0), si lo hay, en el acento; el resto en gris. Útil en lecciones y evaluaciones.
 */
@Composable
fun BpmSegmentedProgress(
    total: Int,
    completed: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    current: Int? = null,
) {
    if (total <= 0) return
    val done = completed.coerceIn(0, total)
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics {
            this.contentDescription = contentDescription
            progressBarRangeInfo = ProgressBarRangeInfo(done.toFloat(), 0f..total.toFloat(), total - 1)
        },
        horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs),
    ) {
        repeat(total) { index ->
            val color = when {
                index == current -> MaterialTheme.colorScheme.secondary
                index < done -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(color))
        }
    }
}

/**
 * Barra lineal de avance de 8 dp, de uso interno (tarjetas de curso y de siguiente paso). Es decorativa:
 * el texto de la tarjeta ya dice el estado, por lo que solo expone el rango.
 */
@Composable
internal fun BpmProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    val value = progress.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(trackColor)
        .clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value).clip(CircleShape).background(color))
    }
}
