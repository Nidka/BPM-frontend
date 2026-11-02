package pe.bpm.features.media.domain

/** Fotografía elegida expresamente por la persona, mantenida solo en memoria. */
data class PickedPhoto(val name: String, val mimeType: String, val bytes: ByteArray)

sealed interface PhotoSelection {
    data class Selected(val photo: PickedPhoto) : PhotoSelection
    data class Failed(val reason: PhotoSelectionFailure) : PhotoSelection
    data object Cancelled : PhotoSelection
}

enum class PhotoSelectionFailure { TOO_LARGE, UNSUPPORTED, READ_FAILED, UNAVAILABLE }

/** Valida los datos del selector; la API vuelve a verificar y decodificar la imagen. */
internal object EvidencePhotoSelectionRules {
    val supportedMimeTypes = setOf("image/jpeg", "image/png")

    fun validate(name: String, mimeType: String, bytes: ByteArray, maxBytes: Int, allowedMimeTypes: Set<String>): PhotoSelection {
        val failure = when {
            maxBytes <= 0 -> PhotoSelectionFailure.UNAVAILABLE
            bytes.size > maxBytes -> PhotoSelectionFailure.TOO_LARGE
            bytes.isEmpty() -> PhotoSelectionFailure.READ_FAILED
            mimeType !in supportedMimeTypes || mimeType !in allowedMimeTypes -> PhotoSelectionFailure.UNSUPPORTED
            detectedMimeType(bytes) != mimeType -> PhotoSelectionFailure.UNSUPPORTED
            else -> null
        }
        return if (failure != null) PhotoSelection.Failed(failure)
        else PhotoSelection.Selected(PickedPhoto(name, mimeType, bytes))
    }

    // Solo reconoce cabeceras; la comprobación completa de imagen corresponde a la API.
    private fun detectedMimeType(bytes: ByteArray): String? = when {
        bytes.startsWith(0xff, 0xd8, 0xff) -> "image/jpeg"
        bytes.startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
        else -> null
    }

    private fun ByteArray.startsWith(vararg signature: Int): Boolean =
        size >= signature.size && signature.indices.all { this[it] == signature[it].toByte() }
}
