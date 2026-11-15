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
 * Descarga de videos privados (R2) al almacén de videos del dispositivo, separado de los datos personales
 * ([files] no se borra al cerrar sesión). Cada archivo se identifica por `mediaId` y su SHA-256 del catálogo.
 * Pide la URL firmada justo antes de cada intento, transfiere por bloques, verifica tamaño y SHA-256 y
 * respeta el límite de espacio (2 GB por defecto, configurable): si falta espacio se liberan antes los videos
 * que no pertenecen al catálogo de la cuenta activa. Las partes cortadas se reanudan al volver a descargar.
 *
 * Un video guardado solo se reproduce y se muestra si el catálogo ya cargado de la cuenta activa incluye su
 * `mediaId` (y el mismo SHA-256): así otra persona que use el celular no ve videos que no le corresponden.
 * Las URL no se guardan.
 */
class OfflineTrainingDownloads(
    private val files: PrivateFileStore,
    private val downloader: VerifiedDownloader,
    private val repository: TrainingRepository,
    private val store: LocalStore,
    private val scope: CoroutineScope,
    /** Videos del catálogo ya guardado de la cuenta activa en la organización dada. */
    private val catalog: suspend (String) -> List<LessonVideo> = { emptyList() },
    /** Almacén personal anterior: sus descargas se migran a [files] cuando el catálogo permite identificarlas. */
    private val legacy: PrivateFileStore? = null,
) : TrainingDownloads {
    private val mutable = MutableStateFlow(DownloadsState(supported = files.durable && files.supportsStreaming))
    override val state: StateFlow<DownloadsState> = mutable.asStateFlow()
    private val lock = Mutex()
    private var job: Job? = null
    /** mediaId a SHA-256 de los videos que la cuenta activa puede ver; vacío hasta cargar su catálogo. */
    private val allowedVideos = MutableStateFlow<Map<String, String>>(emptyMap())
    private val allowed get() = allowedVideos.value

    private fun ref(video: LessonVideo) = ref(video.mediaId, video.mimeType, video.sha256)

    override suspend fun bind(org: String) {
        if (!mutable.value.supported) return
        val videos = try { catalog(org) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        allowedVideos.value = videos.mapNotNull { video -> video.sha256?.let { video.mediaId to it } }.toMap()
        try { migrateLegacy(videos) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* se reintenta al abrir otro curso */ }
        refresh()
    }

    override suspend fun unbind() {
        job?.cancel()
        allowedVideos.value = emptyMap()
        mutable.update { it.copy(videos = emptyMap(), activeCourseId = null) }
    }

    /**
     * Mueve al almacén de videos las descargas del formato anterior (`video-<mediaId>.mp4` en el almacén
     * personal) que el catálogo permite identificar y cuyo tamaño coincide; las demás se descartan porque
     * no se puede comprobar qué video son.
     */
    private suspend fun migrateLegacy(videos: List<LessonVideo>) {
        val old = legacy ?: return
        val byId = videos.filter { it.sha256 != null }.associateBy { it.mediaId }
        for (info in old.list().filter { it.ref.startsWith(PREFIX) }) {
            val mediaId = info.ref.removePrefix(PREFIX).substringBefore('.')
            val video = byId[mediaId]
            val moved = !info.partial && video != null && info.sizeBytes == video.sizeBytes &&
                files.size(ref(video)) == null && old.moveTo(info.ref, files, ref(video))
            if (!moved) old.delete(info.ref)
        }
    }

    override suspend fun refresh() {
        if (!mutable.value.supported) return
        val limit = readLimit()
        val infos = files.list().filter { it.ref.startsWith(PREFIX) }
        val known = mutable.value.videos
        // Solo se muestran los videos del catálogo de esta cuenta; el espacio usado cuenta todo el dispositivo.
        val visible = infos.mapNotNull { info -> parse(info.ref)?.takeIf { (id, sha16) -> allowed[id]?.startsWith(sha16) == true }?.let { info to it.first } }
        val videos = visible.associate { (info, mediaId) ->
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
        // El curso que se descarga viene del catálogo de la cuenta activa: sus videos quedan autorizados.
        allowedVideos.update { it + course.lessons.mapNotNull { lesson -> lesson.video?.let { video -> video.sha256?.let { sha -> video.mediaId to sha } } } }
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
        // Si falta espacio se liberan primero los videos que no son del catálogo de esta cuenta (de otras cuentas o retirados).
        if (mutable.value.usedBytes + needed > mutable.value.limitBytes) {
            freeForeignVideos(mutable.value.usedBytes + needed - mutable.value.limitBytes)
            refresh()
        }
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

    /** Borra videos ajenos al catálogo activo, los más grandes primero, hasta liberar [bytes] o agotarlos. */
    private suspend fun freeForeignVideos(bytes: Long) {
        var freed = 0L
        val foreign = files.list().filter { info -> info.ref.startsWith(PREFIX) && parse(info.ref)?.let { (id, sha16) -> allowed[id]?.startsWith(sha16) == true } != true }
        for (info in foreign.sortedByDescending { it.sizeBytes }) {
            if (freed >= bytes) break
            files.delete(info.ref)
            freed += info.sizeBytes
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

    /** «Borrar descargas»: vacía el almacén de videos del dispositivo, también los de otras cuentas. */
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
        // Sin catálogo propio cargado que incluya este video (con su SHA-256), no se reproduce el archivo local.
        val sha = video.sha256 ?: return null
        if (allowed[video.mediaId] != sha) return null
        val name = ref(video)
        if (files.size(name) != video.sizeBytes) return null
        val uri = files.localUri(name) ?: return null
        return VideoTicket(uri, video.mimeType, "local", local = true)
    }

    private suspend fun readLimit(): Long =
        store.get(TrainingOfflineStore.DOWNLOAD_LIMIT, "limit")?.json?.toLongOrNull()?.takeIf { it > 0 } ?: TrainingDownloads.DEFAULT_LIMIT_BYTES

    companion object {
        const val PREFIX = "video-"

        /** `video-<mediaId>-<16 primeros hex del SHA-256>.<ext>`: el nombre identifica el contenido, no a la cuenta. */
        fun ref(mediaId: String, mimeType: String, sha256: String?) =
            "$PREFIX$mediaId" + (sha256?.take(16)?.lowercase()?.let { "-$it" } ?: "") + if (mimeType == "video/webm") ".webm" else ".mp4"

        private val named = Regex("^video-(.+)-([0-9a-f]{16})\\.(?:mp4|webm)$")

        /** (mediaId, prefijo del SHA-256) de un archivo del almacén de videos; null si no sigue el formato. */
        internal fun parse(ref: String): Pair<String, String>? =
            named.matchEntire(ref.removeSuffix(".part"))?.let { it.groupValues[1] to it.groupValues[2] }
    }
}
