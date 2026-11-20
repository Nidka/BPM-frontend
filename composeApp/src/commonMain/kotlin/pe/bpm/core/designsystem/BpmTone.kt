package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tono semántico de chips, filas y avisos pequeños. Cada tono define una pareja fondo/texto con contraste
 * suficiente; el significado nunca depende solo del color (siempre va acompañado de texto o icono).
 */
enum class BpmTone { Neutral, Info, Success, Warning, Danger }

/** Gris neutro del sistema para fondos de contenedores y chips sin énfasis. */
internal val BpmNeutralSurface = Color(0xFFEEF1F1)

/** Pareja (fondo, texto) del tono, tomada de [BpmTokens]. */
@Composable
fun BpmTone.colors(): Pair<Color, Color> = when (this) {
    BpmTone.Neutral -> BpmNeutralSurface to BpmTokens.muted
    BpmTone.Info -> BpmTokens.soft to BpmTokens.primary
    BpmTone.Success -> BpmTokens.successSurface to BpmTokens.successText
    BpmTone.Warning -> BpmTokens.warningSurface to BpmTokens.warningText
    BpmTone.Danger -> BpmTokens.dangerSurface to BpmTokens.dangerText
}
