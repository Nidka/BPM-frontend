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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch

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
    const val STAGGER = 40
    const val STAGGER_MAX_ITEMS = 5
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
 * Implementada como `Modifier.Node` (no `composed`): sin asignaciones por recomposición y con
 * el progreso leído en la fase de colocación, sin recomponer ni medir de nuevo cada cuadro.
 */
@Composable
fun Modifier.bpmAppear(index: Int = 0): Modifier = this.then(AppearElement(index, rememberReduceMotion()))

private data class AppearElement(val index: Int, val reduce: Boolean) : ModifierNodeElement<AppearNode>() {
    override fun create() = AppearNode(index, reduce)
    override fun update(node: AppearNode) { node.index = index; node.updateReduce(reduce) }
}

private class AppearNode(var index: Int, var reduce: Boolean) : Modifier.Node(), LayoutModifierNode {
    private val progress = Animatable(if (reduce) 1f else 0f)

    fun updateReduce(value: Boolean) {
        if (value && !reduce) coroutineScope.launch { progress.snapTo(1f) }
        reduce = value
    }

    override fun onAttach() {
        coroutineScope.launch {
            if (reduce) progress.snapTo(1f)
            else progress.animateTo(1f, tween(BpmMotion.MEDIUM,
                delayMillis = index.coerceIn(0, BpmMotion.STAGGER_MAX_ITEMS) * BpmMotion.STAGGER, easing = BpmMotion.enter))
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            // El bloque de la capa lee el progreso en dibujo: el cuadro animado no vuelve a medir ni a componer.
            placeable.placeWithLayer(0, 0) {
                alpha = progress.value
                translationY = (1f - progress.value) * 16.dp.toPx()
            }
        }
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
