package pe.bpm.features.training.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

// ---- Catálogo descargable ---------------------------------------------------------------------

enum class CatalogSync { UPDATED, NOT_MODIFIED }

/** Sincroniza el catálogo offline (`If-None-Match`); un 304 deja intacta la copia local. */
interface TrainingCatalogRepository {
    suspend fun sync(org: String): TrainingResult<CatalogSync>
}

class SyncOfflineCatalog(private val repository: TrainingCatalogRepository) {
    suspend operator fun invoke(org: String): TrainingResult<CatalogSync> = repository.sync(org)
}

// ---- Evaluaciones enviadas sin conexión -------------------------------------------------------

/**
 * Evaluación hecha en el dispositivo. Mientras [result] es null el servidor aún no la calificó
 * (se califica al conectarse); el cliente nunca calcula la calificación.
 */
data class AttemptRecord(
    val attemptId: String,
    val courseId: String,
    val type: AttemptType,
    val queuedAt: Long,
    val result: AttemptSummary?,
) {
    val graded: Boolean get() = result != null
}

interface TrainingAttemptLedger {
    fun observe(courseId: String): Flow<List<AttemptRecord>>
}

object NoTrainingAttemptLedger : TrainingAttemptLedger {
    override fun observe(courseId: String): Flow<List<AttemptRecord>> = emptyFlow()
}

// ---- Descargas de video -----------------------------------------------------------------------

enum class VideoDownloadStatus { NOT_DOWNLOADED, DOWNLOADING, PAUSED, DOWNLOADED }

/** Motivo público por el que una descarga no terminó. */
enum class DownloadIssue { NETWORK, CORRUPT, LIMIT, UNSUPPORTED, TICKET }

data class VideoDownload(
    val mediaId: String,
    val status: VideoDownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val issue: DownloadIssue? = null,
)

/** Estado de las descargas del dispositivo: espacio usado, límite y cada video conocido. */
data class DownloadsState(
    val supported: Boolean = false,
    val usedBytes: Long = 0,
    val limitBytes: Long = TrainingDownloads.DEFAULT_LIMIT_BYTES,
    val videos: Map<String, VideoDownload> = emptyMap(),
    /** Curso cuyas descargas están en curso; null si no hay ninguna. */
    val activeCourseId: String? = null,
) {
    fun of(mediaId: String): VideoDownload? = videos[mediaId]
    fun downloaded(mediaId: String) = videos[mediaId]?.status == VideoDownloadStatus.DOWNLOADED
}

/** Descarga de videos R2 para verlos sin conexión: verificada, reanudable y con límite de espacio. */
interface TrainingDownloads {
    val state: StateFlow<DownloadsState>
    /** Relee del almacén qué hay descargado (al abrir la pantalla). */
    suspend fun refresh()
    /**
     * Asocia el almacén de videos con la cuenta activa: carga su catálogo ya guardado, migra las descargas
     * del formato anterior y muestra solo los videos que ese catálogo incluye.
     */
    suspend fun bind(org: String) = Unit
    /** Desasocia la cuenta (cierre de sesión o cambio de organización); los archivos no se borran. */
    suspend fun unbind() = Unit
    /** Inicia (o reanuda) la descarga de los videos privados del curso; vuelve de inmediato. */
    fun download(org: String, course: TrainingCourse)
    fun cancel()
    suspend fun delete(mediaId: String)
    suspend fun deleteCourse(course: TrainingCourse)
    suspend fun deleteAll()
    suspend fun setLimit(bytes: Long)
    /** Boleto de reproducción local (`file://`) si el video está descargado y verificado. */
    suspend fun localVideo(video: LessonVideo): VideoTicket?

    companion object {
        const val DEFAULT_LIMIT_BYTES = 2L * 1024 * 1024 * 1024
        val LIMIT_CHOICES = listOf(512L * 1024 * 1024, 1024L * 1024 * 1024, DEFAULT_LIMIT_BYTES, 5L * 1024 * 1024 * 1024)
    }
}

/** Sin descargas (pruebas y navegadores): el reproductor siempre usa la URL firmada. */
object NoTrainingDownloads : TrainingDownloads {
    private val empty = MutableStateFlow(DownloadsState()).asStateFlow()
    override val state: StateFlow<DownloadsState> = empty
    override suspend fun refresh() = Unit
    override fun download(org: String, course: TrainingCourse) = Unit
    override fun cancel() = Unit
    override suspend fun delete(mediaId: String) = Unit
    override suspend fun deleteCourse(course: TrainingCourse) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun setLimit(bytes: Long) = Unit
    override suspend fun localVideo(video: LessonVideo): VideoTicket? = null
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${(bytes * 10 / (1024L * 1024 * 1024)) / 10.0} GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
