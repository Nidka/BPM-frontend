package pe.bpm.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.drawWithContent
import kotlin.math.roundToInt

/** Estado de un nodo del camino: lección hecha, la actual, bloqueada, o el trofeo de la evaluación final. */
enum class BpmPathNodeState { Done, Current, Locked, Trophy }

private val NodeWidth = 72.dp
private val NodeHeight = 66.dp
private val NodeRelief = 7.dp

/**
 * Nodo del camino de aprendizaje (`.node`): óvalo de 72 × 66 dp con relieve inferior de 7 dp y un icono de 32 dp.
 * [BpmPathNodeState.Done] dorado con visto; [BpmPathNodeState.Current] teal con estrella y un aro claro alrededor;
 * [BpmPathNodeState.Locked] gris con candado (no responde al toque); [BpmPathNodeState.Trophy] cuadrado blanco redondeado
 * con trofeo. [enabled] permite bloquear también el trofeo hasta terminar las lecciones. Se hunde al pulsar (instantáneo
 * con «reducir movimiento»). El nodo actual rebota suave tres veces al aparecer (quieto con «reducir movimiento»).
 * [contentDescription] describe la lección para el lector de pantalla; sin él, el lector dice el estado («Lección hecha», «Bloqueada»).
 */
@Composable
fun BpmPathNode(
    state: BpmPathNodeState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = state != BpmPathNodeState.Locked,
    contentDescription: String? = null,
) {
    val trophy = state == BpmPathNodeState.Trophy
    val face: Color; val relief: Color; val ink: Color
    when (state) {
        BpmPathNodeState.Done -> { face = BpmPlayColors.gold.face; relief = BpmPlayColors.gold.shadow; ink = Color.White }
        BpmPathNodeState.Current -> { face = BpmPlayColors.action.face; relief = BpmPlayColors.action.shadow; ink = Color.White }
        BpmPathNodeState.Locked -> { face = BpmPlayColors.lock; relief = BpmPlayColors.lockShadow; ink = BpmPlayColors.lockIcon }
        BpmPathNodeState.Trophy -> { face = Color.White; relief = BpmPlayColors.line; ink = BpmPlayColors.gold.shadow }
    }
    val icon = when (state) {
        BpmPathNodeState.Done -> BpmSymbol.Check
        BpmPathNodeState.Current -> BpmSymbol.Star
        BpmPathNodeState.Locked -> BpmSymbol.Lock
        BpmPathNodeState.Trophy -> BpmSymbol.Trophy
    }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val sink by animateDpAsState(if (pressed && enabled) 4.dp else 0.dp, if (reduceMotion) snap() else tween(BpmMotion.SHORT / 2), label = "Hundimiento del nodo")
    val shape = if (trophy) RoundedCornerShape(18.dp) else RoundedCornerShape(50)
    val stateLabel = when (state) {
        BpmPathNodeState.Done -> "Lección hecha"
        BpmPathNodeState.Current -> "Lección actual"
        BpmPathNodeState.Locked -> "Bloqueada"
        BpmPathNodeState.Trophy -> if (enabled) "Evaluación final" else "Evaluación final, bloqueada"
    }
    // El nodo actual rebota suave tres veces al aparecer (finito: nada se repite más de 5 s); sin movimiento, quieto.
    val bounce = remember { Animatable(0f) }
    LaunchedEffect(state, enabled, reduceMotion) {
        if (state == BpmPathNodeState.Current && enabled && !reduceMotion) {
            delay(400)
            repeat(3) {
                bounce.animateTo(-6f, tween(BpmMotion.MEDIUM / 2, easing = BpmMotion.enter))
                bounce.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
            }
        } else bounce.snapTo(0f)
    }
    // El relieve (y el aro del nodo actual) se dibujan fuera del cuadro de 72 × 66 dp; el alto reserva el relieve.
    Box(modifier.graphicsLayer { translationY = bounce.value.dp.toPx() }.size(NodeWidth, NodeHeight + NodeRelief).drawBehind {
        val w = NodeWidth.toPx(); val h = NodeHeight.toPx(); val r = NodeRelief.toPx()
        if (state == BpmPathNodeState.Current) {
            val gap = 6.dp.toPx(); val stroke = 7.dp.toPx()
            drawOval(BpmPlayColors.pathHalo, Offset(-gap - stroke / 2f, -gap - stroke / 2f), Size(w + 2 * gap + stroke, h + 2 * gap + stroke), style = Stroke(stroke))
        }
        if (trophy) drawRoundRect(relief, Offset(0f, r), Size(w, h), CornerRadius(16.dp.toPx()))
        else drawOval(relief, Offset(0f, r), Size(w, h))
    }) {
        Box(
            Modifier.offset { IntOffset(0, sink.roundToPx()) }.size(NodeWidth, NodeHeight).clip(shape).background(face)
                .then(if (trophy) Modifier.border(3.dp, BpmPlayColors.line, shape) else Modifier)
                .semantics { this.contentDescription = contentDescription ?: stateLabel }
                .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { BpmIcon(icon, tint = ink, modifier = Modifier.size(32.dp)) }
    }
}

/** Un nodo de [BpmLearningPath]: su [state], la acción al tocarlo y su descripción accesible. */
@Immutable
data class BpmPathNodeSpec(
    val state: BpmPathNodeState,
    val onClick: () -> Unit,
    val contentDescription: String? = null,
    val enabled: Boolean = state != BpmPathNodeState.Locked,
)

/** Desplazamientos horizontales (dp) del zigzag: centro, izquierda, centro, derecha. */
private val ZigzagOffsets = intArrayOf(0, -60, 0, 60)

/**
 * Camino de aprendizaje: los [nodes] en columna con zigzag (desplazamientos 0, −60, 0, +60 dp respecto al centro), 16 dp
 * entre uno y otro. Sobre el nodo actual aparece una burbuja [startLabel] («Empezar») con su punta hacia abajo; la
 * burbuja ocupa su propio espacio encima del nodo, así que nunca lo tapa ni se encima con el anterior. El camino mide
 * lo que miden sus nodos; va dentro de un contenedor con desplazamiento vertical.
 */
@Composable
fun BpmLearningPath(nodes: List<BpmPathNodeSpec>, modifier: Modifier = Modifier, startLabel: String = "Empezar") {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        nodes.forEachIndexed { index, node ->
            val dx = ZigzagOffsets[index % ZigzagOffsets.size]
            Column(Modifier.offset { IntOffset(dx.dp.roundToPx(), 0) }, horizontalAlignment = Alignment.CenterHorizontally) {
                if (node.state == BpmPathNodeState.Current) {
                    StartBubble(startLabel)
                    Box(Modifier.size(1.dp, 12.dp))
                }
                BpmPathNode(node.state, node.onClick, enabled = node.enabled, contentDescription = node.contentDescription)
            }
        }
    }
}

