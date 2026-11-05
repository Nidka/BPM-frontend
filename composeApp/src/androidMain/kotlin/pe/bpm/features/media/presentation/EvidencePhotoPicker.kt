package pe.bpm.features.media.presentation

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.bpm.features.media.domain.EvidencePhotoSelectionRules
import pe.bpm.features.media.domain.PhotoSelection
import pe.bpm.features.media.domain.PhotoSelectionFailure

@Composable
actual fun rememberEvidencePhotoPicker(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    val currentLimit = rememberUpdatedState(maxBytes)
    val currentTypes = rememberUpdatedState(allowedMimeTypes)
    val busy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            busy.set(false)
            callback.value(PhotoSelection.Cancelled)
        } else {
            val limit = currentLimit.value
            val types = currentTypes.value
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (limit <= 0) return@withContext PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE)
                        val mime = resolver.getType(uri).orEmpty()
                        if (mime !in types || mime !in EvidencePhotoSelectionRules.supportedMimeTypes) {
                            return@withContext PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED)
                        }
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "evidencia"
                        val bytes = resolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            // Nunca se lee más de un byte sobre el límite, incluso con metadatos falsos.
                            while (output.size().toLong() <= limit.toLong()) {
                                ensureActive()
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit.toLong() - output.size() + 1).toInt())
                                if (count < 0) break
                                if (count == 0) return@withContext PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: return@withContext PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
                        EvidencePhotoSelectionRules.validate(name, mime, bytes, limit, types)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
                    }
                }
                busy.set(false)
                callback.value(result)
            }
        }
    }
    return remember(launcher, maxBytes, allowedMimeTypes) {
        {
            if (busy.compareAndSet(false, true)) {
                if (maxBytes <= 0 || allowedMimeTypes.intersect(EvidencePhotoSelectionRules.supportedMimeTypes).isEmpty()) {
                    busy.set(false)
                    callback.value(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE))
                } else try {
                    launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    busy.set(false)
                    callback.value(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE))
                }
            }
        }
    }
}
