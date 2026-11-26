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

/**
 * Abre la cámara del dispositivo para tomar la foto en el momento; devuelve null donde no hay una cámara propia
 * (en el navegador y en iOS el selector ya ofrece «Tomar foto»). Misma validación y resultado que el selector.
 */
@Composable
expect fun rememberEvidenceCamera(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): (() -> Unit)?
