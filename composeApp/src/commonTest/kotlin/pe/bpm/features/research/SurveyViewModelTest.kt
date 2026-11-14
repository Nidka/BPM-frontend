package pe.bpm.features.research

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.research.domain.*
import pe.bpm.features.research.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyViewModelTest {
    private suspend fun TestScope.withVm(repo: FakeParticipationRepository, block: suspend (SurveyViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var n = 0
        val model = SurveyViewModel(LoadParticipation(repo), RecordConsent(repo), SubmitSurvey(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP })
        try {
            model.open("user", "org"); advanceUntilIdle()
            block(model)
        } finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private fun SurveyViewModel.answerAll() = validAnswers.forEach { (q, v) -> answer(q, v) }

    @Test fun opensWithParticipationAndStartsAMoment() = runTest {
        withVm(FakeParticipationRepository()) { model ->
            assertNotNull(model.state.value.participation)
            model.start(SurveyMoment.PRETEST)
            assertEquals(SurveyPane.QUESTIONS, model.state.value.screen)
        }
    }

    @Test fun incompleteAnswersAreNotSent() = runTest {
        val repo = FakeParticipationRepository()
        withVm(repo) { model ->
            model.start(SurveyMoment.PRETEST)
            model.answer(Q1, 1)
            model.submit(); advanceUntilIdle()
            assertTrue(repo.submissions.isEmpty())
            assertTrue(model.state.value.showMissing)
            model.goToFirstMissing()
            assertEquals(1, model.state.value.index)
        }
    }

    @Test fun invalidValuesAreIgnored() = runTest {
        withVm(FakeParticipationRepository()) { model ->
            model.start(SurveyMoment.PRETEST)
            model.answer(Q1, 5); model.answer(Q2, 0); model.answer(Q2, 6)
            assertTrue(model.state.value.answers.isEmpty())
        }
    }

    @Test fun successfulSubmissionShowsConfirmationAndMarksTheMomentCompleted() = runTest {
        val repo = FakeParticipationRepository()
        withVm(repo) { model ->
            model.start(SurveyMoment.PRETEST); model.answerAll()
            model.submit(); advanceUntilIdle()
            assertEquals(SurveyPane.DONE, model.state.value.screen)
            assertEquals(SurveyMoment.PRETEST, model.state.value.completedMoment)
            assertEquals(setOf(SurveyMoment.PRETEST), model.state.value.participation?.completedMoments())
            // Ya completado: no se puede iniciar otra vez.
            model.dismissDone(); model.start(SurveyMoment.PRETEST)
            assertEquals(SurveyPane.OVERVIEW, model.state.value.screen)
        }
    }

    @Test fun retryAfterNetworkFailureReusesTheSameIdentifiers() = runTest {
        val repo = FakeParticipationRepository()
        var calls = 0
        repo.submitResult = { if (++calls == 1) ResearchResult.Failure(ResearchFailure.CONNECTION) else ResearchResult.Success(SubmissionReceipt(it.id, it.moment, it.instrumentVersionId, STAMP)) }
        withVm(repo) { model ->
            model.start(SurveyMoment.POSTTEST); model.answerAll()
            model.submit(); advanceUntilIdle()
            assertEquals(ResearchFailure.CONNECTION, model.state.value.failure)
            assertNotNull(model.state.value.pending)
            // Con una entrega pendiente las respuestas quedan bloqueadas.
            model.answer(Q1, 0)
            assertEquals(1, model.state.value.answers[Q1])
            model.submit(); advanceUntilIdle()
            assertEquals(2, repo.submissions.size)
            assertEquals(repo.submissions[0], repo.submissions[1])
            assertEquals(SurveyPane.DONE, model.state.value.screen)
        }
    }

    @Test fun duplicateSubmissionConflictIsTreatedAsAlreadyDone() = runTest {
        val repo = FakeParticipationRepository()
        repo.submitResult = { ResearchResult.Failure(ResearchFailure.ALREADY_SUBMITTED) }
        withVm(repo) { model ->
            model.start(SurveyMoment.PRETEST); model.answerAll()
            repo.participation = ResearchResult.Success(sampleParticipation(submissions = listOf(SubmissionRecord(SurveyMoment.PRETEST, VERSION_ID, STAMP))))
            model.submit(); advanceUntilIdle()
            assertEquals(SurveyPane.OVERVIEW, model.state.value.screen)
            assertNull(model.state.value.pending)
            assertNotNull(model.state.value.notice)
            assertEquals(setOf(SurveyMoment.PRETEST), model.state.value.participation?.completedMoments())
        }
    }

    @Test fun revokedConsentAtSubmissionReloadsAndBlocksTheSurvey() = runTest {
        val repo = FakeParticipationRepository()
        repo.submitResult = { ResearchResult.Failure(ResearchFailure.CONSENT_REQUIRED) }
        withVm(repo) { model ->
            model.start(SurveyMoment.PRETEST); model.answerAll()
            repo.participation = ResearchResult.Success(sampleParticipation(valid = false, withdrawn = true))
            model.submit(); advanceUntilIdle()
            assertEquals(SurveyPane.OVERVIEW, model.state.value.screen)
            assertNull(model.state.value.survey)
            model.start(SurveyMoment.PRETEST)
            assertEquals(SurveyPane.OVERVIEW, model.state.value.screen)
        }
    }

    @Test fun revokingConsentClearsAnswersAndHidesTheSurvey() = runTest {
        val repo = FakeParticipationRepository()
        withVm(repo) { model ->
            model.start(SurveyMoment.PRETEST); model.answerAll()
            model.back()
            model.showConsent(); model.askRevoke()
            assertTrue(model.state.value.confirmingRevoke)
            model.revokeConsent(); advanceUntilIdle()
            assertEquals(listOf("v1" to false), repo.consents)
            assertNull(model.state.value.survey)
            assertTrue(model.state.value.answers.isEmpty())
            model.acceptConsent(); advanceUntilIdle()
            assertEquals("v1" to true, repo.consents.last())
            assertNotNull(model.state.value.survey)
        }
    }

    @Test fun loadFailureCanBeRetried() = runTest {
        val repo = FakeParticipationRepository().apply { participation = ResearchResult.Failure(ResearchFailure.CONNECTION) }
        withVm(repo) { model ->
            assertEquals(ResearchFailure.CONNECTION, model.state.value.failure)
            repo.participation = ResearchResult.Success(sampleParticipation())
            model.refresh(); advanceUntilIdle()
            assertNull(model.state.value.failure)
            assertNotNull(model.state.value.participation)
        }
    }
}
