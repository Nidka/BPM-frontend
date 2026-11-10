package pe.bpm.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.unit.dp

/**
 * Movimiento de la biblioteca: duraciones y curvas comunes para que toda transición se sienta
 * igual. Las duraciones siguen las escalas corta/media/larga de Material 3 y el movimiento se
 * reduce con la preferencia del sistema. Ver `.claude/skills/bpm-motion`.
 */
object BpmMotion {
    /** Cambios de estado de un control: pulsación, selección, cambio de color. */
    const val SHORT = 150
    /** Aparición de contenido y cambio de tamaño. */
    const val MEDIUM = 300
    /** Transiciones de pantalla o de bloques grandes. */
    const val LONG = 450
    /** Retraso entre elementos consecutivos de una lista y tope para que la cascada no se alargue. */
    const val STAGGER = 45
    const val STAGGER_MAX_ITEMS = 6
    /** Curva de desaceleración de Material 3 («emphasized decelerate»): para lo que entra. */
    val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Curva de aceleración de Material 3 («emphasized accelerate»): para lo que sale. */
    val exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Resorte amortiguado para tamaño y posición; se siente físico y se puede interrumpir. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    fun <T> fadeIn(): FiniteAnimationSpec<T> = tween(MEDIUM, easing = enter)
}

/** Verdadero si la persona pidió reducir el movimiento en su sistema o navegador. */
@Composable
expect fun rememberReduceMotion(): Boolean

/**
 * Aparición escalonada: el elemento sube unos dp mientras se vuelve opaco, con retraso según
 * su [index]. Se ejecuta una vez al entrar a la composición; con «reducir movimiento» aparece al instante.
 */
fun Modifier.bpmAppear(index: Int = 0): Modifier = composed {
    val reduce = rememberReduceMotion()
    val progress = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(reduce) {
        if (reduce) progress.snapTo(1f)
        else progress.animateTo(1f, tween(BpmMotion.MEDIUM, delayMillis = index.coerceIn(0, BpmMotion.STAGGER_MAX_ITEMS) * BpmMotion.STAGGER, easing = BpmMotion.enter))
    }
    // graphicsLayer lee el progreso en la fase de dibujo: no recompone ni vuelve a medir cada cuadro.
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 16.dp.toPx()
    }
}

/**
 * Hace aparecer un aviso, error o confirmación con fundido y despliegue vertical en lugar de
 * «saltar» al layout. Con «reducir movimiento» aparece al instante. Se retira de la composición
 * al salir (no deja controles enfocables ocultos).
 */
@Composable
fun BpmReveal(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = rememberReduceMotion()
    val visible = remember { MutableTransitionState(reduce).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visible, modifier = modifier,
        enter = if (reduce) EnterTransition.None
            else fadeIn(BpmMotion.fadeIn()) + expandVertically(BpmMotion.spatial()) + slideInVertically(BpmMotion.spatial()) { -it / 4 },
        exit = fadeOut(tween(BpmMotion.SHORT, easing = BpmMotion.exit)) + shrinkVertically(tween(BpmMotion.SHORT, easing = BpmMotion.exit)),
    ) { content() }
}
