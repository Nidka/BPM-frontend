package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import pe.bpm.resources.*

/**
 * Tipografía del estilo de juego de Personal: **Baloo 2** (títulos, 700/800) y **Nunito** (texto, 600–900), ambas con
 * licencia OFL (ver `composeResources/files/baloo2_OFL.txt` y `nunito_OFL.txt`). Solo la usan las pantallas de
 * Personal; el resto de roles conserva la tipografía del sistema. Medidas tomadas de la maqueta `personal-v2`.
 */
object BpmPlayType {
    /** Familia de títulos (Baloo 2 700/800). */
    val display: FontFamily
        @Composable get() {
            val bold = Font(Res.font.baloo2_bold, FontWeight.Bold)
            val extra = Font(Res.font.baloo2_extrabold, FontWeight.ExtraBold)
            return remember(bold, extra) { FontFamily(bold, extra) }
        }

    /** Familia de texto (Nunito 600/700/800/900). */
    val body: FontFamily
        @Composable get() {
            val semi = Font(Res.font.nunito_semibold, FontWeight.SemiBold)
            val bold = Font(Res.font.nunito_bold, FontWeight.Bold)
            val extra = Font(Res.font.nunito_extrabold, FontWeight.ExtraBold)
            val black = Font(Res.font.nunito_black, FontWeight.Black)
            return remember(semi, bold, extra, black) { FontFamily(semi, bold, extra, black) }
        }

    /** Título de pantalla: Baloo 2 800, 26/28 sp. */
    val h1 @Composable get() = TextStyle(fontFamily = display, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 28.sp)
    /** Título de sección: Nunito 900, 17 sp. */
    val h2 @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 17.sp, lineHeight = 22.sp)
    /** Texto de lectura: Nunito 700, 15 sp. */
    val text @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp)
    /** Apoyo gris (subtítulos): Nunito 600, 14 sp. */
    val sub @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
    /** Etiqueta en mayúsculas: Nunito 900, 13 sp con espaciado de 0,04 em. El texto se escribe ya en mayúsculas. */
    val label @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.52.sp)
    /** Texto de botón grueso: Nunito 900, 16 sp, espaciado 0,04 em, en mayúsculas. */
    val button @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.64.sp)
    /** Título de una fila o tarjeta de misión: Nunito 900, 15,5 sp. */
    val rowTitle @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 15.5.sp, lineHeight = 19.sp)
    /** Detalle de una fila (hora, estado): Nunito 700, 13 sp. */
    val rowDetail @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 17.sp)
    /** Texto de una opción de respuesta: Nunito 800, 15,5 sp. */
    val option @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.ExtraBold, fontSize = 15.5.sp, lineHeight = 20.sp)
    /** Número de teclado de una opción y contadores: Nunito 900, 14 sp. */
    val key @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 14.sp, lineHeight = 18.sp)
    /** Número de un contador (pastilla): Nunito 900, 15 sp. */
    val pill @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 18.sp)
    /** Texto de una ficha de selección: Nunito 900, 15 sp. */
    val tile @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 19.sp)
    /** Texto de la burbuja del tomate: Nunito 700, 14,5 sp. */
    val bubble @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, lineHeight = 20.sp)
    /** Etiqueta de la franja de una ficha de resultado: Nunito 900, 11,5 sp, espaciado 0,06 em. */
    val statLabel @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.69.sp)
    /** Valor de una ficha de resultado: Nunito 900, 19 sp. */
    val statValue @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 19.sp, lineHeight = 22.sp)
    /** Etiqueta de la barra inferior: Nunito 900, 11,5 sp, espaciado 0,04 em, en mayúsculas. */
    val navLabel @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 11.5.sp, lineHeight = 14.sp, letterSpacing = 0.46.sp)
    /** Etiqueta del menú lateral: Nunito 900, 15 sp, espaciado 0,04 em, en mayúsculas. */
    val sideLabel @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.6.sp)
    /** Antetítulo de un módulo: Nunito 900, 12,5 sp, espaciado 0,08 em, en mayúsculas. */
    val kicker @Composable get() = TextStyle(fontFamily = body, fontWeight = FontWeight.Black, fontSize = 12.5.sp, lineHeight = 16.sp, letterSpacing = 1.sp)
    /** Título de un módulo: Baloo 2 800, 20/21 sp. */
    val unitTitle @Composable get() = TextStyle(fontFamily = display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, lineHeight = 21.sp)
    /** Título de la franja de respuesta: Baloo 2 800, 23 sp. */
    val feedbackTitle @Composable get() = TextStyle(fontFamily = display, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, lineHeight = 26.sp)
    /** Resalte en negrita dentro de un texto (la frase del tomate: «2 tareas»): Nunito 800. */
    val strong: androidx.compose.ui.text.SpanStyle get() = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.ExtraBold)
    /** Título de una celebración: Baloo 2 800, 32 sp. */
    val celebration @Composable get() = TextStyle(fontFamily = display, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 32.sp)
}
