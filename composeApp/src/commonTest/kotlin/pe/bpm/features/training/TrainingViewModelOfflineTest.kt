package pe.bpm.features.training

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*

/** Aprender sin conexión desde la pantalla: evaluación encolada, estudio sin revisión y video local. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingViewModelOfflineTest {
    private class FakeDownloads(var local: VideoTicket? = null) : TrainingDownloads {
        override val state: StateFlow<DownloadsState> = MutableStateFlow(DownloadsState(supported = true))
        val deleted = mutableListOf<String>()
        override suspend fun refresh() = Unit
        override fun download(org: String, course: TrainingCourse) = Unit
        override fun cancel() = Unit
        override suspend fun delete(mediaId: String) { deleted += mediaId; local = null }
        override suspend fun deleteCourse(course: TrainingCourse) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun setLimit(bytes: Long) = Unit
        override suspend fun localVideo(video: LessonVideo): VideoTicket? = local
    }

    private suspend fun TestScope.withVm(repo: FakeTrainingRepository, downloads: TrainingDownloads = NoTrainingDownloads, block: suspend (TrainingViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var n = 0
        val model = TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo), SubmitTrainingAttempt(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP }, downloads = downloads)
        try {
            model.open("user", "org", COURSE_ID); advanceUntilIdle()
            block(model)
        } finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun anAttemptQueuedOfflineLocksTheAnswersAndShowsThatItWillBeGradedOnConnecting() = runTest {
        val repo = FakeTrainingRepository().apply { attemptResults += { TrainingResult.Failure(TrainingFailure.QUEUED) } }
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL)
            model.state.value.evaluationQuestions.forEach { model.answerEvaluation(it.id, 1) }
            model.submitEvaluation(); advanceUntilIdle()
            val evaluation = model.state.value.evaluation!!
            assertTrue(evaluation.queued)
            assertNull(evaluation.outcome)
            assertNull(evaluation.pending)
            assertNull(evaluation.failure)
            // El cliente no califica: sin resultado, sin reenvío manual y sin cambiar respuestas.
            assertFalse(model.canSubmitEvaluation())
            model.answerEvaluation(model.state.value.evaluationQuestions.first().id, 0)
            assertEquals(1, evaluation.answers[model.state.value.evaluationQuestions.first().id])
            model.submitEvaluation(); advanceUntilIdle()
            assertEquals(1, repo.attempts.size)
            model.closeEvaluation()
            assertEquals(TrainingPane.OVERVIEW, model.state.value.screen)
        }
    }

    @Test fun studyNeedingTheServerLetsTheLearnerSkipAndKeepReading() = runTest {
        val repo = FakeTrainingRepository().apply { study = { _, _ -> TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        withVm(repo) { model ->
            model.startStudy(); advanceUntilIdle()
            model.answerStudy(1); advanceUntilIdle()
            assertEquals(TrainingFailure.CONNECTION, model.state.value.study?.failure)
            model.skipStudyQuestion()
            assertEquals(1, model.state.value.study?.index)
            assertNull(model.state.value.study?.failure)
        }
    }

    @Test fun aDownloadedVideoPlaysFromTheLocalFileWithoutAskingForASignedUrl() = runTest {
        val repo = FakeTrainingRepository()
        val downloads = FakeDownloads(VideoTicket("file:///privado/video-1.mp4", "video/mp4", "local", local = true))
        withVm(repo, downloads) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            model.loadVideo(); advanceUntilIdle()
            val ready = model.state.value.video as VideoState.Ready
            assertTrue(ready.ticket.local)
            assertTrue(repo.ticketCalls.isEmpty())
            // Si el archivo local no se puede reproducir se elimina y se pide el video en línea.
            model.onVideoError(); advanceUntilIdle()
            assertEquals(listOf(MEDIA_ID), downloads.deleted)
            assertEquals(1, repo.ticketCalls.size)
            assertFalse((model.state.value.video as VideoState.Ready).ticket.local)
        }
    }

    @Test fun withoutADownloadTheSignedUrlIsStillRequestedEachTime() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo, FakeDownloads(null)) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            model.loadVideo(); advanceUntilIdle()
            assertEquals(1, repo.ticketCalls.size)
            assertFalse((model.state.value.video as VideoState.Ready).ticket.local)
        }
    }
}
