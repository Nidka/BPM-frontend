package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.BpmSecondaryButton
import pe.bpm.features.training.domain.VideoSelection
import pe.bpm.features.training.domain.VideoSelectionFailure

/** JVM es el host de pruebas y desarrollo: ofrece abrir el video en el navegador del sistema. */
@Composable
actual fun LessonVideoPlayer(url: String, mimeType: String, onError: () -> Unit, modifier: Modifier, captionsVtt: String) {
    val failed = rememberUpdatedState(onError)
    Column(modifier) {
        Text("La reproducción integrada no está disponible en este equipo.", Modifier.padding(bottom = 8.dp))
        BpmSecondaryButton(onClick = {
            val opened = try {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI(url)); true
                } else false
            } catch (_: Exception) { false }
            if (!opened) failed.value()
        }) { Text("Abrir video en el navegador") }
    }
}

/** JVM no incluye selector de archivos de producción. */
@Composable
actual fun rememberLessonVideoPicker(maxBytes: Int, onResult: (VideoSelection) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    return remember { { callback.value(VideoSelection.Failed(VideoSelectionFailure.UNAVAILABLE)) } }
}

/** JVM (pruebas): el video de YouTube se abre en el navegador del sistema. */
@Composable
actual fun ExternalLessonVideo(embedUrl: String, watchUrl: String, modifier: Modifier) {
    Column(modifier) {
        Text("El video de YouTube se abre en el navegador. Requiere conexión.", Modifier.padding(bottom = 8.dp))
        BpmSecondaryButton(onClick = {
            try { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().browse(java.net.URI(watchUrl)) } catch (_: Exception) { }
        }) { Text("Abrir video en el navegador") }
    }
}
