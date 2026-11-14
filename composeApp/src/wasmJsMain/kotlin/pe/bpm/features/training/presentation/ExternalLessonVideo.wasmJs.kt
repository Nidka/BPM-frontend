@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity

/**
 * Web: `<iframe>` superpuesto y sincronizado con el área de Compose, igual que el `<video>`.
 * La CSP del servidor solo permite `frame-src` de youtube-nocookie.com además del acceso de Firebase.
 */
@Composable
actual fun ExternalLessonVideo(embedUrl: String, watchUrl: String, modifier: Modifier) {
    val density = LocalDensity.current.density
    val element = remember(embedUrl) { createYouTubeFrame(embedUrl) }
    DisposableEffect(element) { onDispose { removeYouTubeFrame(element) } }
    Box(modifier.onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow()
        val visible = coordinates.boundsInWindow()
        val width = coordinates.size.width.toDouble()
        val height = coordinates.size.height.toDouble()
        val shown = !visible.isEmpty && width > 0 && height > 0
        val scale = density.toDouble()
        placeYouTubeFrame(element, origin.x / scale, origin.y / scale, width / scale, height / scale,
            (visible.top - origin.y) / scale, (origin.x + width - visible.right) / scale,
            (origin.y + height - visible.bottom) / scale, (visible.left - origin.x) / scale, shown)
    })
}

@JsFun("""(url) => {
    const frame = document.createElement('iframe');
    frame.title = 'Video de la lección (YouTube)';
    frame.referrerPolicy = 'strict-origin-when-cross-origin';
    frame.allow = 'encrypted-media; picture-in-picture; fullscreen';
    frame.allowFullscreen = true;
    frame.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation');
    frame.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:2000;background:#000;display:none;border:0;border-radius:12px;';
    frame.src = url;
    document.body.appendChild(frame);
    return frame;
}""")
private external fun createYouTubeFrame(url: String): JsAny

@JsFun("""(frame, x, y, width, height, top, right, bottom, left, shown) => {
    const style = frame.style;
    style.display = shown ? 'block' : 'none';
    style.left = x + 'px';
    style.top = y + 'px';
    style.width = width + 'px';
    style.height = height + 'px';
    style.clipPath = 'inset(' + Math.max(0, top) + 'px ' + Math.max(0, right) + 'px ' + Math.max(0, bottom) + 'px ' + Math.max(0, left) + 'px)';
}""")
private external fun placeYouTubeFrame(frame: JsAny, x: Double, y: Double, width: Double, height: Double, top: Double, right: Double, bottom: Double, left: Double, shown: Boolean)

@JsFun("(frame) => { frame.src = 'about:blank'; frame.remove(); }")
private external fun removeYouTubeFrame(frame: JsAny)