/** Burbuja «Empezar» (`.start`): blanca, borde de 2 dp, radio 14, texto teal en mayúsculas y punta hacia abajo. */
@Composable
private fun StartBubble(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text.uppercase(), style = BpmPlayType.label.copy(fontSize = 14.sp, letterSpacing = 0.84.sp), color = BpmPlayColors.action.face, textAlign = TextAlign.Center,
        modifier = Modifier
            .drawWithContent {
                drawContent()
                val side = 12.dp.toPx(); val line = BpmPlayBorder.toPx()
                val topLeft = Offset(size.width / 2f - side / 2f, size.height - side / 2f)
                rotate(45f, pivot = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f)) {
                    drawRect(Color.White, topLeft, Size(side, side))
                    drawRect(BpmPlayColors.line, Offset(topLeft.x + side - line, topLeft.y), Size(line, side))
                    drawRect(BpmPlayColors.line, Offset(topLeft.x, topLeft.y + side - line), Size(side, line))
                }
            }
            .background(Color.White, shape).border(BpmPlayBorder, BpmPlayColors.line, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Cabecera de módulo del camino (`.unit`): bloque de [color] con radio 18 y relieve de 4 dp, antetítulo [kicker]
 * («Módulo 1 · Sección 2») y [title] (Baloo 2, 20 sp) en blanco a la izquierda y, a la derecha, el botón de guía
 * ([guideDescription], un libro de 44 dp con borde claro) que dispara [onGuide] («Lo esencial» del curso).
 * Los colores de módulo rotan con [BpmPlayColors.unitColors].
 */
@Composable
fun BpmUnitBanner(
    kicker: String,
    title: String,
    color: Color,
    onGuide: () -> Unit,
    modifier: Modifier = Modifier,
    guideDescription: String = "Lo esencial de este módulo",
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().bpmRelief(Color.Black.copy(alpha = 0.18f), shape, 4.dp).clip(shape).background(color).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(kicker.uppercase(), style = BpmPlayType.kicker, color = Color.White.copy(alpha = 0.85f))
            Text(title, style = BpmPlayType.unitTitle, color = Color.White)
        }
        Box(Modifier.size(BpmSpacing.touch).clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = guideDescription }.clickable(role = Role.Button, onClick = onGuide), contentAlignment = Alignment.Center) {
            Box(Modifier.size(44.dp).border(BpmPlayBorder, Color.White.copy(alpha = 0.45f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                BpmIcon(BpmSymbol.Book, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}
