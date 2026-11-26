package pe.bpm.features.training.domain

class LoadTrainingCourse(private val repository: TrainingRepository) {
    suspend operator fun invoke(org: String, courseId: String): TrainingResult<TrainingCourse> = repository.course(org, courseId)
}

/** Pide una URL nueva en cada reproducción; no hay caché de URLs firmadas. */
class RequestLessonVideo(private val repository: TrainingRepository) {
    suspend operator fun invoke(org: String, courseId: String, lessonId: String): TrainingResult<VideoTicket> =
        repository.videoTicket(org, courseId, lessonId)
}

class SaveLessonProgress(private val repository: TrainingRepository) {
    suspend operator fun invoke(org: String, courseId: String, lessonId: String, completedLessonId: String? = null): TrainingResult<TrainingProgress> =
        repository.saveProgress(org, courseId, lessonId, completedLessonId)
}

/** Modo estudio: verifica la opción elegida sin crear intento, puntaje, puntos ni constancia. */
class CheckStudyAnswer(private val repository: TrainingRepository) {
    suspend operator fun invoke(org: String, course: TrainingCourse, questionId: String, selected: Int): TrainingResult<StudyFeedback> {
        val question = course.questions.firstOrNull { it.id == questionId }
            ?: return TrainingResult.Failure(TrainingFailure.NOT_FOUND)
        if (selected !in question.options.indices) return TrainingResult.Failure(TrainingFailure.INVALID)
        return repository.checkStudyAnswer(org, course.course.id, questionId, selected)
    }
}

/**
 * Prepara y envía un intento calificado. El intento se construye una sola vez ([prepare]); el
 * envío se puede repetir con el mismo objeto sin duplicar el resultado.
 */
class SubmitTrainingAttempt(private val repository: TrainingRepository) {
    /** Rechaza intentos que no respondan todas las preguntas exactamente una vez. */
    fun prepare(
        course: TrainingCourse,
        type: AttemptType,
        answers: Map<String, Int>,
        newId: () -> String,
        startedAt: String,
        now: String,
        lessonId: String? = null,
        /** Evaluación sorteada del banco: el intento usa su id y su versión, y se responden sus preguntas. */
        drawn: DrawnEvaluation? = null,
    ): AttemptSubmission? {
        if (drawn != null) {
            if (type != drawn.type || lessonId != null) return null
            val ids = drawn.questions.map { it.id }
            if (ids.isEmpty() || answers.keys != ids.toSet() || drawn.questions.any { answers[it.id] !in it.options.indices }) return null
            return AttemptSubmission(drawn.id, newId(), drawn.versionId, type, startedAt, now, drawn.questions.map { AttemptAnswer(it.id, answers.getValue(it.id)) })
        }
        // MODULE evalúa solo las preguntas de su lección; los demás tipos no admiten lección.
        if ((type == AttemptType.MODULE) != (lessonId != null)) return null
        if (lessonId != null && course.lessons.none { it.id == lessonId }) return null
        val questions = evaluationQuestions(course, lessonId)
        val ids = questions.map { it.id }
        if (ids.isEmpty() || answers.keys != ids.toSet()) return null
        if (questions.any { answers[it.id] !in it.options.indices }) return null
        return AttemptSubmission(newId(), newId(), course.version.id, type, startedAt, now,
            questions.map { AttemptAnswer(it.id, answers.getValue(it.id)) }, lessonId)
    }

    /** Preguntas que entran en la evaluación: las de la lección (módulo) o todas las del curso. */
    fun evaluationQuestions(course: TrainingCourse, lessonId: String?): List<TrainingQuestion> =
        if (lessonId == null) course.questions else course.questions.filter { it.lessonId == lessonId }

    suspend operator fun invoke(org: String, courseId: String, attempt: AttemptSubmission): TrainingResult<AttemptOutcome> =
        repository.submitAttempt(org, courseId, attempt)
}

/** Sortea en el servidor la evaluación de un curso con banco oficial (necesita conexión). */
class StartTrainingEvaluation(private val repository: TrainingRepository) {
    suspend operator fun invoke(org: String, courseId: String, id: String, type: AttemptType): TrainingResult<DrawnEvaluation> {
        if (type == AttemptType.MODULE) return TrainingResult.Failure(TrainingFailure.INVALID)
        return repository.startEvaluation(org, courseId, id, type)
    }
}

/** Banco oficial de un módulo para administración (solo lectura). */
class LoadQuestionBank(private val repository: TrainingAuthoringRepository) {
    suspend operator fun invoke(org: String, courseId: String): TrainingResult<QuestionBank> = repository.questionBank(org, courseId)
}

class ListAdminCourses(private val repository: TrainingAuthoringRepository) {
    suspend operator fun invoke(org: String, status: CourseStatus? = null) = repository.courses(org, status)
}

class CreateTrainingCourse(private val repository: TrainingAuthoringRepository) {
    suspend operator fun invoke(org: String, course: NewCourse): TrainingResult<TrainingCourseInfo> {
        if (TrainingDraftRules.validateNewCourse(course).isNotEmpty()) return TrainingResult.Failure(TrainingFailure.INVALID)
        return repository.createCourse(org, course.copy(code = course.code.trim(), title = course.title.trim()))
    }
}

class LoadTrainingDraft(private val repository: TrainingAuthoringRepository) {
    suspend operator fun invoke(org: String, courseId: String) = repository.draft(org, courseId)
}

/** Guarda solo borradores que cumplen los límites del contrato; el backend valida otra vez. */
class SaveTrainingDraft(private val repository: TrainingAuthoringRepository) {
    suspend operator fun invoke(org: String, draft: TrainingDraft): TrainingResult<TrainingDraft> {
        if (TrainingDraftRules.validate(draft).isNotEmpty()) return TrainingResult.Failure(TrainingFailure.INVALID)
        return repository.saveDraft(org, draft.course.id, draft.normalized())
    }
}

class PublishTrainingCourse(private val repository: TrainingAuthoringRepository) {
    suspend operator fun invoke(org: String, courseId: String) = repository.publish(org, courseId)
}

class UploadLessonVideo(private val repository: TrainingAuthoringRepository) {
    suspend operator fun invoke(org: String, mediaId: String, video: PickedVideo, onProgress: (Float) -> Unit = {}): TrainingResult<VideoFile> {
        if (TrainingVideoRules.validate(video.name, video.mimeType, video.bytes) != null) return TrainingResult.Failure(TrainingFailure.CONTENT_INVALID)
        return repository.uploadVideo(org, mediaId, video, onProgress)
    }
}
