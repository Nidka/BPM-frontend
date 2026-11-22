package pe.bpm.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Tono de un [BpmChunkyButton]: color de relleno y de relieve. */
enum class BpmChunkyTone { Action, Correct, Error, Report, White, Disabled }

private val ChunkyShape = RoundedCornerShape(16.dp)

/** Alto del relieve inferior del botón grueso; el cuerpo mide 52 dp ([BpmSpacing.button]). */
private val ChunkyRelief = 4.dp

/**
 * Botón grueso del estilo de juego (`.btn` de la maqueta): cuerpo de 52 dp con radio 16, texto en mayúsculas
 * (Nunito 900, 16 sp) y relieve inferior de 4 dp que el cuerpo cubre al pulsar (se «hunde»). El ancho lo decide
 * quien lo usa (`Modifier.fillMaxWidth()` para el botón de pie de pantalla).
 *
 * [tone] elige el color: [BpmChunkyTone.Action] (teal), [BpmChunkyTone.Correct] (verde), [BpmChunkyTone.Error] (rojo),
 * [BpmChunkyTone.Report] (coral), [BpmChunkyTone.White] (secundario con borde) y [BpmChunkyTone.Disabled] (gris, sin acción).
 * Con [enabled] en falso se ve siempre como [BpmChunkyTone.Disabled]. [icon] va a la izquierda del texto, a 22 dp.
 * Con «reducir movimiento» el hundimiento es instantáneo. Mantiene foco visible por teclado.
 */
@Composable
fun BpmChunkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: BpmChunkyTone = BpmChunkyTone.Action,
    icon: BpmSymbol? = null,
    enabled: Boolean = true,
) {
    val effective = if (enabled) tone else BpmChunkyTone.Disabled
    val active = effective != BpmChunkyTone.Disabled
    val face: Color; val relief: Color; val content: Color
    when (effective) {
        BpmChunkyTone.Action -> { face = BpmPlayColors.action.face; relief = BpmPlayColors.action.shadow; content = Color.White }
        BpmChunkyTone.Correct -> { face = BpmPlayColors.correct.face; relief = BpmPlayColors.correct.shadow; content = Color.White }
        BpmChunkyTone.Error -> { face = BpmPlayColors.error.face; relief = BpmPlayColors.error.shadow; content = Color.White }
        BpmChunkyTone.Report -> { face = BpmPlayColors.streak.face; relief = BpmPlayColors.streak.shadow; content = Color.White }
        BpmChunkyTone.White -> { face = Color.White; relief = BpmPlayColors.line; content = BpmPlayColors.action.face }
        BpmChunkyTone.Disabled -> { face = BpmPlayColors.lock; relief = BpmPlayColors.lockShadow; content = BpmPlayColors.lockText }
    }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val reduceMotion = rememberReduceMotion()
    val sink by animateDpAsState(if (pressed && active) ChunkyRelief else 0.dp,
        if (reduceMotion) snap() else tween(BpmMotion.SHORT / 2), label = "Hundimiento del botón grueso")
    // propagateMinConstraints: el cuerpo ocupa todo el ancho solo si quien lo usa se lo pide al contenedor.
    Box(modifier.background(relief, ChunkyShape).padding(bottom = ChunkyRelief), propagateMinConstraints = true) {
        Row(
            Modifier
                .offset(y = sink)
                .defaultMinSize(minHeight = BpmSpacing.button)
                .clip(ChunkyShape)
                .background(face)
                .then(if (effective == BpmChunkyTone.White) Modifier.border(BpmPlayBorder, BpmPlayColors.line, ChunkyShape) else Modifier)
                .then(if (focused && active) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, ChunkyShape) else Modifier)
                .clickable(interactionSource = interactions, indication = null, enabled = active, role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) BpmIcon(icon, tint = content, modifier = Modifier.size(22.dp))
            Text(text.uppercase(), color = content, style = BpmPlayType.button, textAlign = TextAlign.Center)
        }
    }
}
