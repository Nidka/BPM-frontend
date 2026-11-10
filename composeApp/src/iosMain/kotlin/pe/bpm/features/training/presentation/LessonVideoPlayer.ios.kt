@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pe.bpm.features.training.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.coroutines.delay
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

/** iOS: AVPlayerViewController (AVKit) con los controles del sistema, alojado en Compose. */
@Composable
actual fun LessonVideoPlayer(url: String, mimeType: String, onError: () -> Unit, modifier: Modifier) {
    val currentOnError = rememberUpdatedState(onError)
    val controller = remember(url) {
        NSURL.URLWithString(url)?.let { target ->
            AVPlayerViewController().apply {
                player = AVPlayer(uRL = target)
                showsPlaybackControls = true
            }
        }
    }
    if (controller == null) {
        // Una URL que iOS no puede interpretar equivale a un ticket inútil: se pide otro.
        LaunchedEffect(url) { currentOnError.value() }
        return
    }
    // AVPlayerItem no avisa de un fallo inicial (por ejemplo URL vencida) sin KVO: se consulta su estado.
    LaunchedEffect(controller) {
        while (true) {
            delay(1_000)
            val status = controller.player?.currentItem?.status
            if (status == AVPlayerItemStatusFailed) { currentOnError.value(); break }
            if (status == AVPlayerItemStatusReadyToPlay) break
        }
    }
    UIKitViewController(
        factory = { controller },
        modifier = modifier,
        onRelease = { released ->
            released.player?.pause()
            released.player = null
        },
    )
}
