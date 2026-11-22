package pe.bpm.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/** Contraste WCAG 1.4.3 de los textos pequeños de las fichas y contadores del estilo de juego (mínimo 4,5:1). */
class PlayContrastTest {
    private fun channel(value: Float): Double = value.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    private fun luminance(color: Color) = 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> if (la >= lb) la to lb else lb to la } }
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertReadable(name: String, text: Color, background: Color) {
        val ratio = contrast(text, background)
        assertTrue(ratio >= 4.5, "$name: contraste ${"%.2f".format(ratio)} < 4,5")
    }

    @Test fun chipsReadOnTheirSoftBackground() {
        BpmPlayTone.entries.forEach { tone ->
            assertReadable("ficha $tone", BpmPlayColors.textOn(tone), BpmPlayColors.shade(tone).soft)
        }
    }

    @Test fun counterValuesReadOnWhite() {
        BpmPlayTone.entries.forEach { tone -> assertReadable("contador $tone", BpmPlayColors.textOn(tone), Color.White) }
    }

    @Test fun resultTileLabelsReadOnTheirBand() {
        BpmPlayTone.entries.filter { it != BpmPlayTone.Gold }.forEach { tone ->
            assertReadable("franja $tone", Color.White, BpmPlayColors.shade(tone).shadow)
        }
        assertReadable("franja dorada", BpmPlayColors.goldBandText, BpmPlayColors.gold.face)
    }

    @Test fun supportTextReadsOnWhiteAndNotice() {
        assertReadable("texto de apoyo", BpmPlayColors.muted, Color.White)
        assertReadable("texto principal", BpmPlayColors.ink, BpmPlayColors.gold.soft)
        assertReadable("aviso", BpmPlayColors.goldText, BpmPlayColors.gold.soft)
    }
}
