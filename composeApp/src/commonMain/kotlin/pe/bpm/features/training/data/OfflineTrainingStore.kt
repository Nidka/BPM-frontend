package pe.bpm.features.training.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import pe.bpm.core.offline.LocalStore
import pe.bpm.core.offline.OfflineJson
import pe.bpm.features.training.domain.*

/**
 * Datos de capacitación guardados en el dispositivo (por cuenta, mediante [LocalStore]): catálogo
 * descargable, avance local, retroalimentación del modo estudio vista con red y evaluaciones
 * enviadas. Nunca guarda URL firmadas, tokens ni la respuesta correcta de una pregunta que no se
 * hubiera consultado antes con conexión.
 */
class TrainingOfflineStore(private val store: LocalStore, private val clock: () -> Long) {

    // ---- Catálogo ----------------------------------------------------------------------------

    internal suspend fun catalogMeta(org: String): CatalogMeta? =
        store.get(META, org)?.let { runCatching { OfflineJson.decodeFromString(CatalogMeta.serializer(), it.json) }.getOrNull() }

    /** Reemplaza el catálogo de [org]: guarda cada curso, retira los que ya no están asignados y recuerda el etag. */
    internal suspend fun saveCatalog(org: String, fresh: CatalogFetch.Fresh) {
        val previous = catalogMeta(org)?.courseIds.orEmpty()
        fresh.courses.forEach { (id, json) -> store.put(CATALOG, key(org, id), json) }
        (previous - fresh.courses.map { it.first }.toSet()).forEach { store.remove(CATALOG, key(org, it)) }
        store.put(META, org, OfflineJson.encodeToString(CatalogMeta.serializer(),
            CatalogMeta(fresh.etag, fresh.generatedAt, fresh.courses.map { it.first }, clock())))
    }

    /** Marca el catálogo como vigente tras un 304 sin tocar su contenido. */
    internal suspend fun touchCatalog(org: String) {
        val meta = catalogMeta(org) ?: return
        store.put(META, org, OfflineJson.encodeToString(CatalogMeta.serializer(), meta.copy(syncedAt = clock())))
    }

    /** Curso del catálogo guardado, con el avance local aplicado; null si no se descargó el catálogo con ese curso. */
    suspend fun cachedCourse(org: String, courseId: String): TrainingCourse? {
        val record = store.get(CATALOG, key(org, courseId)) ?: return null
        val course = runCatching { OfflineJson.decodeFromString(OfflineCourseDto.serializer(), record.json).domain() }.getOrNull() ?: return null
        val progress = progress(courseId) ?: course.progress
        val syncedAt = catalogMeta(org)?.syncedAt ?: record.updatedAt
        return course.copy(progress = progress, cachedAt = syncedAt, bank = bank(courseId))
    }

    // ---- Banco oficial -----------------------------------------------------------------------

    /** Recuerda que el curso usa el banco oficial: sin conexión no se rinde una evaluación clásica con sus 20 preguntas. */
    suspend fun saveBank(courseId: String, bank: QuestionBankInfo?) {
        if (bank == null) { store.remove(BANK, courseId); return }
        store.put(BANK, courseId, OfflineJson.encodeToString(CachedBankInfo.serializer(), CachedBankInfo(bank.version, bank.itemsPerAttempt, bank.poolSize)))
    }

    suspend fun bank(courseId: String): QuestionBankInfo? =
        store.get(BANK, courseId)?.let { runCatching { OfflineJson.decodeFromString(CachedBankInfo.serializer(), it.json) }.getOrNull() }
            ?.let { QuestionBankInfo(it.version, it.itemsPerAttempt, it.poolSize) }

    /** Videos de los cursos del catálogo guardado de [org]; vacío si aún no se cargó el catálogo. */
    suspend fun catalogVideos(org: String): List<LessonVideo> =
        catalogMeta(org)?.courseIds.orEmpty().mapNotNull { cachedCourse(org, it) }.flatMap { course -> course.lessons.mapNotNull { it.video } }

    // ---- Avance ------------------------------------------------------------------------------

    suspend fun progress(courseId: String): TrainingProgress? =
        store.get(PROGRESS, courseId)?.let { runCatching { OfflineJson.decodeFromString(ProgressDto.serializer(), it.json).domain() }.getOrNull() }

    suspend fun saveProgress(courseId: String, progress: TrainingProgress) {
        val dto = ProgressDto(progress.status.name, progress.lastLessonId, progress.bestScore, progress.experiencePoints, progress.completedAt,
            progress.completedLessonIds, progress.completedLessons, progress.totalLessons, progress.percent)
        store.put(PROGRESS, courseId, OfflineJson.encodeToString(ProgressDto.serializer(), dto))
    }

