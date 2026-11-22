package pe.bpm.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.tween

/** Estado de una [BpmOptionTile]: sin elegir, elegida, correcta o equivocada. */
enum class BpmOptionState { Idle, Selected, Correct, Wrong }

/**
 * Opción de respuesta del estilo de juego (`.opt`): ficha de al menos 62 dp con un recuadro numerado a la izquierda
 * ([key], el atajo de teclado 1–9; sin él, no se dibuja), borde de 2 dp con 4 dp abajo y radio 16. Los colores cambian
 * según [state] (elegida en teal, correcta en verde, equivocada en rojo) con una transición corta. La ficha mide
 * lo que mide su texto: nunca se estira en alto. Con [enabled] en falso (tras comprobar) no responde al toque.
 */
@Composable
fun BpmOptionTile(
    text: String,
    key: String?,
    state: BpmOptionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spec = when (state) {
        BpmOptionState.Idle -> OptionColors(BpmPlayColors.line, Color.White, BpmPlayColors.ink, BpmPlayColors.line, BpmPlayColors.muted)
        BpmOptionState.Selected -> OptionColors(BpmPlayColors.selectedBorder, BpmPlayColors.action.soft, BpmPlayColors.action.face, BpmPlayColors.selectedBorder, BpmPlayColors.action.face)
        BpmOptionState.Correct -> OptionColors(BpmPlayColors.correctBorder, BpmPlayColors.correct.soft, BpmPlayColors.correct.shadow, BpmPlayColors.correctBorder, BpmPlayColors.correct.shadow)
        BpmOptionState.Wrong -> OptionColors(BpmPlayColors.wrongBorder, BpmPlayColors.error.soft, BpmPlayColors.error.shadow, BpmPlayColors.line, BpmPlayColors.muted)
    }
    val frame by animateColorAsState(spec.frame, tween(BpmMotion.SHORT), label = "Borde de la opción")
    val fill by animateColorAsState(spec.fill, tween(BpmMotion.SHORT), label = "Fondo de la opción")
    Row(
        modifier
            .semantics { selected = state == BpmOptionState.Selected }
            .bpmPlayFrame(frame, fill, 16.dp)
            .defaultMinSize(minHeight = 56.dp) // + borde de 2 y de 4 dp = 62 dp en total
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (key != null) Box(Modifier.size(32.dp).border(BpmPlayBorder, spec.keyBorder, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(key, color = spec.keyText, style = BpmPlayType.key)
        }
        Text(text, color = spec.text, style = BpmPlayType.option, modifier = Modifier.weight(1f))
    }
}

private class OptionColors(val frame: Color, val fill: Color, val text: Color, val keyBorder: Color, val keyText: Color)

/**
 * Ficha de selección con icono grande (`.tile`): para elegir entre pocas opciones con icono (cómo quedó una tarea,
 * categoría de un reporte). Icono de 30 dp sobre un cuadro de 56 dp con el color suave de [tone], texto centrado,
 * borde de 2 dp con 5 dp abajo y radio 18. [selected] la marca en teal. Con [horizontal] el icono va a la izquierda y el
 * texto a su derecha, para una ficha que ocupa todo el ancho. La altura sale del contenido (no se estira).
 */
@Composable
fun BpmChoiceTile(
    text: String,
    icon: BpmSymbol,
    tone: BpmPlayTone,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    val shade = BpmPlayColors.shade(tone)
    val frame by animateColorAsState(if (selected) BpmPlayColors.selectedBorder else BpmPlayColors.line, tween(BpmMotion.SHORT), label = "Borde de la ficha")
    val fill by animateColorAsState(if (selected) BpmPlayColors.action.soft else Color.White, tween(BpmMotion.SHORT), label = "Fondo de la ficha")
    val big: @Composable () -> Unit = {
        Box(Modifier.size(56.dp).background(shade.soft, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            BpmIcon(icon, tint = shade.ink, modifier = Modifier.size(30.dp))
        }
    }
    val label: @Composable (Modifier) -> Unit = { m ->
        Text(text, color = BpmPlayColors.ink, style = BpmPlayType.tile, textAlign = if (horizontal) TextAlign.Start else TextAlign.Center, modifier = m)
    }
    val clickable = Modifier
        .semantics { this.selected = selected }
        .bpmPlayFrame(frame, fill, 18.dp, bottom = 5.dp)
        .clickable(role = Role.RadioButton, onClick = onClick)
    if (horizontal) Row(modifier.then(clickable).padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        big(); label(Modifier.weight(1f))
    } else Column(modifier.then(clickable).padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        big(); label(Modifier.fillMaxWidth())
    }
}
