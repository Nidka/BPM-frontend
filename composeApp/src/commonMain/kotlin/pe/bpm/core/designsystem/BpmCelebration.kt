package pe.bpm.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Datos de una ficha de resultado (`.stat`): [label] va en mayúsculas sobre la franja de color y [value] debajo, con
 * [icon] opcional a su izquierda. [tone] da el color de borde, franja y valor: XP [BpmPlayTone.Gold], racha
 * [BpmPlayTone.Streak], aciertos [BpmPlayTone.Correct].
 */
@Immutable
data class BpmStatTileSpec(val label: String, val value: String, val tone: BpmPlayTone = BpmPlayTone.Gold, val icon: BpmSymbol? = null)

/**
 * Celebración del estilo de juego (`.cel`): la ilustración del tomate ([image], 170 dp de ancho), un [title] grande en
 * dorado (Baloo 2, 32 sp), un [subtitle] gris y una fila de fichas de resultado ([stats], hasta tres, que se reparten el ancho).
 * Se centra en horizontal y mide lo que mide su contenido; el botón de continuar lo pone quien la usa debajo.
 * [titleColor] y [imageWidth] ajustan el título y la ilustración (el resultado de una evaluación usa 140 dp) y
 * [middle] inserta contenido propio (p. ej. el puntaje grande) entre el subtítulo y las fichas.
 *
 * Con [celebrate] (por defecto) suena y vibra una vez al mostrarse ([BpmFeedbackEffects.celebrate], con los interruptores
 * de Perfil) y un estallido breve de estrellas y confeti ([BpmConfettiBurst]) sale de arriba; con «reducir movimiento»
 * no hay confeti. Un resultado sin logro («Casi lo logras») pasa `celebrate = false`.
 */
@Composable
fun BpmCelebration(
    image: DrawableResource,
    title: String,
    subtitle: String?,
    stats: List<BpmStatTileSpec>,
    modifier: Modifier = Modifier,
    titleColor: Color = BpmPlayColors.gold.shadow,
    imageWidth: Dp = 170.dp,
    middle: (@Composable ColumnScope.() -> Unit)? = null,
    celebrate: Boolean = true,
) {
    val effects = rememberBpmFeedbackEffects()
    LaunchedEffect(celebrate) { if (celebrate) effects.celebrate() }
    Box(modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(image), contentDescription = null, modifier = Modifier.width(imageWidth))
        Text(title, style = BpmPlayType.celebration, color = titleColor, textAlign = TextAlign.Center)
        if (subtitle != null) Text(subtitle, style = BpmPlayType.text, color = BpmPlayColors.muted, textAlign = TextAlign.Center)
        if (middle != null) middle()
        // Con cuatro fichas el ancho de 390 dp es justo: se acerca el espacio entre ellas para que la etiqueta quepa entera.
        if (stats.isNotEmpty()) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(if (stats.size > 3) 8.dp else 12.dp)) {
            stats.forEach { BpmStatTile(it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
    if (celebrate) BpmConfettiBurst(Modifier.matchParentSize())
    }
}

/** Ficha de resultado: franja de color con la etiqueta y, debajo, el valor con su icono (`.stat`). */
@Composable
private fun BpmStatTile(spec: BpmStatTileSpec, modifier: Modifier = Modifier) {
    val shade = BpmPlayColors.shade(spec.tone)
    Column(modifier.clip(RoundedCornerShape(16.dp)).border(BpmPlayBorder, shade.face, RoundedCornerShape(16.dp))
        .semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        // Franja de contraste ≥ 4,5:1: texto oscuro sobre el dorado y franja del tono oscuro en los demás.
        val gold = spec.tone == BpmPlayTone.Gold
        // Una sola línea: nunca se parte una palabra; si no cabe, la letra se reduce (hasta 8 sp) en vez de saltar de línea.
        Text(spec.label.uppercase(), Modifier.fillMaxWidth().background(if (gold) shade.face else shade.shadow).padding(vertical = 4.dp, horizontal = 2.dp), color = if (gold) BpmPlayColors.goldBandText else Color.White,
            style = BpmPlayType.statLabel.copy(letterSpacing = 0.3.sp), textAlign = TextAlign.Center, maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = BpmPlayType.statLabel.fontSize, stepSize = 0.5.sp))
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            if (spec.icon != null) BpmIcon(spec.icon, tint = shade.ink, modifier = Modifier.size(20.dp))
            // El valor también va en una sola línea («100 %» no se parte): se reduce hasta 13 sp si el ancho es justo.
            Text(spec.value, Modifier.weight(1f, fill = false), color = BpmPlayColors.textOn(spec.tone), style = BpmPlayType.statValue, maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = BpmPlayType.statValue.fontSize, stepSize = 0.5.sp))
        }
    }
}
