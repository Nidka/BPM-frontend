package pe.bpm.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Franja de respuesta del estilo de juego (`.fb`), que sube desde abajo al comprobar una pregunta: fondo verde suave
 * si [correct] o rojo suave si no, círculo blanco de 36 dp con visto o X, [title] en Baloo 2 («¡Muy bien!», «Casi»),
 * [explanation] y un botón grueso a todo el ancho con [actionLabel] («Continuar», «Entendido»).
 *
 * Aparece con un fundido y un deslizamiento vertical corto (`BpmMotion.MEDIUM`); con «reducir movimiento» aparece al
 * instante. Al aparecer suena y vibra una vez ([BpmFeedbackEffects.success] o `error`, según los interruptores de Perfil). Se anuncia a los lectores de pantalla como región activa. Va pegada al borde inferior, sin radio: quien la
 * usa decide si la separa de la barra o del pie.
 */
@Composable
fun BpmFeedbackSheet(
    correct: Boolean,
    title: String,
    explanation: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = rememberReduceMotion()
    val effects = rememberBpmFeedbackEffects()
    LaunchedEffect(correct) { if (correct) effects.success() else effects.error() }
    val visible = remember { MutableTransitionState(reduce).apply { targetState = true } }
    val shade = if (correct) BpmPlayColors.correct else BpmPlayColors.error
    AnimatedVisibility(
        visibleState = visible, modifier = modifier,
        enter = if (reduce) EnterTransition.None
            else fadeIn(tween(BpmMotion.MEDIUM, easing = BpmMotion.enter)) + slideInVertically(BpmMotion.spatial()) { it / 3 },
    ) {
        Column(
            Modifier.fillMaxWidth().background(shade.soft).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 18.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    BpmIcon(if (correct) BpmSymbol.Check else BpmSymbol.Close, tint = shade.face, modifier = Modifier.size(24.dp))
                }
                Text(title, style = BpmPlayType.feedbackTitle, color = shade.shadow)
            }
            Text(explanation, style = BpmPlayType.bubble, color = if (correct) BpmPlayColors.feedbackCorrectText else BpmPlayColors.feedbackWrongText)
            BpmChunkyButton(actionLabel, onAction, Modifier.fillMaxWidth(), tone = if (correct) BpmChunkyTone.Correct else BpmChunkyTone.Error)
        }
    }
}
