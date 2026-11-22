package pe.bpm.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Forma de una pieza del confeti. */
private enum class PieceShape { Square, Dot, Star }

/** Una pieza: velocidad inicial (fracción del ancho y del alto por segundo), giro, tamaño (dp), color y forma. */
private class ConfettiPiece(val vx: Float, val vy: Float, val spin: Float, val size: Float, val color: Color, val shape: PieceShape)

/** Estrella de cinco puntas de radio 1 centrada en el origen; se escala al dibujar. */
private val StarPath = Path().apply {
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) 1f else 0.45f
        val angle = -PI / 2 + i * PI / 5
        val x = (cos(angle) * radius).toFloat(); val y = (sin(angle) * radius).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** Duración del estallido: corta y única (WCAG 2.2.2: nada que se repita ni dure más de 5 s). */
private const val BURST_MILLIS = 1600

/**
 * Estallido de estrellas y confeti ligero (Canvas) que sale de la parte alta del contenedor, sube un poco y cae
 * desvaneciéndose en 1,6 s. Ocurre una sola vez al entrar a la composición; con «reducir movimiento» no se dibuja nada.
 * El progreso se lee en la fase de dibujo (sin recomponer) y la capa no captura toques: se coloca con `matchParentSize()`.
 */
@Composable
fun BpmConfettiBurst(modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    if (reduce) return
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(BURST_MILLIS, easing = LinearEasing)) }
    val colors = remember { listOf(BpmPlayColors.gold.face, BpmPlayColors.streak.face, BpmPlayColors.correct.face, BpmPlayColors.action.face, BpmPlayColors.unitPurple, BpmPlayColors.unitBlue) }
    val pieces = remember(colors) {
        val random = Random(7)
        List(30) { index ->
            val angle = -PI / 2 + (random.nextFloat() - 0.5f) * PI * 0.9
            val speed = 0.55f + random.nextFloat() * 0.75f
            ConfettiPiece(
                vx = (cos(angle) * speed).toFloat() * 0.9f, vy = (sin(angle) * speed).toFloat() * 0.9f,
                spin = (random.nextFloat() - 0.5f) * 540f, size = 6f + random.nextFloat() * 6f,
                color = colors[index % colors.size], shape = PieceShape.entries[index % PieceShape.entries.size],
            )
        }
    }
    Canvas(modifier) {
        val t = progress.value
        if (t <= 0f || t >= 1f) return@Canvas
        val seconds = t * BURST_MILLIS / 1000f
        val fade = (1f - ((t - 0.65f) / 0.35f).coerceIn(0f, 1f))
        val origin = Offset(size.width / 2f, size.height * 0.22f)
        val gravity = 1.6f
        pieces.forEach { piece ->
            val x = origin.x + piece.vx * seconds * size.width * 0.9f
            val y = origin.y + (piece.vy * seconds + 0.5f * gravity * seconds * seconds) * size.height * 0.5f
            val side = piece.size.dp.toPx()
            val color = piece.color.copy(alpha = fade)
            translate(x, y) {
                rotate(piece.spin * t, pivot = Offset.Zero) {
                    when (piece.shape) {
                        PieceShape.Square -> drawRect(color, Offset(-side / 2f, -side / 2f), Size(side, side * 0.6f))
                        PieceShape.Dot -> drawCircle(color, side / 2f)
                        PieceShape.Star -> scale(side * 0.9f, pivot = Offset.Zero) { drawPath(StarPath, color) }
                    }
                }
            }
        }
    }
}
