package pe.bpm.features.training.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pe.bpm.core.offline.*
import pe.bpm.features.training.domain.*

/**
 * Descarga de videos privados (R2) al almacén del dispositivo. Pide la URL firmada justo antes de cada
 * intento, transfiere por bloques, verifica tamaño y SHA-256 del catálogo y respeta el límite de espacio
 * (2 GB por defecto, configurable). Las partes cortadas se reanudan al volver a descargar. Las URL no se guardan.
 */
class OfflineTrainingDownloads(
    private val files: PrivateFileStore,
    private val downloader: VerifiedDownloader,
    private val repository: TrainingRepository,
    private val store: LocalStore,
    private val scope: CoroutineScope,
) : TrainingDownloads {
    private val mutable = MutableStateFlow(DownloadsState(supported = files.durable && files.supportsStreaming))
    override val state: StateFlow<DownloadsState> = mutable.asStateFlow()
    private val lock = Mutex()
    private var job: Job? = null

    private fun ref(video: LessonVideo) = ref(video.mediaId, video.mimeType)

    override suspend fun refresh() {
        if (!mutable.value.supported) return
        val limit = readLimit()
        val infos = files.list().filter { it.ref.startsWith(PREFIX) }
        val known = mutable.value.videos
        val videos = infos.associate { info ->
            val mediaId = info.ref.removePrefix(PREFIX).substringBefore('.')
            val total = known[mediaId]?.totalBytes ?: 0L
            mediaId to when {
                !info.partial -> VideoDownload(mediaId, VideoDownloadStatus.DOWNLOADED, info.sizeBytes, info.sizeBytes)
                known[mediaId]?.status == VideoDownloadStatus.DOWNLOADING -> known.getValue(mediaId)
                else -> VideoDownload(mediaId, VideoDownloadStatus.PAUSED, info.sizeBytes, total, known[mediaId]?.issue)
            }
        }
        // Los avisos de una descarga que no dejó archivo (límite, archivo corrupto) se conservan hasta el siguiente intento.
        val withIssues = videos + known.filter { (id, video) -> id !in videos && video.issue != null }
        mutable.update { it.copy(limitBytes = limit, usedBytes = infos.sumOf { info -> info.sizeBytes }, videos = withIssues) }
    }

    override fun download(org: String, course: TrainingCourse) {
        if (!mutable.value.supported || mutable.value.activeCourseId != null) return
        mutable.update { it.copy(activeCourseId = course.course.id) }
        job = scope.launch {
            try { lock.withLock { run(org, course) } }
            finally { mutable.update { it.copy(activeCourseId = null) }; try { refresh() } catch (_: Exception) { /* informativo */ } }
        }
    }

    private suspend fun run(org: String, course: TrainingCourse) {
        refresh()
        val pending = course.lessons.filter { it.offlineAvailable && it.video?.sha256 != null }
            .filter { !mutable.value.downloaded(it.video!!.mediaId) }
        // El límite se comprueba contra lo que falta bajar de todo el curso, antes de empezar.
        val needed = pending.sumOf { lesson -> lesson.video!!.sizeBytes - (mutable.value.of(lesson.video!!.mediaId)?.downloadedBytes ?: 0L) }
        if (mutable.value.usedBytes + needed > mutable.value.limitBytes) {
            pending.forEach { setIssue(it.video!!, DownloadIssue.LIMIT) }
            return
        }
        for (lesson in pending) {
            val video = lesson.video!!
            setProgress(video, VideoDownloadStatus.DOWNLOADING, mutable.value.of(video.mediaId)?.downloadedBytes ?: 0L, null)
            val outcome = downloader.download(ref(video), video.sizeBytes, video.sha256!!, urlProvider = {
                // Una URL nueva en cada intento; nunca se reutiliza ni se guarda.
                (repository.videoTicket(org, course.course.id, lesson.id) as? TrainingResult.Success)?.value?.url
            }, onProgress = { bytes -> setProgress(video, VideoDownloadStatus.DOWNLOADING, bytes, null) })
            when (outcome) {
                DownloadOutcome.Done -> setProgress(video, VideoDownloadStatus.DOWNLOADED, video.sizeBytes, null)
                DownloadOutcome.Corrupt -> setProgress(video, VideoDownloadStatus.NOT_DOWNLOADED, 0, DownloadIssue.CORRUPT)
                is DownloadOutcome.Failed -> {
                    setIssue(video, when (outcome.reason) {
                        DownloadFailure.NETWORK -> DownloadIssue.NETWORK
                        DownloadFailure.TICKET -> DownloadIssue.TICKET
                        DownloadFailure.UNSUPPORTED -> DownloadIssue.UNSUPPORTED
                    })
                    // Sin red no tiene sentido seguir con los demás videos: lo descargado queda para reanudar.
                    if (outcome.reason != DownloadFailure.TICKET) return
                }
            }
        }
    }

    private fun setProgress(video: LessonVideo, status: VideoDownloadStatus, bytes: Long, issue: DownloadIssue?) {
        mutable.update { it.copy(videos = it.videos + (video.mediaId to VideoDownload(video.mediaId, status, bytes, video.sizeBytes, issue))) }
    }

    private fun setIssue(video: LessonVideo, issue: DownloadIssue) {
        val current = mutable.value.of(video.mediaId)
        val bytes = current?.downloadedBytes ?: 0L
        mutable.update { it.copy(videos = it.videos + (video.mediaId to VideoDownload(video.mediaId,
            if (bytes > 0) VideoDownloadStatus.PAUSED else VideoDownloadStatus.NOT_DOWNLOADED, bytes, video.sizeBytes, issue))) }
    }

    override fun cancel() { job?.cancel() }

    override suspend fun delete(mediaId: String) {
        files.list().filter { it.ref.startsWith("$PREFIX$mediaId") }.forEach { files.delete(it.ref) }
        mutable.update { it.copy(videos = it.videos - mediaId) }
        refresh()
    }

    override suspend fun deleteCourse(course: TrainingCourse) {
        course.lessons.mapNotNull { it.video?.mediaId }.forEach { delete(it) }
    }

    override suspend fun deleteAll() {
        job?.cancel()
        files.list().filter { it.ref.startsWith(PREFIX) }.forEach { files.delete(it.ref) }
        mutable.update { it.copy(videos = emptyMap(), usedBytes = 0, activeCourseId = null) }
    }

    override suspend fun setLimit(bytes: Long) {
        require(bytes > 0)
        store.put(TrainingOfflineStore.DOWNLOAD_LIMIT, "limit", bytes.toString())
        refresh()
    }

    override suspend fun localVideo(video: LessonVideo): VideoTicket? {
        if (!mutable.value.supported) return null
        val name = ref(video)
        if (files.size(name) != video.sizeBytes) return null
        val uri = files.localUri(name) ?: return null
        return VideoTicket(uri, video.mimeType, "local", local = true)
    }

    private suspend fun readLimit(): Long =
        store.get(TrainingOfflineStore.DOWNLOAD_LIMIT, "limit")?.json?.toLongOrNull()?.takeIf { it > 0 } ?: TrainingDownloads.DEFAULT_LIMIT_BYTES

    companion object {
        const val PREFIX = "video-"
        fun ref(mediaId: String, mimeType: String) = "$PREFIX$mediaId" + if (mimeType == "video/webm") ".webm" else ".mp4"
    }
}
