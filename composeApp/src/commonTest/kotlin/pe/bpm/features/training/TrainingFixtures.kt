package pe.bpm.features.training

import pe.bpm.features.training.domain.*

internal const val COURSE_ID = "10000000-0000-4000-8000-000000000001"
internal const val VERSION_ID = "20000000-0000-4000-8000-000000000001"
internal const val LESSON_1 = "30000000-0000-4000-8000-000000000001"
internal const val LESSON_2 = "30000000-0000-4000-8000-000000000002"
internal const val QUESTION_1 = "40000000-0000-4000-8000-000000000001"
internal const val QUESTION_2 = "40000000-0000-4000-8000-000000000002"
internal const val MEDIA_ID = "70000000-0000-4000-8000-000000000001"
internal const val STAMP = "2026-11-10T12:00:00Z"

internal val progress = TrainingProgress(ProgressStatus.NOT_STARTED, null, null, 0, null)

internal fun sampleCourse(version: String = VERSION_ID, withVideo: Boolean = true) = TrainingCourse(
    TrainingCourseInfo(COURSE_ID, "M-01", "Higiene de manos", "Lavado correcto", 15),
    TrainingVersionInfo(version, 2, 80),
    listOf(
        TrainingLesson(LESSON_1, "Paso a paso", "Texto uno", 1, if (withVideo) LessonVideo(MEDIA_ID, "video/mp4", 1234) else null),
        TrainingLesson(LESSON_2, "Cuándo lavar", "Texto dos", 2, null),
    ),
    listOf(
        TrainingQuestion(QUESTION_1, LESSON_1, "¿Cuánto dura el lavado?", listOf("5 s", "20 s"), 1),
        TrainingQuestion(QUESTION_2, null, "¿Con qué se seca?", listOf("Toalla de uso común", "Papel desechable", "Delantal"), 2),
    ),
    progress, null,
)

internal fun sampleDraft(rowVersion: String = "v1") = TrainingDraft(
    TrainingCourseInfo(COURSE_ID, "M-01", "Higiene de manos", "", 15), VERSION_ID, 3, 80, rowVersion,
    listOf(DraftLesson(LESSON_1, "Paso a paso", "Texto", 1, null)),
    listOf(DraftQuestion(QUESTION_1, LESSON_1, "¿Cuánto dura?", listOf("5 s", "20 s"), 1, "Al menos 20 s", 1)),
)

/** Repositorio falso programable: cada respuesta se consume en orden y todo se registra. */
internal class FakeTrainingRepository : TrainingRepository, TrainingAuthoringRepository {
    var course: TrainingResult<TrainingCourse> = TrainingResult.Success(sampleCourse())
    val courseCalls = mutableListOf<String>()
    var ticket: TrainingResult<VideoTicket> = TrainingResult.Success(VideoTicket("https://b.r2.cloudflarestorage.com/x.mp4?sig=1", "video/mp4", "2099-01-01T00:00:00Z"))
    val ticketCalls = mutableListOf<String>()
    var progressResult: (String) -> TrainingResult<TrainingProgress> = { id -> TrainingResult.Success(TrainingProgress(ProgressStatus.IN_PROGRESS, id, null, 0, null)) }
    val progressCalls = mutableListOf<String>()
    var study: (String, Int) -> TrainingResult<StudyFeedback> = { _, selected -> TrainingResult.Success(StudyFeedback(selected == 1, 1, "Por higiene")) }
    val studyCalls = mutableListOf<Pair<String, Int>>()
    val attempts = mutableListOf<AttemptSubmission>()
    val attemptResults = ArrayDeque<(AttemptSubmission) -> TrainingResult<AttemptOutcome>>()

    override suspend fun course(org: String, courseId: String): TrainingResult<TrainingCourse> { courseCalls += courseId; return course }
    override suspend fun videoTicket(org: String, courseId: String, lessonId: String): TrainingResult<VideoTicket> { ticketCalls += lessonId; return ticket }
    override suspend fun saveProgress(org: String, courseId: String, lessonId: String): TrainingResult<TrainingProgress> { progressCalls += lessonId; return progressResult(lessonId) }
    override suspend fun checkStudyAnswer(org: String, courseId: String, questionId: String, selected: Int): TrainingResult<StudyFeedback> {
        studyCalls += questionId to selected; return study(questionId, selected)
    }
    override suspend fun submitAttempt(org: String, courseId: String, attempt: AttemptSubmission): TrainingResult<AttemptOutcome> {
        attempts += attempt
        return (attemptResults.removeFirstOrNull() ?: { outcomeFor(it, passed = true) })(attempt)
    }

    // Autoría
    var adminCourses: TrainingResult<List<AdminCourse>> = TrainingResult.Success(emptyList())
    val created = mutableListOf<NewCourse>()
    var draftResult: TrainingResult<TrainingDraft> = TrainingResult.Success(sampleDraft())
    val saved = mutableListOf<TrainingDraft>()
    var saveResult: (TrainingDraft) -> TrainingResult<TrainingDraft> = { TrainingResult.Success(it.copy(rowVersion = "v-next")) }
    var published = 0
    val uploads = mutableListOf<Pair<String, PickedVideo>>()
    var uploadResult: TrainingResult<VideoFile> = TrainingResult.Success(VideoFile(MEDIA_ID, "lavado.mp4", "video/mp4", 12))

    override suspend fun courses(org: String, status: CourseStatus?) = adminCourses
    override suspend fun createCourse(org: String, course: NewCourse): TrainingResult<TrainingCourseInfo> {
        created += course
        return TrainingResult.Success(TrainingCourseInfo(COURSE_ID, course.code, course.title, course.description, course.estimatedDurationMinutes))
    }
    override suspend fun draft(org: String, courseId: String) = draftResult
    override suspend fun saveDraft(org: String, courseId: String, draft: TrainingDraft): TrainingResult<TrainingDraft> { saved += draft; return saveResult(draft) }
    override suspend fun publish(org: String, courseId: String): TrainingResult<Unit> { published++; return TrainingResult.Success(Unit) }
    override suspend fun uploadVideo(org: String, mediaId: String, video: PickedVideo, onProgress: (Float) -> Unit): TrainingResult<VideoFile> {
        uploads += mediaId to video; onProgress(0.5f); return uploadResult
    }
}

/** Resultado coherente con las respuestas enviadas: la opción 1 es siempre la correcta. */
internal fun outcomeFor(attempt: AttemptSubmission, passed: Boolean): TrainingResult<AttemptOutcome> {
    val results = attempt.answers.map { QuestionResult(it.questionId, it.selectedOption, it.selectedOption == 1, 1, "Explicación") }
    val score = kotlin.math.round(results.count { it.correct } * 100.0 / results.size).toInt()
    return TrainingResult.Success(AttemptOutcome(
        AttemptSummary(attempt.id, attempt.attemptType, score, passed, STAMP), results,
        TrainingProgress(if (passed && attempt.attemptType == AttemptType.FINAL) ProgressStatus.COMPLETED else ProgressStatus.IN_PROGRESS, null, score, 20, null),
        if (passed && attempt.attemptType == AttemptType.FINAL) TrainingCertificate("80000000-0000-4000-8000-000000000001", "ABCDEFGHIJKLMNOP", STAMP) else null))
}
