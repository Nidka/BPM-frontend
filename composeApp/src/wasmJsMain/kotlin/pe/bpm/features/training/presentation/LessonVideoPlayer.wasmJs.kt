@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity

/**
 * Web: un elemento HTML `<video controls>` superpuesto al área de Compose. Reutiliza los
 * controles, el teclado y los subtítulos nativos del navegador. La posición y el tamaño se
 * sincronizan con el diseño de Compose; cuando el desplazamiento recorta el área, un `clip-path`
 * recorta también el video para que no tape la barra superior ni la navegación.
 * El video sale directamente de la URL firmada de R2 (la CSP lo permite con `media-src`).
 */
@Composable
actual fun LessonVideoPlayer(url: String, mimeType: String, onError: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current.density
    val currentOnError = rememberUpdatedState(onError)
    val element = remember(url) { createVideoElement(url) { currentOnError.value() } }
    DisposableEffect(element) { onDispose { removeVideoElement(element) } }
    Box(modifier.onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow()
        val visible = coordinates.boundsInWindow()
        val width = coordinates.size.width.toDouble()
        val height = coordinates.size.height.toDouble()
        val shown = !visible.isEmpty && width > 0 && height > 0
        val scale = density.toDouble()
        placeVideoElement(
            element,
            origin.x / scale, origin.y / scale, width / scale, height / scale,
            (visible.top - origin.y) / scale,
            (origin.x + width - visible.right) / scale,
            (origin.y + height - visible.bottom) / scale,
            (visible.left - origin.x) / scale,
            shown,
        )
    })
}

@JsFun("""(url, onError) => {
    const video = document.createElement('video');
    video.controls = true;
    video.preload = 'metadata';
    video.playsInline = true;
    video.referrerPolicy = 'no-referrer';
    video.setAttribute('controlsList', 'nodownload');
    video.setAttribute('aria-label', 'Video de la lección');
    video.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;z-index:2000;background:#000;display:none;border-radius:12px;';
    video.addEventListener('error', () => onError());
    video.src = url;
    document.body.appendChild(video);
    return video;
}""")
private external fun createVideoElement(url: String, onError: () -> Unit): JsAny

@JsFun("""(video, x, y, width, height, top, right, bottom, left, shown) => {
    const style = video.style;
    style.display = shown ? 'block' : 'none';
    style.left = x + 'px';
    style.top = y + 'px';
    style.width = width + 'px';
    style.height = height + 'px';
    style.clipPath = 'inset(' + Math.max(0, top) + 'px ' + Math.max(0, right) + 'px ' + Math.max(0, bottom) + 'px ' + Math.max(0, left) + 'px)';
}""")
private external fun placeVideoElement(video: JsAny, x: Double, y: Double, width: Double, height: Double, top: Double, right: Double, bottom: Double, left: Double, shown: Boolean)

@JsFun("(video) => { video.pause(); video.removeAttribute('src'); video.load(); video.remove(); }")
private external fun removeVideoElement(video: JsAny)
