package pe.bpm.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Contador compacto del estilo de juego (`.pill`): icono de 20 dp y valor (Nunito 900, 15 sp) dentro de una píldora con
 * borde de 2 dp y radio 14. El color del icono y del valor sale de [tone]: racha [BpmPlayTone.Streak], XP
 * [BpmPlayTone.Gold], meta del día [BpmPlayTone.Action]. [contentDescription] es lo que lee el lector de pantalla
 * («Racha de 6 días»); si es nulo se lee el valor.
 */
@Composable
fun BpmStatPill(
    icon: BpmSymbol,
    value: String,
    tone: BpmPlayTone,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val ink = BpmPlayColors.shade(tone).ink
    Row(
        modifier.border(BpmPlayBorder, BpmPlayColors.line, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { if (contentDescription != null) this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BpmIcon(icon, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(5.dp)) // 5 dp entre icono y valor, como la maqueta
        Text(value, color = BpmPlayColors.textOn(tone), style = BpmPlayType.pill)
    }
}

/**
 * Barra de avance gruesa (`.bar`): 16 dp de alto, pista gris, relleno redondeado del color de [tone] con la franja
 * inferior más oscura y un brillo claro arriba. [progress] va de 0 a 1. Por defecto verde ([BpmPlayTone.Correct]);
 * la evaluación final usa [BpmPlayTone.Gold]. El relleno se anima (instantáneo con «reducir movimiento») y se
 * dibuja sin recomponer. Ocupa el ancho que le dé quien la usa (`weight(1f)` o `fillMaxWidth()`).
 */
@Composable
fun BpmChunkyProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    tone: BpmPlayTone = BpmPlayTone.Correct,
    contentDescription: String? = null,
) {
    val target = progress.coerceIn(0f, 1f)
    val reduceMotion = rememberReduceMotion()
    val shown by animateFloatAsState(target, if (reduceMotion) snap() else tween(BpmMotion.MEDIUM, easing = BpmMotion.enter), label = "Avance de la barra")
    val shade = BpmPlayColors.shade(tone)
    Canvas(modifier.height(16.dp).clip(CircleShape).background(BpmPlayColors.lock)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f); if (contentDescription != null) this.contentDescription = contentDescription }) {
        val width = size.width * shown
        if (width > 0f) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(shade.face, Offset.Zero, Size(width, size.height), radius)
            // Franja inferior de 4 dp más oscura (inset 0 -4px 0 rgba(0,0,0,.12)).
            val band = 4.dp.toPx()
            drawRoundRect(Color.Black.copy(alpha = 0.12f), Offset(0f, size.height - band), Size(width, band), CornerRadius(band / 2f))
            // Brillo: 4 dp de alto, 3 dp bajo el borde superior y 8 dp de margen lateral.
            val inset = 8.dp.toPx()
            if (width > inset * 2) drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(inset, 3.dp.toPx()), Size(width - inset * 2, 4.dp.toPx()), CornerRadius(4.dp.toPx()))
        }
    }
}

/**
 * Puntos de pasos de un flujo corto (`.steps3`): [total] barras de 8 dp de alto que se reparten el ancho; las hasta el
 * paso [current] (de 1 a [total], incluido) van en verde y el resto en gris. Anuncia «Paso N de M».
 */
@Composable
fun BpmStepDots(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics { contentDescription = "Paso $current de $total" }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { index ->
            val on = index < current
            Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(if (on) BpmPlayColors.correct.face else BpmPlayColors.lock))
        }
    }
}
