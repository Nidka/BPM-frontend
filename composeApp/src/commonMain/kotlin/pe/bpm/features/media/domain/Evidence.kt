package pe.bpm.features.media.domain

data class EvidenceContext(val organizationId: String, val runId: String, val itemId: String)
enum class EvidenceStatus { PENDING_UPLOAD, AVAILABLE, FAILED, DELETED }
data class EvidenceFile(
    val id: String, val runId: String, val itemId: String, val originalName: String,
    val mimeType: String, val sizeBytes: Int, val status: EvidenceStatus,
    val createdAt: String, val uploadedAt: String? = null,
)
data class EvidencePolicy(val maxBytes: Int, val allowedMimeTypes: Set<String>, val maxFiles: Int, val uploadAvailable: Boolean)
data class EvidenceListing(val files: List<EvidenceFile>, val policy: EvidencePolicy)
data class EvidenceUpload(val id: String, val photo: PickedPhoto, val confirmFirst: Boolean = false)
data class EvidencePreview(val fileId: String, val name: String, val mimeType: String, val bytes: ByteArray)
enum class MediaFailure { SESSION, DENIED, NOT_FOUND, INVALID, TOO_LARGE, UNSUPPORTED, CONTENT_INVALID, CONFLICT, UPLOAD_NOT_READY, RATE_LIMITED, CONNECTION, UNAVAILABLE, INVALID_RESPONSE }
sealed interface MediaResult<out T> {
    data class Success<T>(val value: T) : MediaResult<T>
    /** mustConfirm impide repetir PUT antes de comprobar la misma reserva. */
    data class Failure(val reason: MediaFailure, val mustConfirm: Boolean = false) : MediaResult<Nothing>
}
interface MediaReader {
    suspend fun list(context: EvidenceContext): MediaResult<EvidenceListing>
    suspend fun preview(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidencePreview>
}
interface MediaWriter {
    suspend fun upload(context: EvidenceContext, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile>
    suspend fun remove(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidenceFile>
}
class LoadEvidence(private val reader: MediaReader) {
    suspend operator fun invoke(context: EvidenceContext): MediaResult<EvidenceListing> =
        if (context.valid()) reader.list(context) else MediaResult.Failure(MediaFailure.INVALID)
}
class UploadEvidence(private val writer: MediaWriter) {
    suspend operator fun invoke(context: EvidenceContext, policy: EvidencePolicy, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile> {
        val normalized = input.copy(photo = input.photo.copy(name = input.photo.name.trim()))
        if (!context.valid() || !input.id.isEvidenceId() || !validEvidenceName(normalized.photo.name)) return MediaResult.Failure(MediaFailure.INVALID)
        if (!policy.uploadAvailable) return MediaResult.Failure(MediaFailure.UNAVAILABLE)
        val selection = EvidencePhotoSelectionRules.validate(normalized.photo.name, normalized.photo.mimeType, normalized.photo.bytes, minOf(policy.maxBytes, MAX_EVIDENCE_BYTES), policy.allowedMimeTypes)
        if (selection is PhotoSelection.Failed) return MediaResult.Failure(when (selection.reason) {
            PhotoSelectionFailure.TOO_LARGE -> MediaFailure.TOO_LARGE
            PhotoSelectionFailure.UNSUPPORTED -> MediaFailure.UNSUPPORTED
            PhotoSelectionFailure.READ_FAILED -> MediaFailure.INVALID
            PhotoSelectionFailure.UNAVAILABLE -> MediaFailure.UNAVAILABLE
        })
        return writer.upload(context, normalized, onProgress)
    }
}
class RemoveEvidence(private val writer: MediaWriter) {
    suspend operator fun invoke(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidenceFile> =
        if (context.valid() && file.belongsTo(context) && file.id.isEvidenceId() && file.status != EvidenceStatus.DELETED) writer.remove(context, file)
        else MediaResult.Failure(MediaFailure.INVALID)
}
class LoadEvidencePreview(private val reader: MediaReader) {
    suspend operator fun invoke(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidencePreview> =
        if (context.valid() && file.belongsTo(context) && file.id.isEvidenceId() && file.status == EvidenceStatus.AVAILABLE && file.sizeBytes in 1..MAX_EVIDENCE_BYTES)
            reader.preview(context, file) else MediaResult.Failure(MediaFailure.INVALID)
}

const val MAX_EVIDENCE_BYTES: Int = 25 * 1024 * 1024
internal fun String.isEvidenceId(): Boolean = matches(Regex("[A-Za-z0-9_-]{1,64}"))
internal fun EvidenceContext.valid(): Boolean = listOf(organizationId, runId, itemId).all { it.isEvidenceId() }
internal fun EvidenceFile.belongsTo(context: EvidenceContext): Boolean = runId == context.runId && itemId == context.itemId

/** Cuenta puntos de código como el backend y rechaza Unicode incompleto, rutas y controles. */
internal fun validEvidenceName(value: String): Boolean {
    if (value.isEmpty() || value != value.trim() || value.any { it.isISOControl() || it == '/' || it == '\\' }) return false
    var index = 0
    var count = 0
    while (index < value.length) {
        val char = value[index++]
        if (char in '\uD800'..'\uDBFF') {
            if (index >= value.length || value[index] !in '\uDC00'..'\uDFFF') return false
            index++
        } else if (char in '\uDC00'..'\uDFFF') return false
        count++
        if (count > 200) return false
    }
    return true
}
