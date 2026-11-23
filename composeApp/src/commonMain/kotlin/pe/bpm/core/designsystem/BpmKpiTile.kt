package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Indicador (`.kpi` de la maqueta): etiqueta en mayúsculas, cifra grande en Baloo 2 con el color del estado ([tone]:
 * [BpmPlayTone.Action] neutro, [BpmPlayTone.Correct] bien, [BpmPlayTone.Gold] atención, [BpmPlayTone.Error] mal), una
 * barra de avance opcional ([progress], de 0 a 1) y una línea de contexto: [trend] («▲ 4 pts», verde si [trendGood] es
 * verdadero y rojo si es falso) o [support]. Con [value] nulo muestra «Sin datos» en gris y con el tamaño de un texto,
 * nunca el de una cifra, y no dibuja barra ni tendencia. Con [featured] el fondo es teal suave (uno por pantalla).
 * Mide lo que mide su contenido: dentro de una rejilla toma el ancho y el alto que le dé [BpmCardGrid].
 */
@Composable
fun BpmKpiTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    tone: BpmPlayTone = BpmPlayTone.Action,
    progress: Float? = null,
    trend: String? = null,
    trendGood: Boolean? = null,
    support: String? = null,
    featured: Boolean = false,
) {
    val hasData = value != null
    val valueColor = if (tone == BpmPlayTone.Action) BpmPlayColors.action.face else BpmPlayColors.textOn(tone)
    val trendColor = when (trendGood) { true -> BpmPlayColors.correct.shadow; false -> BpmPlayColors.error.shadow; null -> BpmPlayColors.muted }
    Column(
        modifier.bpmPlayFrame(if (featured) BpmPlayColors.selectedBorder else BpmPlayColors.line, if (featured) BpmPlayColors.action.soft else Color.White, 16.dp)
            .padding(12.dp).semantics(mergeDescendants = true) { contentDescription = "$label: ${value ?: "Sin datos"}" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = BpmPlayType.label.copy(fontSize = 12.5.sp, letterSpacing = 0.6.sp), color = BpmPlayColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (hasData) {
            Text(value!!, style = BpmPlayType.celebration.copy(fontSize = 28.sp, lineHeight = 30.sp), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) BpmProgressBar(progress, Modifier.fillMaxWidth(), color = BpmPlayColors.shade(tone).face, trackColor = BpmPlayColors.lock)
            if (trend != null) Text(trend, style = BpmPlayType.rowDetail, color = trendColor)
        } else Text("Sin datos", style = BpmPlayType.h2, color = BpmPlayColors.muted)
        if (support != null) Text(support, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
    }
}
