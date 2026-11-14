package pe.bpm.features.training.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.features.training.domain.VideoSelection

/**
 * Reproductor nativo de la lección. Recibe una URL firmada de corta vigencia obtenida justo antes
 * de reproducir; no la guarda. Si la reproducción falla (por ejemplo, la URL venció) llama a
 * [onError] para que la pantalla pida otra.
 *
 * - Android: AndroidX Media3 ExoPlayer.
 * - iOS: AVPlayerViewController (AVKit).
 * - Web (wasmJs): elemento HTML `<video controls>` superpuesto y sincronizado con el área.
 * - JVM (solo pruebas/escritorio de desarrollo): abre el video en el navegador del sistema.
 */
@Composable
expect fun LessonVideoPlayer(url: String, mimeType: String, onError: () -> Unit, modifier: Modifier = Modifier)

/**
 * Abre el selector de archivos del sistema para un video MP4/WebM de una lección. La acción
 * devuelta debe invocarse desde un gesto explícito. El resultado conserva los bytes en memoria
 * y valida tamaño, tipo y firma de formato; el backend vuelve a comprobarlos.
 */
@Composable
expect fun rememberLessonVideoPicker(maxBytes: Int, onResult: (VideoSelection) -> Unit): () -> Unit

/**
 * Reproductor embebido de un video de YouTube de la lección; [embedUrl] siempre es
 * `https://www.youtube-nocookie.com/embed/{videoId}`. Requiere conexión. [watchUrl] es la
 * alternativa para abrir en el navegador. Web: iframe superpuesto (CSP con frame-src mínimo);
 * Android: WebView con JavaScript solo para ese dominio y sin archivos; iOS: WKWebView;
 * JVM (pruebas): abre el navegador.
 */
@Composable
expect fun ExternalLessonVideo(embedUrl: String, watchUrl: String, modifier: Modifier = Modifier)
