package pe.bpm.features.training

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingViewModelTest {
    private fun vm(repo: FakeTrainingRepository, clock: () -> Instant = { Instant.parse("2026-11-10T12:00:00Z") }): TrainingViewModel {
        var n = 0
        return TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo), SubmitTrainingAttempt(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP }, clock)
    }

    private suspend fun TestScope.withVm(repo: FakeTrainingRepository, clock: () -> Instant = { Instant.parse("2026-11-10T12:00:00Z") }, block: suspend (TrainingViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = vm(repo, clock)
        try {
            model.open("user", "org", COURSE_ID); advanceUntilIdle()
            block(model)
        } finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun openingLoadsTheCourseAndResumeSavesTheLastLessonViewed() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            assertEquals(COURSE_ID, model.state.value.course?.course?.id)
            model.resume(); advanceUntilIdle()
            assertEquals(TrainingPane.LESSON, model.state.value.screen)
            assertEquals(LESSON_1, model.state.value.lessonId)
            assertEquals(listOf(LESSON_1), repo.progressCalls)
            assertEquals(LESSON_1, model.state.value.course?.progress?.lastLessonId)
            model.nextLesson(); advanceUntilIdle()
            assertEquals(listOf(LESSON_1, LESSON_2), repo.progressCalls)
            model.previousLesson(); advanceUntilIdle()
            assertEquals(LESSON_1, model.state.value.lessonId)
        }
    }

    @Test fun resumeUsesTheServerLastLesson() = runTest {
        val repo = FakeTrainingRepository().apply { course = TrainingResult.Success(sampleCourse().copy(progress = progress.copy(status = ProgressStatus.IN_PROGRESS, lastLessonId = LESSON_2))) }
        withVm(repo) { model -> model.resume(); advanceUntilIdle(); assertEquals(LESSON_2, model.state.value.lessonId) }
    }

    @Test fun progressFailureDoesNotBlockReading() = runTest {
        val repo = FakeTrainingRepository().apply { progressResult = { TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        withVm(repo) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            assertEquals(TrainingPane.LESSON, model.state.value.screen)
            assertTrue(model.state.value.progressNotSaved)
        }
    }

    @Test fun loadFailureIsReportedAndCanBeRetried() = runTest {
        val repo = FakeTrainingRepository().apply { course = TrainingResult.Failure(TrainingFailure.CONNECTION) }
        withVm(repo) { model ->
            assertEquals(TrainingFailure.CONNECTION, model.state.value.failure)
            assertNull(model.state.value.course)
            repo.course = TrainingResult.Success(sampleCourse())
            model.refresh(); advanceUntilIdle()
            assertNull(model.state.value.failure)
            assertNotNull(model.state.value.course)
        }
    }

    @Test fun videoTicketIsRequestedOnDemandAndRefreshedOnlyWhenExpired() = runTest {
        val repo = FakeTrainingRepository()
        var now = Instant.parse("2026-11-10T12:00:00Z")
        withVm(repo, { now }) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            assertEquals(VideoState.Idle, model.state.value.video)
            assertTrue(repo.ticketCalls.isEmpty())
            model.loadVideo(); advanceUntilIdle()
            assertIs<VideoState.Ready>(model.state.value.video)
            assertEquals(1, repo.ticketCalls.size)
            // Error con URL vigente: no se pide otra; se ofrece reintentar.
            model.onVideoError(); advanceUntilIdle()
            assertIs<VideoState.Failed>(model.state.value.video)
            assertEquals(1, repo.ticketCalls.size)
            // URL vencida: se pide una nueva automáticamente.
            repo.ticket = TrainingResult.Success(VideoTicket("https://b.r2.cloudflarestorage.com/x.mp4?sig=2", "video/mp4", "2026-11-10T12:10:00Z"))
            model.loadVideo(); advanceUntilIdle()
            now = Instant.parse("2026-11-10T12:20:00Z")
            model.onVideoError(); advanceUntilIdle()
            assertEquals(3, repo.ticketCalls.size)
            // Cambiar de lección descarta la URL anterior.
            model.nextLesson(); advanceUntilIdle()
            assertEquals(VideoState.Idle, model.state.value.video)
        }
    }

    @Test fun lessonWithoutVideoNeverRequestsATicket() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model -> model.selectLesson(LESSON_2); model.loadVideo(); advanceUntilIdle(); assertTrue(repo.ticketCalls.isEmpty()) }
    }

    // ---- Modo estudio -----------------------------------------------------------------------

    @Test fun studyShowsFeedbackWithoutScoreAndAllowsRetryAndFinishing() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.startStudy(); advanceUntilIdle()
            assertEquals(TrainingPane.STUDY, model.state.value.screen)
            model.answerStudy(0); advanceUntilIdle()
            val wrong = model.state.value.study!!
            assertEquals(false, wrong.feedback?.correct)
            assertEquals("Por higiene", wrong.feedback?.explanation)
            // Una pregunta ya respondida no se vuelve a enviar hasta reintentar.
            model.answerStudy(1); advanceUntilIdle()
            assertEquals(1, repo.studyCalls.size)
            model.retryStudyQuestion()
            assertNull(model.state.value.study?.feedback)
            model.answerStudy(1); advanceUntilIdle()
            assertEquals(true, model.state.value.study?.feedback?.correct)
            assertEquals(2, repo.studyCalls.size)
            model.nextStudyQuestion()
            assertEquals(1, model.state.value.study?.index)
            model.answerStudy(1); advanceUntilIdle(); model.nextStudyQuestion()
            assertTrue(model.state.value.study!!.finished)
            // Estudiar nunca crea intentos ni altera el avance ni el certificado.
            assertTrue(repo.attempts.isEmpty())
            assertEquals(ProgressStatus.NOT_STARTED, model.state.value.course?.progress?.status)
            assertNull(model.state.value.evaluation)
        }
    }

    @Test fun studyFailureKeepsTheQuestionAnswerable() = runTest {
        val repo = FakeTrainingRepository().apply { study = { _, _ -> TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        withVm(repo) { model ->
            model.startStudy(); model.answerStudy(1); advanceUntilIdle()
            val study = model.state.value.study!!
            assertEquals(TrainingFailure.CONNECTION, study.failure)
            assertNull(study.feedback); assertNull(study.selected); assertFalse(study.checking)
            repo.study = { _, selected -> TrainingResult.Success(StudyFeedback(selected == 1, 1, "")) }
            model.answerStudy(1); advanceUntilIdle()
            assertEquals(true, model.state.value.study?.feedback?.correct)
        }
    }

    @Test fun studyByLessonUsesOnlyThatLessonsQuestionsAndBackLeavesTheCourse() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.startStudy(LESSON_1)
            assertEquals(listOf(QUESTION_1), model.state.value.study?.questions?.map { it.id })
            assertTrue(model.back())
            assertEquals(TrainingPane.OVERVIEW, model.state.value.screen)
            assertNull(model.state.value.study)
            assertFalse(model.back())
        }
    }

    // ---- Modo evaluación --------------------------------------------------------------------

    private fun TrainingViewModel.answerAll(vararg options: Int) {
        val questions = state.value.course!!.questions
        questions.forEachIndexed { i, q -> answerEvaluation(q.id, options[i]) }
    }

    @Test fun submitIsBlockedUntilEveryQuestionIsAnswered() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL)
            assertFalse(model.canSubmitEvaluation())
            model.answerEvaluation(QUESTION_1, 1)
            assertFalse(model.canSubmitEvaluation())
            model.submitEvaluation(); advanceUntilIdle()
            assertTrue(repo.attempts.isEmpty())
            assertEquals(TrainingFailure.ANSWERS_INVALID, model.state.value.evaluation?.failure)
            model.answerEvaluation(QUESTION_2, 1)
            assertTrue(model.canSubmitEvaluation())
        }
    }

    @Test fun passedFinalShowsPerQuestionResultsScoreXpAndCertificate() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL); model.answerAll(1, 1)
            model.submitEvaluation(); advanceUntilIdle()
            val state = model.state.value
            val outcome = state.evaluation?.outcome!!
            assertEquals(100, outcome.attempt.score)
            assertTrue(outcome.attempt.passed)
            assertEquals(listOf(true, true), outcome.results.map { it.correct })
            assertEquals("ABCDEFGHIJKLMNOP", state.course?.certificate?.verificationCode)
            assertEquals(ProgressStatus.COMPLETED, state.course?.progress?.status)
            assertEquals(20, state.course?.progress?.experiencePoints)
            // Con resultado las respuestas ya no se editan ni se reenvían.
            model.answerEvaluation(QUESTION_1, 0); model.submitEvaluation(); advanceUntilIdle()
            assertEquals(1, repo.attempts.size)
            model.closeEvaluation()
            assertEquals(TrainingPane.OVERVIEW, model.state.value.screen)
            assertNotNull(model.state.value.course?.certificate)
        }
    }

    @Test fun failedDiagnosticDoesNotChangeCertificate() = runTest {
        val repo = FakeTrainingRepository()
        repo.attemptResults += { outcomeFor(it, passed = false) }
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.DIAGNOSTIC); model.answerAll(0, 0)
            model.submitEvaluation(); advanceUntilIdle()
            assertEquals(false, model.state.value.evaluation?.outcome?.attempt?.passed)
            assertEquals(0, model.state.value.evaluation?.outcome?.attempt?.score)
            assertNull(model.state.value.course?.certificate)
            assertEquals(AttemptType.DIAGNOSTIC, repo.attempts.single().attemptType)
        }
    }

    @Test fun networkFailureKeepsTheSameAttemptAndRetryResendsTheIdenticalBody() = runTest {
        val repo = FakeTrainingRepository()
        repo.attemptResults += { TrainingResult.Failure(TrainingFailure.CONNECTION) }
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL); model.answerAll(1, 0)
            model.submitEvaluation(); advanceUntilIdle()
            val failed = model.state.value.evaluation!!
            assertEquals(TrainingFailure.CONNECTION, failed.failure)
            assertNotNull(failed.pending)
            assertNull(failed.outcome)
            // Con un envío incierto las respuestas quedan bloqueadas.
            model.answerEvaluation(QUESTION_1, 0)
            assertEquals(1, model.state.value.evaluation?.answers?.get(QUESTION_1))
            model.submitEvaluation(); advanceUntilIdle()
            assertEquals(2, repo.attempts.size)
            assertEquals(repo.attempts[0], repo.attempts[1])
            assertEquals(repo.attempts[0].clientOperationId, repo.attempts[1].clientOperationId)
            assertNotNull(model.state.value.evaluation?.outcome)
            assertNull(model.state.value.evaluation?.pending)
        }
    }

    @Test fun doubleTapWhileSubmittingSendsOnlyOnce() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL); model.answerAll(1, 1)
            model.submitEvaluation(); model.submitEvaluation(); advanceUntilIdle()
            assertEquals(1, repo.attempts.size)
        }
    }

    @Test fun definitiveRejectionClearsThePendingAttemptSoAnswersCanBeFixed() = runTest {
        val repo = FakeTrainingRepository()
        repo.attemptResults += { TrainingResult.Failure(TrainingFailure.ANSWERS_INVALID) }
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL); model.answerAll(1, 1)
            model.submitEvaluation(); advanceUntilIdle()
            assertNull(model.state.value.evaluation?.pending)
            assertEquals(TrainingFailure.ANSWERS_INVALID, model.state.value.evaluation?.failure)
            model.answerEvaluation(QUESTION_1, 0)
            model.submitEvaluation(); advanceUntilIdle()
            // Un cuerpo corregido es un intento nuevo, con otros identificadores.
            assertNotEquals(repo.attempts[0].id, repo.attempts[1].id)
            assertNotEquals(repo.attempts[0].clientOperationId, repo.attempts[1].clientOperationId)
        }
    }

    @Test fun outdatedVersionReloadsTheCourseAndRestartsTheEvaluation() = runTest {
        val repo = FakeTrainingRepository()
        val newVersion = "20000000-0000-4000-8000-000000000009"
        repo.attemptResults += {
            repo.course = TrainingResult.Success(sampleCourse(version = newVersion))
            TrainingResult.Failure(TrainingFailure.VERSION_OUTDATED)
        }
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL); model.answerAll(1, 1)
            model.submitEvaluation(); advanceUntilIdle()
            val state = model.state.value
            assertEquals(2, repo.courseCalls.size)
            assertEquals(newVersion, state.course?.version?.id)
            assertEquals(TrainingPane.EVALUATION, state.screen)
            assertTrue(state.evaluation!!.answers.isEmpty())
            assertNull(state.evaluation?.pending)
            assertNotNull(state.notice)
            // El siguiente intento usa la versión vigente.
            model.answerAll(1, 1); model.submitEvaluation(); advanceUntilIdle()
            assertEquals(newVersion, repo.attempts.last().versionId)
            assertNotEquals(repo.attempts[0].id, repo.attempts[1].id)
        }
    }

    @Test fun changingCourseOrOrganizationDropsLateResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeTrainingRepository()
        val model = vm(repo)
        try {
            model.open("user", "org", COURSE_ID); advanceUntilIdle()
            model.startEvaluation(AttemptType.FINAL); model.answerAll(1, 1)
            model.submitEvaluation()
            model.open("user", "other-org", COURSE_ID)
            advanceUntilIdle()
            assertEquals("other-org", model.state.value.organizationId)
            assertNull(model.state.value.evaluation?.outcome)
            model.reset()
            assertNull(model.state.value.course)
        } finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
