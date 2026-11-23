package pe.bpm.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import pe.bpm.core.designsystem.BpmTheme

/** Un ancho de ventana a capturar (en dp); el alto es el de una pantalla típica de ese tipo de dispositivo. */
internal data class ScreenshotSize(val label: String, val widthDp: Int, val heightDp: Int) {
    /** Ancho (dp) ocupado por la navegación lateral: no cuenta como contenido al recortar el espacio vacío. */
    val sideNavDp: Int get() = when { widthDp >= 840 -> 230; else -> 0 }
}

internal val ScreenshotSizes = listOf(
    ScreenshotSize("390", 390, 844),
    ScreenshotSize("768", 768, 1024),
    ScreenshotSize("1280", 1280, 800),
    ScreenshotSize("1920", 1920, 1080),
)

/** Alto (dp) de la variante «completa»: la ventana crece hasta que cabe todo el contenido con desplazamiento. */
internal const val TALL_HEIGHT_DP = 2400

/** Densidad de las capturas: 1.5 mantiene legible el texto sin pasar de ~1 MB por archivo. */
internal const val SHOT_DENSITY = 1.5f

/** Dibuja [content] fuera de pantalla (sin ventana) con el tema de BPM y devuelve la imagen. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun renderOffscreen(widthDp: Int, heightDp: Int, content: @Composable () -> Unit): BufferedImage {
    val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).also { it.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        override val lifecycle get() = registry
    }
    val scene = ImageComposeScene(
        width = (widthDp * SHOT_DENSITY).toInt(), height = (heightDp * SHOT_DENSITY).toInt(),
        density = Density(SHOT_DENSITY), coroutineContext = Dispatchers.Unconfined,
    ) {
        CompositionLocalProvider(LocalLifecycleOwner provides owner) { BpmTheme { content() } }
    }
    try {
        // Varios cuadros con tiempo creciente: deja que terminen las animaciones de entrada y los efectos.
        var nanos = 0L
        repeat(40) { scene.render(nanos); nanos += 100_000_000L }
        val bytes = scene.render(nanos).encodeToData(EncodedImageFormat.PNG)!!.bytes
        return ImageIO.read(ByteArrayInputStream(bytes))
    } finally {
        scene.close()
    }
}

/**
 * Quita de la variante «completa» el hueco vacío entre el final del contenido y la barra inferior (la ventana alta
 * deja un vacío que no existiría con desplazamiento). Devuelve la imagen recortada; no cambia nada si no hay hueco.
 */
internal fun collapseEmptyGap(image: BufferedImage, sideNavDp: Int): BufferedImage {
    val w = image.width
    val h = image.height
    val x0 = (sideNavDp * SHOT_DENSITY).toInt()
    val pixels = image.getRGB(0, 0, w, h, null, 0, w)
    val background = pixels[(h / 2) * w + (w - 3)]
    fun blank(y: Int): Boolean { for (x in x0 until w) if (pixels[y * w + x] != background) return false; return true }
    var bestStart = -1
    var bestLength = 0
    var y = 0
    while (y < h) {
        if (blank(y)) {
            val start = y
            while (y < h && blank(y)) y++
            if (y - start > bestLength) { bestStart = start; bestLength = y - start }
        } else y++
    }
    val keep = (48 * SHOT_DENSITY).toInt()
    if (bestLength <= keep + 48) return image
    val out = BufferedImage(w, h - (bestLength - keep), BufferedImage.TYPE_INT_ARGB)
    val top = pixels.copyOfRange(0, (bestStart + keep) * w)
    val bottom = pixels.copyOfRange((bestStart + bestLength) * w, h * w)
    out.setRGB(0, 0, w, bestStart + keep, top, 0, w)
    out.setRGB(0, bestStart + keep, w, h - bestStart - bestLength, bottom, 0, w)
    return out
}

/** La imagen no es un lienzo vacío: tiene al menos [minColors] colores distintos y varianza de luminancia. */
internal fun looksRendered(image: BufferedImage, minColors: Int = 8): Boolean {
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    val colors = HashSet<Int>()
    var sum = 0.0
    var sumSquares = 0.0
    for (p in pixels) {
        if (colors.size < 4096) colors.add(p)
        val luminance = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
        sum += luminance; sumSquares += luminance * luminance
    }
    val mean = sum / pixels.size
    val variance = sumSquares / pixels.size - mean * mean
    return colors.size >= minColors && variance > 4.0
}

internal fun writePng(image: BufferedImage, file: File) {
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
}

/** Las [images] (una por ancho) lado a lado con su etiqueta, reducidas a como máximo [maxWidth] px de ancho. */
internal fun contactSheet(images: List<Pair<String, BufferedImage>>, maxWidth: Int = 2000): BufferedImage {
    val gap = 40
    val margin = 40
    val labelBand = 56
    val rawWidth = images.sumOf { it.second.width } + gap * (images.size - 1) + margin * 2
    val scale = minOf(1.0, maxWidth.toDouble() / rawWidth)
    fun px(v: Int) = (v * scale).toInt()
    val width = px(rawWidth)
    val height = images.maxOf { px(it.second.height) } + px(margin) * 2 + labelBand
    val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = sheet.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.color = Color(0xD9DDE1)
    g.fillRect(0, 0, width, height)
    var x = px(margin)
    for ((label, image) in images) {
        val w = px(image.width)
        val h = px(image.height)
        g.color = Color(0x20262B)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.drawString("$label dp", x, px(margin) + 24)
        g.drawImage(image.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH), x, px(margin) + labelBand, null)
        x += w + px(gap)
    }
    g.dispose()
    return sheet
}
