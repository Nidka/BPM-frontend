package pe.bpm.features.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import pe.bpm.features.media.domain.EvidencePhotoSelectionRules
import pe.bpm.features.media.domain.PhotoSelection
import pe.bpm.features.media.domain.PhotoSelectionFailure

class PhotoSelectionTest {
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    private val webp = "RIFF1234WEBPVP8 ".encodeToByteArray()
    private val allowed = setOf("image/jpeg", "image/png", "image/webp")

    @Test fun acceptsEachSupportedSignatureAtTheExactLimit() {
        listOf("image/jpeg" to jpeg, "image/png" to png).forEach { (mime, bytes) ->
            val result = EvidencePhotoSelectionRules.validate("foto", mime, bytes, bytes.size, allowed)
            assertEquals(mime, assertIs<PhotoSelection.Selected>(result).photo.mimeType)
        }
    }

    @Test fun configurationCannotEnableWebP() {
        assertEquals(PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED),
            EvidencePhotoSelectionRules.validate("foto.webp", "image/webp", webp, 100, setOf("image/webp")))
    }

    @Test fun rejectsOneByteOverTheConfiguredLimit() {
        assertEquals(PhotoSelection.Failed(PhotoSelectionFailure.TOO_LARGE),
            EvidencePhotoSelectionRules.validate("foto.jpg", "image/jpeg", jpeg, jpeg.size - 1, allowed))
    }

    @Test fun rejectsFormatExcludedByTheServerConfiguration() {
        assertEquals(PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED),
            EvidencePhotoSelectionRules.validate("foto.webp", "image/webp", webp, 100, setOf("image/jpeg", "image/png")))
    }

    @Test fun rejectsForgedMimeAndIncompleteHeaders() {
        listOf(png, byteArrayOf(0xff.toByte(), 0xd8.toByte()), "<svg/>".encodeToByteArray()).forEach { bytes ->
            assertEquals(PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED),
                EvidencePhotoSelectionRules.validate("foto.jpg", "image/jpeg", bytes, 100, allowed))
        }
    }

    @Test fun configurationCannotEnableOtherImageFormats() {
        assertEquals(PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED),
            EvidencePhotoSelectionRules.validate("foto.gif", "image/gif", "GIF89a".encodeToByteArray(), 100, setOf("image/gif")))
    }

    @Test fun rejectsEmptyReadsAndInvalidLimits() {
        assertEquals(PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED),
            EvidencePhotoSelectionRules.validate("foto.jpg", "image/jpeg", byteArrayOf(), 100, allowed))
        assertEquals(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE),
            EvidencePhotoSelectionRules.validate("foto.jpg", "image/jpeg", jpeg, 0, allowed))
    }
}
