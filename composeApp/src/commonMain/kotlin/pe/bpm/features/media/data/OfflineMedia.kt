package pe.bpm.features.media.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pe.bpm.core.offline.ConnectivityMonitor
import pe.bpm.core.offline.OutboxDraft
import pe.bpm.core.offline.OutboxHandler
import pe.bpm.core.offline.OutboxOperation
import pe.bpm.core.offline.Outbox
import pe.bpm.core.offline.PHOTO_KIND
import pe.bpm.core.offline.PrivateFileStore
import pe.bpm.core.offline.SendOutcome
import pe.bpm.features.media.domain.*
import kotlin.time.Clock

@Serializable
internal data class PhotoPayload(
    val organizationId: String, val runId: String = "", val itemId: String = "", val incidentId: String? = null,
    val actionId: String? = null, val stage: String? = null, val cleaningExecutionId: String? = null,
    val uploadId: String, val name: String, val mimeType: String, val sizeBytes: Int, val fileRef: String,
) {
    fun context() = EvidenceContext(organizationId, runId, itemId, incidentId, actionId, stage?.let(EvidenceStage::valueOf), cleaningExecutionId)
}

/** Registro de la Outbox al que pertenece la foto: su alta pendiente ordena el envío. */
private fun EvidenceContext.entityId() = cleaningExecutionId ?: incidentId ?: runId

/**
 * Decora el escritor de evidencias: sin red (o con un fallo de conexión, o si su registro padre aún no
 * existe en el servidor) copia la foto al almacenamiento privado de la app y la deja en la Outbox.
 * El envío (reserva, PUT firmado y confirmación) ocurre después con [PhotoOutboxHandler]; la URL
 * firmada nunca se guarda. Devuelve un archivo PENDING_UPLOAD que la interfaz muestra como pendiente.
 * Una foto pendiente no cuenta como evidencia confirmada: finalizar un control que la exige espera su envío.
 */
class OfflineMediaWriter(
    private val delegate: MediaWriter,
    private val files: PrivateFileStore,
    private val outbox: Outbox,
    private val connectivity: ConnectivityMonitor,
    private val newId: () -> String,
    private val clock: () -> Long,
) : MediaWriter {
    override suspend fun upload(context: EvidenceContext, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile> {
        if (!files.durable) return delegate.upload(context, input, onProgress)
        val entity = context.entityId()
        if (!connectivity.online.value || outbox.hasUnsentForEntity(entity)) return queue(context, input)
        val result = delegate.upload(context, input, onProgress)
        if (result is MediaResult.Failure && result.reason == MediaFailure.CONNECTION) return queue(context, input)
        return result
    }

    override suspend fun remove(context: EvidenceContext, file: EvidenceFile) = delegate.remove(context, file)

    private suspend fun queue(context: EvidenceContext, input: EvidenceUpload): MediaResult<EvidenceFile> {
        try {
            files.save(input.id, input.photo.bytes)
            val payload = PhotoPayload(context.organizationId, context.runId, context.itemId, context.incidentId, context.actionId,
                context.stage?.name, context.cleaningExecutionId, input.id, input.photo.name, input.photo.mimeType, input.photo.bytes.size, input.id)
            outbox.enqueue(OutboxDraft(newId(), PHOTO_KIND, context.organizationId, context.entityId(), "photo-${input.id}",
                Json.encodeToString(PhotoPayload.serializer(), payload), clock()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Sin espacio o sin base local: se informa como fallo de conexión y la foto sigue en memoria.
            return MediaResult.Failure(MediaFailure.CONNECTION)
        }
        return MediaResult.Success(EvidenceFile(input.id, context.runId, context.itemId, input.photo.name, input.photo.mimeType,
            input.photo.bytes.size, EvidenceStatus.PENDING_UPLOAD, Clock.System.now().toString(), null, context.incidentId,
            context.actionId, context.stage, context.cleaningExecutionId))
    }
}

/** Envía las fotos de la Outbox con el flujo habitual de reserva, PUT firmado y confirmación. */
class PhotoOutboxHandler(private val writer: MediaWriter, private val files: PrivateFileStore) : OutboxHandler {
    override suspend fun send(operation: OutboxOperation): SendOutcome {
        val payload = try { Json.decodeFromString(PhotoPayload.serializer(), operation.payload) } catch (_: Exception) {
            return SendOutcome.Rejected("La foto guardada está dañada y no puede enviarse.", 0)
        }
        val bytes = files.read(payload.fileRef) ?: return SendOutcome.Rejected("La foto ya no está en este dispositivo.", 0)
        // Tras un intento fallido pudo existir una reserva: se confirma la misma antes de repetir el PUT.
        val upload = EvidenceUpload(payload.uploadId, PickedPhoto(payload.name, payload.mimeType, bytes), confirmFirst = operation.attempts > 0)
        return when (val result = writer.upload(payload.context(), upload) {}) {
            is MediaResult.Success -> SendOutcome.Sent()
            is MediaResult.Failure -> when (result.reason) {
                MediaFailure.CONNECTION, MediaFailure.UNAVAILABLE, MediaFailure.INVALID_RESPONSE, MediaFailure.UPLOAD_NOT_READY ->
                    SendOutcome.Retry("No se pudo enviar la foto todavía.", network = result.reason == MediaFailure.CONNECTION)
                MediaFailure.RATE_LIMITED -> SendOutcome.Retry("El servidor pidió esperar un momento.", 429)
                MediaFailure.SESSION -> SendOutcome.Retry("Falta renovar la sesión.", 401, network = true, countAttempt = false)
                MediaFailure.CONFLICT -> SendOutcome.Conflict("La foto ya no coincide con el registro. Descártala y vuelve a tomarla.")
                MediaFailure.DENIED -> SendOutcome.Rejected("No tienes permiso para agregar esta foto.", 403)
                MediaFailure.NOT_FOUND -> SendOutcome.Rejected("El registro de esta foto ya no existe.", 404)
                MediaFailure.TOO_LARGE, MediaFailure.UNSUPPORTED, MediaFailure.CONTENT_INVALID, MediaFailure.INVALID ->
                    SendOutcome.Rejected("El servidor no aceptó la foto. Descártala y toma otra.", 422)
            }
        }
    }

    override suspend fun onSent(operation: OutboxOperation) {
        val payload = try { Json.decodeFromString(PhotoPayload.serializer(), operation.payload) } catch (_: Exception) { return }
        files.delete(payload.fileRef)
    }
}
