package pe.bpm.features.media.presentation

import androidx.compose.runtime.Composable
import pe.bpm.features.media.domain.PhotoSelection

/** Abre el selector nativo solo al invocar la acción devuelta desde un gesto explícito. */
@Composable
expect fun rememberEvidencePhotoPicker(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): () -> Unit
