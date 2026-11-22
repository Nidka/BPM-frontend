package pe.bpm.features.training.presentation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Android: ExoPlayer de Media3 con los controles estándar de PlayerView; se libera al salir. */
@Composable
actual fun LessonVideoPlayer(url: String, mimeType: String, onError: () -> Unit, modifier: Modifier, captionsVtt: String) {
    val context = LocalContext.current
    val currentOnError = rememberUpdatedState(onError)
    // Un reproductor por URL: una URL nueva (tras vencer la anterior) crea una instancia limpia.
    val exoPlayer = remember(url, captionsVtt) {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.Builder().setUri(Uri.parse(url))
            // Subtítulos (RF-45): pista WebVTT desde una URI data: (no se escribe en disco), activa por defecto.
            if (captionsVtt.isNotBlank()) {
                val data = "data:text/vtt;base64," + Base64.encodeToString(captionsVtt.encodeToByteArray(), Base64.NO_WRAP)
                item.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(data))
                    .setMimeType(MimeTypes.TEXT_VTT).setLanguage("es").setLabel("Español")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            }
            setMediaItem(item.build())
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    currentOnError.value()
                }
            })
            prepare()
        }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { exoPlayer.pause() }
    AndroidView(
        factory = { viewContext -> PlayerView(viewContext).apply { player = exoPlayer; useController = true } },
        update = { view -> if (view.player !== exoPlayer) view.player = exoPlayer },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}
