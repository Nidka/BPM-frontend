package pe.bpm.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Burbuja de diálogo del tomate (`.bubble`): fondo blanco, borde de 2 dp, radio 16, texto de 14,5 sp y una punta a la
 * izquierda que apunta a la mascota. La punta se dibuja fuera del borde izquierdo (≈ 7 dp), así que el contenedor debe
 * dejar ese espacio ([BpmMascotRow] lo hace con su separación de 12 dp). Usa [AnnotatedString] para resaltar partes en negrita.
 */
@Composable
fun BpmSpeechBubble(text: AnnotatedString, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        text, style = BpmPlayType.bubble, color = BpmPlayColors.ink,
        modifier = modifier
            .drawWithContent {
                drawContent()
                // Punta: cuadrado de 14 dp girado 45° con borde solo a izquierda y abajo, sobre el borde de la burbuja.
                val side = 14.dp.toPx(); val line = BpmPlayBorder.toPx()
                val topLeft = Offset(-7.dp.toPx(), 20.dp.toPx())
                rotate(45f, pivot = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f)) {
                    drawRect(Color.White, topLeft, Size(side, side))
                    drawRect(BpmPlayColors.line, topLeft, Size(line, side))
                    drawRect(BpmPlayColors.line, Offset(topLeft.x, topLeft.y + side - line), Size(side, line))
                }
            }
            .background(Color.White, shape).border(BpmPlayBorder, BpmPlayColors.line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** Burbuja con texto simple; ver la sobrecarga con [AnnotatedString] para resaltar palabras. */
@Composable
fun BpmSpeechBubble(text: String, modifier: Modifier = Modifier) = BpmSpeechBubble(AnnotatedString(text), modifier)

/**
 * Fila del tomate que habla (`.mascot-row`): la ilustración [image] a la izquierda ([imageWidth], 92 dp por defecto) y
 * su [text] en una [BpmSpeechBubble] a la derecha, centrados en vertical. Mide lo que mide la mayor de las dos partes.
 */
@Composable
fun BpmMascotRow(image: DrawableResource, text: AnnotatedString, modifier: Modifier = Modifier, imageWidth: Dp = 92.dp) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(image), contentDescription = null, modifier = Modifier.width(imageWidth))
        BpmSpeechBubble(text, Modifier.weight(1f))
    }
}

/** Fila del tomate con texto simple. */
@Composable
fun BpmMascotRow(image: DrawableResource, text: String, modifier: Modifier = Modifier, imageWidth: Dp = 92.dp) =
    BpmMascotRow(image, AnnotatedString(text), modifier, imageWidth)
