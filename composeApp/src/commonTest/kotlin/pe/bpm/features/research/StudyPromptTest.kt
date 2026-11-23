package pe.bpm.features.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pe.bpm.features.research.domain.*

/** Cuándo se invita a la encuesta del estudio: consentimiento → inicial → final (pasado el piloto), sin bloquear ni insistir. */
class StudyPromptTest {
    private val survey = Survey("CAP", "CAP BPM", "11111111-1111-4111-8111-111111111111", 1,
        List(26) { SurveyQuestion("q$it", it, Dimension.KNOWLEDGE, QuestionType.MULTIPLE_CHOICE, "P$it", listOf("a", "b")) })
    private val document = ConsentDocument("v1", "Consentimiento", "Texto", "Investigadora", "2026-01-01T00:00:00Z")
    private fun participation(valid: Boolean = true, accepted: Boolean? = null, submissions: List<SubmissionRecord> = emptyList(), withSurvey: Boolean = true, withDocument: Boolean = true) =
        Participation(Participant("P-1", ParticipantStatus.ACTIVE), document.takeIf { withDocument },
            accepted?.let { ConsentRecord("v1", it, "2026-01-01T00:00:00Z", "Investigadora") }, valid, survey.takeIf { withSurvey }, submissions)
    private fun record(moment: SurveyMoment, at: String) = SubmissionRecord(moment, survey.instrumentVersionId, at)
    private val now = "2026-06-01T12:00:00Z"

    @Test fun nothingToAskWithoutStudyData() {
        assertNull(studyPrompt(null, now))
        assertNull(studyPrompt(participation(withSurvey = false), now))
        assertNull(studyPrompt(participation(valid = false, withDocument = false), now))
    }

    @Test fun firstInvitationIsTheConsentAndWithdrawalIsRespected() {
        assertEquals(StudyStep.CONSENT, studyPrompt(participation(valid = false), now)?.step)
        assertNull(studyPrompt(participation(valid = false, accepted = false), now))
    }

    @Test fun pretestComesRightAfterConsent() {
        val prompt = studyPrompt(participation(), now)
        assertEquals(StudyStep.PRETEST, prompt?.step)
        assertEquals("Encuesta inicial", prompt?.title)
    }

    @Test fun posttestOnlyAppearsAfterThePilotHasPassed() {
        val pre = record(SurveyMoment.PRETEST, "2026-03-10T12:00:00Z")
        // 83 días después de la inicial: todavía no.
        assertNull(studyPrompt(participation(submissions = listOf(pre)), "2026-06-01T12:00:00Z"))
        // 90 días o más: toca la final.
        assertEquals(StudyStep.POSTTEST, studyPrompt(participation(submissions = listOf(pre)), "2026-06-10T12:00:00Z")?.step)
        // Con las dos hechas no se pide nada.
        assertNull(studyPrompt(participation(submissions = listOf(pre, record(SurveyMoment.POSTTEST, "2026-06-11T12:00:00Z"))), "2026-06-20T12:00:00Z"))
    }

    @Test fun anAnswerForAnotherInstrumentVersionDoesNotCount() {
        val old = SubmissionRecord(SurveyMoment.PRETEST, "22222222-2222-4222-8222-222222222222", "2026-01-10T12:00:00Z")
        assertEquals(StudyStep.PRETEST, studyPrompt(participation(submissions = listOf(old)), now)?.step)
    }
}
