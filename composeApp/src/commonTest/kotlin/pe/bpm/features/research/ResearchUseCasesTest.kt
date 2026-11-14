package pe.bpm.features.research

import kotlin.test.*
import pe.bpm.features.research.data.JsonInstrumentImportParser
import pe.bpm.features.research.domain.*

class ResearchUseCasesTest {
    private val ids = generateSequence(1) { it + 1 }.map { "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}" }.iterator()
    private val submit = SubmitSurvey(FakeParticipationRepository())

    @Test fun prepareFixesIdentifiersAndKeepsQuestionOrder() {
        val prepared = submit.prepare(sampleParticipation(), SurveyMoment.PRETEST, validAnswers, { ids.next() }, STAMP)!!
        assertNotEquals(prepared.id, prepared.clientOperationId)
        assertEquals(listOf(Q1, Q2, Q3), prepared.answers.map { it.questionId })
        assertEquals(VERSION_ID, prepared.instrumentVersionId)
    }

    @Test fun prepareRejectsIncompleteExtraOrOutOfRangeAnswers() {
        fun prepare(answers: Map<String, Int>, p: Participation = sampleParticipation(), m: SurveyMoment = SurveyMoment.PRETEST) = submit.prepare(p, m, answers, { ids.next() }, STAMP)
        assertNull(prepare(validAnswers - Q3))
        assertNull(prepare(validAnswers + ("x" to 1)))
        assertNull(prepare(validAnswers + (Q1 to 2)))
        assertNull(prepare(validAnswers + (Q2 to 0)))
        assertNull(prepare(validAnswers, sampleParticipation(valid = false)))
        assertNull(prepare(validAnswers, sampleParticipation(withdrawn = true)))
        assertNull(prepare(validAnswers, sampleParticipation(submissions = listOf(SubmissionRecord(SurveyMoment.PRETEST, VERSION_ID, STAMP)))))
        assertNotNull(prepare(validAnswers, sampleParticipation(submissions = listOf(SubmissionRecord(SurveyMoment.PRETEST, VERSION_ID, STAMP))), SurveyMoment.POSTTEST))
    }

    @Test fun scaleValuesMapToOptionPositions() {
        val likert = sampleQuestions[1]
        assertEquals(1, SurveyRules.valueForOption(likert, 0))
        assertEquals(0, SurveyRules.optionIndex(likert, 1))
        assertEquals(0, SurveyRules.valueForOption(sampleQuestions[0], 0))
    }

    @Test fun capPublicationNeedsEightEightAndTen() {
        fun q(type: QuestionType, n: Int) = List(n) { DraftQuestion(type, "p$it", if (type == QuestionType.MULTIPLE_CHOICE) listOf("a", "b") else emptyList(), if (type == QuestionType.MULTIPLE_CHOICE) 0 else null) }
        assertTrue(InstrumentRules.publishProblems(true, q(QuestionType.MULTIPLE_CHOICE, 8) + q(QuestionType.LIKERT, 8) + q(QuestionType.FREQUENCY, 10)).isEmpty())
        assertTrue(InstrumentRules.publishProblems(true, q(QuestionType.MULTIPLE_CHOICE, 7) + q(QuestionType.LIKERT, 8) + q(QuestionType.FREQUENCY, 10)).isNotEmpty())
        assertTrue(InstrumentRules.publishProblems(false, listOf(DraftQuestion(QuestionType.MULTIPLE_CHOICE, "p", listOf("a", "b"), null))).isNotEmpty())
        assertTrue(InstrumentRules.publishProblems(false, listOf(DraftQuestion(QuestionType.MULTIPLE_CHOICE, "p", listOf("a", "a"), 0))).isNotEmpty())
    }

    @Test fun importParserReadsTheOfficialFormatAndIgnoresMetadata() {
        val json = """{"code":"cap-bpm","name":"CAP","isCap":true,"source":"x","generalData":{"a":1},
            "questions":[{"type":"MULTIPLE_CHOICE","text":"P","options":["a","b"],"correctOption":null},{"type":"LIKERT","text":"L","options":["1","2","3","4","5"],"correctOption":null}]}"""
        val parsed = JsonInstrumentImportParser().parse(json)!!
        assertEquals("CAP-BPM", parsed.code)
        assertTrue(parsed.isCap)
        assertEquals(2, parsed.questions.size)
        assertNull(parsed.questions[0].correctOption)
        assertNull(JsonInstrumentImportParser().parse("no json"))
        assertNull(JsonInstrumentImportParser().parse("""{"code":"x","name":"n","questions":[{"type":"OTHER","text":"t"}]}"""))
    }
}
