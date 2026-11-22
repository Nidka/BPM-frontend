@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pe.bpm.features.training.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.coroutines.delay
// Comodín: currentItem y pause vienen de categorías de AVPlayer y requieren importarse.
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSURL
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.NSTextAlignmentCenter
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UILabel
import pe.bpm.features.training.domain.WebVtt

/** iOS: AVPlayerViewController (AVKit) con los controles del sistema, alojado en Compose. */
@Composable
actual fun LessonVideoPlayer(url: String, mimeType: String, onError: () -> Unit, modifier: Modifier, captionsVtt: String) {
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
    // Subtítulos (RF-45): AVPlayer no admite pistas WebVTT externas en MP4; se dibujan en contentOverlayView y se
    // actualizan con la posición del video cuatro veces por segundo.
    val cues = remember(captionsVtt) { WebVtt.parse(captionsVtt) }
    if (cues.isNotEmpty()) {
        val label = remember(controller) { captionLabel(controller) }
        LaunchedEffect(controller, cues) {
            while (true) {
                val seconds = controller.player?.currentTime()?.let { CMTimeGetSeconds(it) } ?: 0.0
                val cue = WebVtt.activeCue(cues, (seconds * 1000).toLong())
                label.text = cue?.text?.let { " $it " }
                label.hidden = cue == null
                delay(250)
            }
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

/** Etiqueta de subtítulos centrada al pie del video, con fondo oscuro para contraste sobre cualquier imagen. */
private fun captionLabel(controller: AVPlayerViewController): UILabel {
    val label = UILabel().apply {
        numberOfLines = 0
        textAlignment = NSTextAlignmentCenter
        textColor = UIColor.whiteColor
        backgroundColor = UIColor.blackColor.colorWithAlphaComponent(0.7)
        font = UIFont.systemFontOfSize(17.0)
        hidden = true
        translatesAutoresizingMaskIntoConstraints = false
    }
    val overlay = controller.contentOverlayView ?: return label
    overlay.addSubview(label)
    NSLayoutConstraint.activateConstraints(listOf(
        label.centerXAnchor.constraintEqualToAnchor(overlay.centerXAnchor),
        label.bottomAnchor.constraintEqualToAnchor(overlay.bottomAnchor, constant = -24.0),
        label.leadingAnchor.constraintGreaterThanOrEqualToAnchor(overlay.leadingAnchor, constant = 16.0),
        label.trailingAnchor.constraintLessThanOrEqualToAnchor(overlay.trailingAnchor, constant = -16.0),
    ))
    return label
}
