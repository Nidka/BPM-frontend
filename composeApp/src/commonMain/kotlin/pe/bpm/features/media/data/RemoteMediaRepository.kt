package pe.bpm.features.media.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.media.domain.*
import kotlin.time.Clock
import kotlin.time.Instant

/** Los tickets viven exclusivamente en la pila de una operación; nunca llegan al estado visual. */
class RemoteMediaRepository(
    private val client: HttpClient,
    private val objects: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : MediaReader, MediaWriter {
    override suspend fun list(context: EvidenceContext): MediaResult<EvidenceListing> = guarded {
        requireContext(context)
        val dto = api<ListEnvelope>(context, "checklist-runs/${context.runId}/items/${context.itemId}/evidence").data
        require(dto.maxBytes in 1..MAX_EVIDENCE_BYTES && dto.maxFiles == 3 && dto.files.size <= dto.maxFiles)
        require(dto.allowedMimeTypes.isNotEmpty() && dto.allowedMimeTypes.distinct().size == dto.allowedMimeTypes.size)
        require(dto.allowedMimeTypes.all { it in EvidencePhotoSelectionRules.supportedMimeTypes })
        val files = dto.files.map { it.domain(context) }
        require(files.distinctBy { it.id }.size == files.size)
        EvidenceListing(files, EvidencePolicy(dto.maxBytes, dto.allowedMimeTypes.toSet(), dto.maxFiles, dto.uploadAvailable))
    }

    override suspend fun upload(context: EvidenceContext, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile> {
        if (!context.valid() || !input.id.isEvidenceId() || !validEvidenceName(input.photo.name) || input.photo.bytes.size !in 1..MAX_EVIDENCE_BYTES) return MediaResult.Failure(MediaFailure.INVALID)
        if (input.confirmFirst) {
            when (val checked = confirm(context, input)) {
                is MediaResult.Success -> return checked
                is MediaResult.Failure -> if (checked.reason != MediaFailure.UPLOAD_NOT_READY) return checked.copy(mustConfirm = checked.reason.retryable())
            }
        }
        val reservation = guarded {
            val sha256 = photoSha256(input.photo.bytes)
            require(sha256.matches(Regex("[0-9a-f]{64}")))
            val body = Json.encodeToJsonElement(UploadDto(input.id, context.runId, context.itemId, input.photo.name, input.photo.mimeType, input.photo.bytes.size, sha256))
            val dto = api<UploadEnvelope>(context, "media/uploads", HttpMethod.Post, body).data
            val file = dto.file.domain(context)
            requireMatches(file, input)
            require(file.status in setOf(EvidenceStatus.PENDING_UPLOAD, EvidenceStatus.AVAILABLE))
            if (file.status == EvidenceStatus.PENDING_UPLOAD) {
                val ticket = dto.upload ?: throw MediaTransportFailure(MediaFailure.UNAVAILABLE)
                ticket.validate(uploadMime = input.photo.mimeType, uploadSize = input.photo.bytes.size, uploadDigest = sha256)
            }
            dto
        }
        if (reservation is MediaResult.Failure) return reservation
        val reserved = (reservation as MediaResult.Success).value
        val file = reserved.file.domain(context)
        if (file.status == EvidenceStatus.AVAILABLE) return MediaResult.Success(file)
        val ticket = reserved.upload!!
        // Incluso una respuesta perdida o 412 comprueba la MISMA reserva antes de otro PUT.
        try {
            currentCoroutineContext().ensureActive()
            objects.preparePut(ticket.url) {
                signedHeaders(ticket)
                setBody(input.photo.bytes)
                onUpload { sent, _ -> onProgress((sent.toDouble() / input.photo.bytes.size).coerceIn(0.0, 1.0).toFloat()) }
            }.execute { response ->
                if (!response.status.isSuccess() && response.status.value != 412) throw MediaTransportFailure(MediaFailure.UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // La comprobación posterior resuelve también los resultados inciertos de almacenamiento.
        }
        return when (val confirmed = confirm(context, input)) {
            is MediaResult.Success -> confirmed
            is MediaResult.Failure -> confirmed.copy(mustConfirm = confirmed.reason.retryable() || confirmed.reason == MediaFailure.UPLOAD_NOT_READY)
        }
    }

    private suspend fun confirm(context: EvidenceContext, input: EvidenceUpload): MediaResult<EvidenceFile> = guarded {
        val file = api<FileEnvelope>(context, "media/${input.id}/confirm", HttpMethod.Post, buildJsonObject {}).data.domain(context)
        requireMatches(file, input)
        require(file.status == EvidenceStatus.AVAILABLE)
        file
    }

    override suspend fun remove(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidenceFile> = guarded {
        requireContext(context)
        require(file.belongsTo(context) && file.id.isEvidenceId())
        val removed = api<FileEnvelope>(context, "media/${file.id}", HttpMethod.Delete).data.domain(context)
        require(removed.id == file.id && removed.status == EvidenceStatus.DELETED)
        removed
    }

    override suspend fun preview(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidencePreview> = guarded {
        requireContext(context)
        require(file.belongsTo(context) && file.id.isEvidenceId() && file.status == EvidenceStatus.AVAILABLE && file.sizeBytes in 1..MAX_EVIDENCE_BYTES)
        val ticket = api<TicketEnvelope>(context, "media/${file.id}/download", HttpMethod.Post, buildJsonObject {}).data
        ticket.validate(uploadMime = null)
        val bytes = objects.prepareGet(ticket.url) { signedHeaders(ticket) }.execute { response ->
            if (!response.status.isSuccess()) throw MediaTransportFailure(MediaFailure.UNAVAILABLE)
            val length = response.contentLength()
            if (length != null && (length > file.sizeBytes || length <= 0)) throw MediaTransportFailure(MediaFailure.INVALID_RESPONSE)
            val contentType = response.contentType()?.withoutParameters()?.toString()
            require(contentType == null || contentType == file.mimeType || contentType == "application/octet-stream")
            val channel = response.bodyAsChannel()
            val chunks = mutableListOf<ByteArray>()
            var count = 0
            val buffer = ByteArray(8192)
            while (count <= file.sizeBytes) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, 0, minOf(buffer.size, file.sizeBytes - count + 1))
                if (read < 0) break
                if (read == 0) continue
                chunks += buffer.copyOf(read)
                count += read
            }
            require(count == file.sizeBytes)
            ByteArray(count).also { output ->
                var offset = 0
                chunks.forEach { it.copyInto(output, offset); offset += it.size }
            }
        }
        require(EvidencePhotoSelectionRules.validate(file.originalName, file.mimeType, bytes, MAX_EVIDENCE_BYTES, setOf(file.mimeType)) is PhotoSelection.Selected)
        EvidencePreview(file.id, file.originalName, file.mimeType, bytes)
    }

    private fun HttpRequestBuilder.signedHeaders(ticket: TicketDto) {
        configureMediaObjectRequest()
        ticket.headers.forEach { (name, values) -> if (includeMediaObjectHeader(name)) values.forEach { header(name, it) } }
        headers.remove(HttpHeaders.Authorization)
        headers.remove(HttpHeaders.Cookie)
    }

    private suspend inline fun <reified T> api(context: EvidenceContext, path: String, verb: HttpMethod = HttpMethod.Get, body: JsonElement? = null): T {
        val access = token()?.takeIf { it.isNotBlank() } ?: throw MediaTransportFailure(MediaFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/organizations/${context.organizationId}/$path") {
            method = verb
            bearerAuth(access)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) onSessionRejected(access)
            val code = if (response.status.value in setOf(409, 422)) {
                response.body<ErrorsEnvelope>().errors.firstOrNull()?.code
            } else null
            throw MediaTransportFailure(when (response.status.value) {
                400 -> MediaFailure.INVALID
                401 -> MediaFailure.SESSION
                403 -> MediaFailure.DENIED
                404 -> MediaFailure.NOT_FOUND
                409 -> if (code == "UPLOAD_NOT_READY") MediaFailure.UPLOAD_NOT_READY else MediaFailure.CONFLICT
                413 -> MediaFailure.TOO_LARGE
                422 -> if (code == "MEDIA_CONTENT_INVALID") MediaFailure.CONTENT_INVALID else MediaFailure.INVALID
                429 -> MediaFailure.RATE_LIMITED
                else -> MediaFailure.UNAVAILABLE
            })
        }
        return response.body()
    }

    private fun requireContext(context: EvidenceContext) { require(context.valid()) }
    private fun requireMatches(file: EvidenceFile, input: EvidenceUpload) {
        require(file.id == input.id && file.originalName == input.photo.name && file.mimeType == input.photo.mimeType && file.sizeBytes == input.photo.bytes.size)
    }
}

private fun MediaFailure.retryable(): Boolean = this in setOf(MediaFailure.CONNECTION, MediaFailure.UNAVAILABLE, MediaFailure.RATE_LIMITED, MediaFailure.INVALID_RESPONSE)
private class MediaTransportFailure(val reason: MediaFailure) : Exception()
private suspend inline fun <T> guarded(block: () -> T): MediaResult<T> = try {
    val result = block()
    currentCoroutineContext().ensureActive()
    MediaResult.Success(result)
} catch (cancelled: CancellationException) { throw cancelled
} catch (error: MediaTransportFailure) { MediaResult.Failure(error.reason)
} catch (error: SessionAccessException) { MediaResult.Failure(if (error.expired) MediaFailure.SESSION else MediaFailure.CONNECTION)
} catch (_: SerializationException) { MediaResult.Failure(MediaFailure.INVALID_RESPONSE)
} catch (_: JsonConvertException) { MediaResult.Failure(MediaFailure.INVALID_RESPONSE)
} catch (_: IllegalArgumentException) { MediaResult.Failure(MediaFailure.INVALID_RESPONSE)
} catch (_: Exception) { MediaResult.Failure(MediaFailure.CONNECTION) }

@Serializable private data class UploadDto(val id: String, val runId: String, val itemId: String, val originalName: String, val mimeType: String, val sizeBytes: Int, val sha256: String)
@Serializable private data class ErrorsEnvelope(val errors: List<MediaErrorDto> = emptyList())
@Serializable private data class MediaErrorDto(val code: String = "")
@Serializable private data class ListEnvelope(val data: ListDto)
@Serializable private data class ListDto(val files: List<FileDto>, val maxBytes: Int, val allowedMimeTypes: List<String>, val maxFiles: Int, val uploadAvailable: Boolean)
@Serializable private data class UploadEnvelope(val data: UploadResponseDto)
@Serializable private data class UploadResponseDto(val file: FileDto, val upload: TicketDto? = null)
@Serializable private data class FileEnvelope(val data: FileDto)
@Serializable private data class TicketEnvelope(val data: TicketDto)
@Serializable private data class FileDto(val id: String, val runId: String, val itemId: String, val originalName: String, val mimeType: String, val sizeBytes: Int,
    val status: String, val createdAt: String, val uploadedAt: String? = null) {
    fun domain(context: EvidenceContext): EvidenceFile {
        require(id.isEvidenceId() && runId == context.runId && itemId == context.itemId)
        require(validEvidenceName(originalName))
        require(mimeType in EvidencePhotoSelectionRules.supportedMimeTypes && sizeBytes in 1..MAX_EVIDENCE_BYTES)
        val state = EvidenceStatus.valueOf(status)
        Instant.parse(createdAt)
        uploadedAt?.let(Instant::parse)
        require(state != EvidenceStatus.AVAILABLE || uploadedAt != null)
        return EvidenceFile(id, runId, itemId, originalName, mimeType, sizeBytes, state, createdAt, uploadedAt)
    }
}

@Serializable private data class TicketDto(val url: String, val headers: Map<String, List<String>> = emptyMap(), val expiresAt: String) {
    fun validate(uploadMime: String?, uploadSize: Int? = null, uploadDigest: String? = null) {
        val target = Url(url)
        require(target.protocol == URLProtocol.HTTPS && target.port == 443 && target.user.isNullOrEmpty() && target.password.isNullOrEmpty() && target.fragment.isEmpty())
        require(target.host.endsWith(".r2.cloudflarestorage.com") && target.host.removeSuffix(".r2.cloudflarestorage.com").matches(Regex("[A-Za-z0-9.-]{1,150}")))
        require(Instant.parse(expiresAt) > Clock.System.now())
        val normalized = headers.mapKeys { it.key.lowercase() }
        require(normalized.size == headers.size && normalized.size <= 10)
        val allowed = if (uploadMime == null) emptySet() else setOf("content-type", "if-none-match", "cache-control", "content-disposition", "x-amz-meta-sha256", "host", "content-length", "x-amz-checksum-sha256", "x-amz-content-sha256")
        require(normalized.keys.all { it in allowed })
        require(normalized.values.all { values -> values.size in 1..4 && values.all { it.length <= 4096 && it.none { c -> c.isISOControl() } } })
        if (uploadMime != null) {
            require(normalized["content-type"] == listOf(uploadMime))
            require(normalized["if-none-match"] == listOf("*"))
            normalized["cache-control"]?.let { require(it == listOf("private, no-store")) }
            normalized["content-disposition"]?.let { require(it == listOf("attachment")) }
            normalized["x-amz-meta-sha256"]?.let { require(it == listOf(uploadDigest)) }
            normalized["host"]?.let { require(it == listOf(target.host)) }
            normalized["content-length"]?.let { require(it == listOf(uploadSize.toString())) }
        }
    }
}
