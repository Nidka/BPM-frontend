package pe.bpm.features.research

import pe.bpm.features.research.domain.*

const val VERSION_ID = "20000000-0000-4000-8000-000000000001"
const val Q1 = "40000000-0000-4000-8000-000000000001"
const val Q2 = "40000000-0000-4000-8000-000000000002"
const val Q3 = "40000000-0000-4000-8000-000000000003"
const val STAMP = "2026-11-10T12:00:00Z"

val sampleQuestions = listOf(
    SurveyQuestion(Q1, 1, Dimension.KNOWLEDGE, QuestionType.MULTIPLE_CHOICE, "¿Cuándo lavar?", listOf("Nunca", "Siempre")),
    SurveyQuestion(Q2, 2, Dimension.ATTITUDE, QuestionType.LIKERT, "Es importante", listOf("1", "2", "3", "4", "5")),
    SurveyQuestion(Q3, 3, Dimension.PRACTICE, QuestionType.FREQUENCY, "Me lavo", listOf("1", "2", "3", "4", "5")),
)
val sampleSurvey = Survey("CAP-BPM", "CAP", VERSION_ID, 1, sampleQuestions)
val sampleDocument = ConsentDocument("v1", "Consentimiento", "Texto completo", "Dra. X", STAMP)
val validAnswers = mapOf(Q1 to 1, Q2 to 4, Q3 to 5)

fun sampleParticipation(valid: Boolean = true, submissions: List<SubmissionRecord> = emptyList(), withdrawn: Boolean = false) = Participation(
    Participant("P-7K2M9QXA", if (withdrawn) ParticipantStatus.WITHDRAWN else ParticipantStatus.ACTIVE), sampleDocument,
    ConsentRecord("v1", valid, STAMP, "Dra. X"), valid, if (valid) sampleSurvey else null, submissions)

class FakeParticipationRepository : ParticipationRepository {
    var participation: ResearchResult<Participation> = ResearchResult.Success(sampleParticipation())
    var consent: (Boolean) -> ResearchResult<Participation> = { accepted -> ResearchResult.Success(sampleParticipation(valid = accepted)) }
    var submitResult: (SurveySubmission) -> ResearchResult<SubmissionReceipt> = { ResearchResult.Success(SubmissionReceipt(it.id, it.moment, it.instrumentVersionId, STAMP)) }
    val submissions = mutableListOf<SurveySubmission>()
    val consents = mutableListOf<Pair<String, Boolean>>()
    var loads = 0

    override suspend fun participation(org: String): ResearchResult<Participation> { loads++; return participation }
    override suspend fun recordConsent(org: String, documentVersion: String, accepted: Boolean): ResearchResult<Participation> {
        consents += documentVersion to accepted
        return consent(accepted)
    }
    override suspend fun submit(org: String, submission: SurveySubmission): ResearchResult<SubmissionReceipt> {
        submissions += submission
        return submitResult(submission)
    }
}
