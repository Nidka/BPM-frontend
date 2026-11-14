package pe.bpm.features.training

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pe.bpm.features.training.domain.*

class TrainingUseCasesTest {
    private fun ids(): () -> String { var n = 0; return { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" } }

    @Test fun studyAnswerIsCheckedWithoutScoreAndRejectsUnknownQuestionOrOutOfRangeOption() = runTest {
        val repo = FakeTrainingRepository()
        val check = CheckStudyAnswer(repo)
        val ok = check("org", sampleCourse(), QUESTION_1, 1)
        assertEquals(TrainingResult.Success(StudyFeedback(true, 1, "Por higiene")), ok)
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID), check("org", sampleCourse(), QUESTION_1, 2))
        assertEquals(TrainingResult.Failure(TrainingFailure.NOT_FOUND), check("org", sampleCourse(), "otra", 0))
        // Solo la primera consulta llegó al repositorio.
        assertEquals(listOf(QUESTION_1 to 1), repo.studyCalls)
    }

    @Test fun attemptRequiresEveryQuestionExactlyOnceAndKeepsStableIdentifiers() {
        val submit = SubmitTrainingAttempt(FakeTrainingRepository())
        val course = sampleCourse()
        assertNull(submit.prepare(course, AttemptType.FINAL, mapOf(QUESTION_1 to 1), ids(), STAMP, STAMP))
        assertNull(submit.prepare(course, AttemptType.FINAL, mapOf(QUESTION_1 to 1, QUESTION_2 to 9), ids(), STAMP, STAMP))
        assertNull(submit.prepare(course, AttemptType.FINAL, mapOf(QUESTION_1 to 1, QUESTION_2 to 0, "extra" to 0), ids(), STAMP, STAMP))
        val attempt = submit.prepare(course, AttemptType.FINAL, mapOf(QUESTION_2 to 2, QUESTION_1 to 1), ids(), STAMP, STAMP)!!
        // El orden sigue el del curso, no el del mapa; el id del intento y el de la operación son distintos.
        assertEquals(listOf(QUESTION_1, QUESTION_2), attempt.answers.map { it.questionId })
        assertNotEquals(attempt.id, attempt.clientOperationId)
        assertEquals(VERSION_ID, attempt.versionId)
    }

    @Test fun submitForwardsTheSameBodyEveryTime() = runTest {
        val repo = FakeTrainingRepository()
        val submit = SubmitTrainingAttempt(repo)
        val attempt = submit.prepare(sampleCourse(), AttemptType.DIAGNOSTIC, mapOf(QUESTION_1 to 0, QUESTION_2 to 2), ids(), STAMP, STAMP)!!
        submit("org", COURSE_ID, attempt); submit("org", COURSE_ID, attempt)
        assertEquals(2, repo.attempts.size)
        assertSame(repo.attempts[0], repo.attempts[1])
    }

    @Test fun createCourseValidatesBeforeCallingTheRepositoryAndTrimsInput() = runTest {
        val repo = FakeTrainingRepository()
        val create = CreateTrainingCourse(repo)
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID), create("org", NewCourse("M 01", "Curso", "", 10)))
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID), create("org", NewCourse("M-01", "x", "", 10)))
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID), create("org", NewCourse("M-01", "Curso", "", 0)))
        assertTrue(repo.created.isEmpty())
        assertIs<TrainingResult.Success<*>>(create("org", NewCourse(" M-01 ", "  Curso nuevo  ", "", 10)))
        assertEquals(NewCourse("M-01", "Curso nuevo", "", 10), repo.created.single())
    }

    @Test fun saveDraftRejectsInvalidContentAndNormalizesOrderAndText() = runTest {
        val repo = FakeTrainingRepository()
        val save = SaveTrainingDraft(repo)
        val bad = sampleDraft().copy(lessons = listOf(DraftLesson(LESSON_1, "", "x", 1, null)))
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID), save("org", bad))
        assertTrue(repo.saved.isEmpty())
        val draft = sampleDraft().copy(
            lessons = listOf(DraftLesson(LESSON_2, "  B  ", "", 9, null), DraftLesson(LESSON_1, "A", "", 4, null)),
            questions = listOf(DraftQuestion(QUESTION_1, LESSON_1, "  ¿Pregunta?  ", listOf(" a ", "b"), 0, "  ", 7)),
        )
        val result = save("org", draft)
        assertIs<TrainingResult.Success<*>>(result)
        val sent = repo.saved.single()
        assertEquals(listOf(1, 2), sent.lessons.map { it.displayOrder })
        assertEquals("B", sent.lessons.first().title)
        assertEquals(listOf("a", "b"), sent.questions.single().options)
        assertEquals("", sent.questions.single().explanation)
        assertEquals("v1", sent.rowVersion)
    }

    @Test fun uploadRejectsFilesWithWrongFormatBeforeReserving() = runTest {
        val repo = FakeTrainingRepository()
        val upload = UploadLessonVideo(repo)
        val html = PickedVideo("falso.mp4", "video/mp4", "<html>no es video</html>".encodeToByteArray())
        assertEquals(TrainingResult.Failure(TrainingFailure.CONTENT_INVALID), upload("org", MEDIA_ID, html))
        val mp4 = PickedVideo("lavado.mp4", "video/mp4", byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 1, 2, 3, 4))
        assertIs<TrainingResult.Success<*>>(upload("org", MEDIA_ID, mp4))
        assertEquals(MEDIA_ID, repo.uploads.single().first)
    }

    @Test fun resumeStartsAtLastViewedLessonOrTheFirstOne() {
        val course = sampleCourse()
        assertEquals(LESSON_1, course.resumeLesson()?.id)
        assertEquals(LESSON_2, course.copy(progress = progress.copy(lastLessonId = LESSON_2)).resumeLesson()?.id)
        // Una lección que ya no existe en la versión publicada vuelve a la primera.
        assertEquals(LESSON_1, course.copy(progress = progress.copy(lastLessonId = "retirada")).resumeLesson()?.id)
    }

    @Test fun retryableFailuresAreOnlyTheTransientOnes() {
        assertTrue(TrainingFailure.CONNECTION.retryable && TrainingFailure.UNAVAILABLE.retryable && TrainingFailure.RATE_LIMITED.retryable)
        assertFalse(TrainingFailure.VERSION_OUTDATED.retryable || TrainingFailure.ANSWERS_INVALID.retryable || TrainingFailure.DENIED.retryable)
    }
}
