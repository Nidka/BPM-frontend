package pe.bpm.features.media

import kotlinx.coroutines.test.runTest
import pe.bpm.features.media.domain.*
import kotlin.test.*

class EvidenceValidationTest {
    private val context = EvidenceContext("org", "run", "item")
    private val policy = EvidencePolicy(100, setOf("image/jpeg"), 3, true)
    private val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())

    @Test fun normalizesOuterSpacesAndCountsUnicodeCharactersWithoutTruncating() = runTest {
        val writer = RecordingWriter()
        val send = UploadEvidence(writer)
        send(context, policy, EvidenceUpload("valid-id", PickedPhoto(" Foto.jpg ", "image/jpeg", bytes))) {}
        assertEquals("Foto.jpg", writer.names.single())
        val unicode = "\uD83D\uDCF7".repeat(200)
        send(context, policy, EvidenceUpload("valid-id", PickedPhoto(unicode, "image/jpeg", bytes))) {}
        assertEquals(unicode, writer.names.last())
    }

    @Test fun rejectsLongNamesPathsControlsAndMalformedUnicodeBeforeTransport() = runTest {
        val writer = RecordingWriter()
        val send = UploadEvidence(writer)
        for (name in listOf("a".repeat(201), "foto/privada.jpg", "foto\\privada.jpg", "foto\u0001.jpg", "\uD800.jpg")) {
            val result = send(context, policy, EvidenceUpload("valid-id", PickedPhoto(name, "image/jpeg", bytes))) {}
            assertEquals(MediaFailure.INVALID, assertIs<MediaResult.Failure>(result).reason)
        }
        assertTrue(writer.names.isEmpty())
    }

    private class RecordingWriter : MediaWriter {
        val names = mutableListOf<String>()
        override suspend fun upload(context: EvidenceContext, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile> {
            names += input.photo.name
            return MediaResult.Failure(MediaFailure.CONNECTION)
        }
        override suspend fun remove(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidenceFile> = error("No corresponde")
    }
}
