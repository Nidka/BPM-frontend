package pe.bpm.features.training.data

import pe.bpm.features.training.domain.*

/** Descarga el catálogo con `If-None-Match` y lo guarda: un 304 mantiene la copia local. */
class OfflineTrainingCatalog(
    private val remote: RemoteTrainingRepository,
    private val store: TrainingOfflineStore,
) : TrainingCatalogRepository {
    override suspend fun sync(org: String): TrainingResult<CatalogSync> {
        val etag = store.catalogMeta(org)?.etag
        return when (val result = remote.fetchCatalog(org, etag)) {
            is TrainingResult.Failure -> result
            is TrainingResult.Success -> when (val fetched = result.value) {
                CatalogFetch.NotModified -> { store.touchCatalog(org); TrainingResult.Success(CatalogSync.NOT_MODIFIED) }
                is CatalogFetch.Fresh -> { store.saveCatalog(org, fetched); TrainingResult.Success(CatalogSync.UPDATED) }
            }
        }
    }
}

/**
 * Aprender con o sin red. Envuelve al repositorio remoto:
 *  - el curso se lee del servidor; si no hay conexión, del catálogo guardado con el avance local;
 *  - guardar avance sin red queda en la Outbox y se muestra como guardado (éxito local pendiente);
 *  - el modo estudio responde desde la retroalimentación ya vista con red; si no la hay, necesita conexión;
 *  - un intento sin red queda registrado para calificarse al sincronizar: el servidor califica, nunca el cliente.
 */
class OfflineTrainingRepository(
    private val remote: TrainingRepository,
    private val store: TrainingOfflineStore,
) : TrainingRepository {

    override suspend fun course(org: String, courseId: String): TrainingResult<TrainingCourse> {
        val result = remote.course(org, courseId)
        if (result is TrainingResult.Success) {
            // El avance pendiente de enviar se conserva encima de lo que el servidor aún no recibió.
            val course = result.value
            val local = store.progress(courseId)
            if (local?.lastLessonId != null && local.status != ProgressStatus.NOT_STARTED && course.progress.status != ProgressStatus.COMPLETED) {
                return TrainingResult.Success(course.copy(progress = course.progress.copy(
                    lastLessonId = local.lastLessonId,
                    status = if (course.progress.status == ProgressStatus.NOT_STARTED) ProgressStatus.IN_PROGRESS else course.progress.status)))
            }
            return result
        }
        val reason = (result as TrainingResult.Failure).reason
        if (reason != TrainingFailure.CONNECTION && reason != TrainingFailure.UNAVAILABLE) return result
        return store.cachedCourse(org, courseId)?.let { TrainingResult.Success(it) } ?: result
    }

    override suspend fun videoTicket(org: String, courseId: String, lessonId: String) = remote.videoTicket(org, courseId, lessonId)

    override suspend fun saveProgress(org: String, courseId: String, lessonId: String): TrainingResult<TrainingProgress> {
        val result = remote.saveProgress(org, courseId, lessonId)
        if (result is TrainingResult.Success) { store.clearProgress(courseId); return result }
        if ((result as TrainingResult.Failure).reason != TrainingFailure.QUEUED) return result
        val current = store.progress(courseId) ?: store.cachedCourse(org, courseId)?.progress
            ?: TrainingProgress(ProgressStatus.NOT_STARTED, null, null, 0, null)
        val local = current.copy(lastLessonId = lessonId,
            status = if (current.status == ProgressStatus.NOT_STARTED) ProgressStatus.IN_PROGRESS else current.status)
        store.saveProgress(courseId, local)
        return TrainingResult.Success(local)
    }

    override suspend fun checkStudyAnswer(org: String, courseId: String, questionId: String, selected: Int): TrainingResult<StudyFeedback> {
        val result = remote.checkStudyAnswer(org, courseId, questionId, selected)
        if (result is TrainingResult.Success) { store.saveStudyFeedback(questionId, result.value); return result }
        if ((result as TrainingResult.Failure).reason != TrainingFailure.CONNECTION) return result
        // Sin red solo se revisa lo que ya se había consultado antes; nunca se inventa una respuesta.
        val cached = store.studyFeedback(questionId) ?: return result
        return TrainingResult.Success(StudyFeedback(selected == cached.correctOption, cached.correctOption, cached.explanation))
    }

    override suspend fun submitAttempt(org: String, courseId: String, attempt: AttemptSubmission): TrainingResult<AttemptOutcome> {
        val result = remote.submitAttempt(org, courseId, attempt)
        if (result is TrainingResult.Success) store.recordGraded(courseId, result.value)
        else if ((result as TrainingResult.Failure).reason == TrainingFailure.QUEUED) store.recordQueuedAttempt(courseId, attempt)
        return result
    }
}