    suspend fun clearProgress(courseId: String) = store.remove(PROGRESS, courseId)

    // ---- Modo estudio ------------------------------------------------------------------------

    internal suspend fun studyFeedback(questionId: String): CachedStudyFeedback? =
        store.get(STUDY, questionId)?.let { runCatching { OfflineJson.decodeFromString(CachedStudyFeedback.serializer(), it.json) }.getOrNull() }

    suspend fun saveStudyFeedback(questionId: String, feedback: StudyFeedback) =
        store.put(STUDY, questionId, OfflineJson.encodeToString(CachedStudyFeedback.serializer(), CachedStudyFeedback(feedback.correctOption, feedback.explanation)))

    // ---- Evaluaciones enviadas ---------------------------------------------------------------

    /** Registra una evaluación encolada sin calificación. */
    suspend fun recordQueuedAttempt(courseId: String, attempt: AttemptSubmission) {
        val dto = AttemptRecordDto(attempt.id, courseId, attempt.attemptType.name, clock())
        store.put(ATTEMPTS, attempt.id, OfflineJson.encodeToString(AttemptRecordDto.serializer(), dto))
    }

    /** Guarda la calificación que devolvió el servidor al sincronizar (o al enviar con red). */
    suspend fun recordGraded(courseId: String, outcome: AttemptOutcome) {
        val summary = outcome.attempt
        val queuedAt = store.get(ATTEMPTS, summary.id)
            ?.let { runCatching { OfflineJson.decodeFromString(AttemptRecordDto.serializer(), it.json).queuedAt }.getOrNull() } ?: clock()
        val dto = AttemptRecordDto(summary.id, courseId, summary.attemptType.name, queuedAt, summary.score, summary.passed, summary.completedAt, summary.xpEarned)
        store.put(ATTEMPTS, summary.id, OfflineJson.encodeToString(AttemptRecordDto.serializer(), dto))
        saveProgress(courseId, outcome.progress)
    }

    fun observeAttempts(courseId: String): Flow<List<AttemptRecord>> = store.observeAll(ATTEMPTS).map { records ->
        records.mapNotNull { runCatching { OfflineJson.decodeFromString(AttemptRecordDto.serializer(), it.json) }.getOrNull() }
            .filter { it.courseId.equals(courseId, ignoreCase = true) }.sortedByDescending { it.queuedAt }.map { it.domain() }
    }

    private fun key(org: String, courseId: String) = "$org/$courseId"

    companion object {
        const val CATALOG = "training-catalog"
        const val META = "training-catalog-meta"
        const val PROGRESS = "training-progress"
        const val STUDY = "training-study"
        const val ATTEMPTS = "training-attempts"
        const val BANK = "training-bank"
        const val DOWNLOAD_LIMIT = "training-download-limit"
    }

    /**
     * Gancho para el resultado de una operación enviada desde la Outbox: guarda la calificación de una
     * evaluación o retira el avance local ya aplicado en el servidor. Ignora rutas que no conoce.
     */
    suspend fun onSynced(path: String, status: Int, responseBody: String) {
        val clean = path.substringBefore('?')
        val match = Regex("/training/courses/([A-Za-z0-9_-]{1,64})/(attempts|progress)$").find(clean) ?: return
        val courseId = match.groupValues[1]
        if (match.groupValues[2] == "progress") { clearProgress(courseId); return }
        val dto = Json { ignoreUnknownKeys = true }.decodeFromString(Envelope.serializer(AttemptOutcomeSummaryDto.serializer()), responseBody).data
        val summary = dto.attempt
        val queuedAt = store.get(ATTEMPTS, summary.id)
            ?.let { runCatching { OfflineJson.decodeFromString(AttemptRecordDto.serializer(), it.json).queuedAt }.getOrNull() } ?: clock()
        store.put(ATTEMPTS, summary.id, OfflineJson.encodeToString(AttemptRecordDto.serializer(),
            AttemptRecordDto(summary.id, courseId, summary.attemptType, queuedAt, summary.score, summary.passed, summary.completedAt, summary.xpEarned)))
        saveProgress(courseId, dto.progress.domain())
    }
}

/** Parte de la respuesta de un intento que se conserva al sincronizar; no necesita los resultados por pregunta. */
@kotlinx.serialization.Serializable
internal data class AttemptOutcomeSummaryDto(val attempt: AttemptDto, val progress: ProgressDto)
