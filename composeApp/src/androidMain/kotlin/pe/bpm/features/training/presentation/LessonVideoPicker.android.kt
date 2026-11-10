package pe.bpm.features.training.presentation

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.bpm.features.training.domain.PickedVideo
import pe.bpm.features.training.domain.TrainingVideoRules
import pe.bpm.features.training.domain.VideoSelection
import pe.bpm.features.training.domain.VideoSelectionFailure

/** Android: selector de documentos del sistema (SAF) filtrado a MP4/WebM; lectura acotada al límite. */
@Composable
actual fun rememberLessonVideoPicker(maxBytes: Int, onResult: (VideoSelection) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    val limit = rememberUpdatedState(maxBytes)
    val busy = remember { AtomicBoolean(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            busy.set(false)
            callback.value(VideoSelection.Cancelled)
        } else {
            val max = limit.value
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "video"
                        val mime = (resolver.getType(uri)?.takeIf { it in setOf("video/mp4", "video/webm") }) ?: TrainingVideoRules.mimeForName(name)
                            ?: return@withContext VideoSelection.Failed(VideoSelectionFailure.UNSUPPORTED)
                        val bytes = resolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(64 * 1024)
                            // Nunca se lee más de un byte sobre el límite, aunque los metadatos mientan.
                            while (output.size().toLong() <= max.toLong()) {
                                ensureActive()
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), max.toLong() - output.size() + 1).toInt())
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: return@withContext VideoSelection.Failed(VideoSelectionFailure.READ_FAILED)
                        if (bytes.size > max) VideoSelection.Failed(VideoSelectionFailure.TOO_LARGE)
                        else if (TrainingVideoRules.validate(name, mime, bytes) != null) VideoSelection.Failed(VideoSelectionFailure.UNSUPPORTED)
                        else VideoSelection.Selected(PickedVideo(name, mime, bytes))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        VideoSelection.Failed(VideoSelectionFailure.READ_FAILED)
                    }
                }
                busy.set(false)
                callback.value(result)
            }
        }
    }
    return remember(launcher) {
        {
            if (busy.compareAndSet(false, true)) {
                try {
                    launcher.launch(arrayOf("video/mp4", "video/webm"))
                } catch (_: Exception) {
                    busy.set(false)
                    callback.value(VideoSelection.Failed(VideoSelectionFailure.UNAVAILABLE))
                }
            }
        }
    }
}
