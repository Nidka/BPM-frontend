package pe.bpm.features.media.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import pe.bpm.features.media.domain.PhotoSelection
import pe.bpm.features.media.domain.PhotoSelectionFailure

/** JVM es exclusivamente el host de pruebas compartidas. */
@Composable
actual fun rememberEvidencePhotoPicker(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    return remember { { callback.value(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE)) } }
}
