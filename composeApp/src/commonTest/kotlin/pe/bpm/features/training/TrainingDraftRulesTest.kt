package pe.bpm.features.training

import kotlin.test.*
import pe.bpm.features.training.domain.*

class TrainingDraftRulesTest {
    private fun kinds(draft: TrainingDraft) = TrainingDraftRules.validate(draft).map { it.kind }.toSet()

    @Test fun validDraftHasNoIssues() {
        assertTrue(TrainingDraftRules.validate(sampleDraft()).isEmpty())
        assertTrue(TrainingDraftRules.publishBlockers(sampleDraft()).isEmpty())
    }

    @Test fun lessonLimitsFollowTheContract() {
        val base = sampleDraft()
        assertEquals(setOf(DraftIssueKind.LESSON_TITLE), kinds(base.copy(lessons = listOf(base.lessons[0].copy(title = "x".repeat(201))))))
        assertEquals(emptySet(), kinds(base.copy(lessons = listOf(base.lessons[0].copy(title = "x".repeat(200))))))
        assertEquals(setOf(DraftIssueKind.LESSON_CONTENT), kinds(base.copy(lessons = listOf(base.lessons[0].copy(content = "x".repeat(20001))))))
        assertEquals(emptySet(), kinds(base.copy(lessons = listOf(base.lessons[0].copy(content = "x\n".repeat(10000))))))
        val many = (1..51).map { DraftLesson("id-$it", "L$it", "", it, null) }
        assertTrue(DraftIssueKind.LESSON_COUNT in kinds(base.copy(lessons = many, questions = emptyList())))
        assertFalse(DraftIssueKind.LESSON_COUNT in kinds(base.copy(lessons = many.take(50), questions = emptyList())))
    }

    @Test fun keyPointsFollowTheContract() {
        val base = sampleDraft()
        fun with(vararg points: String) = base.copy(lessons = listOf(base.lessons[0].copy(keyPoints = points.toList())))
        assertEquals(emptySet(), kinds(with()))
        assertEquals(emptySet(), kinds(with("Lavar", "Secar", "", "  ")))
        assertEquals(emptySet(), kinds(with("x".repeat(200))))
        assertEquals(setOf(DraftIssueKind.KEY_POINTS), kinds(with("x".repeat(201))))
        assertEquals(setOf(DraftIssueKind.KEY_POINTS), kinds(with("1", "2", "3", "4", "5", "6")))
        assertEquals(setOf(DraftIssueKind.KEY_POINTS), kinds(with("Lavar", " lavar ")))
        assertEquals(setOf(DraftIssueKind.KEY_POINTS), kinds(with("dos\nlíneas")))
        // Los campos vacíos no cuentan para el tope.
        assertEquals(emptySet(), kinds(with("1", "2", "3", "4", "5", "")))
    }

    @Test fun keyPointEditsRespectTheLimitAndStaySingleLine() {
        val lesson = sampleDraft().lessons[0]
        var draft = sampleDraft()
        repeat(7) { draft = TrainingDraftEdits.addKeyPoint(draft, lesson.id) }
        assertEquals(TrainingLimits.KEY_POINTS_MAX, draft.lessons[0].keyPoints.size)
        draft = TrainingDraftEdits.setKeyPoint(draft, lesson.id, 1, "uno\ndos")
        assertEquals("uno dos", draft.lessons[0].keyPoints[1])
        draft = TrainingDraftEdits.removeKeyPoint(draft, lesson.id, 0)
        assertEquals(4, draft.lessons[0].keyPoints.size)
        assertEquals("uno dos", draft.lessons[0].keyPoints[0])
        // Al guardar se recortan y se descartan los vacíos.
        assertEquals(listOf("uno dos"), draft.normalized().lessons[0].keyPoints)
    }

    @Test fun questionLimitsFollowTheContract() {
        val q = sampleDraft().questions[0]
        fun with(question: DraftQuestion) = sampleDraft().copy(questions = listOf(question))
        assertTrue(DraftIssueKind.OPTION_COUNT in kinds(with(q.copy(options = listOf("solo"), correctOption = 0))))
        assertTrue(DraftIssueKind.OPTION_COUNT in kinds(with(q.copy(options = List(7) { "o$it" }))))
        assertEquals(emptySet(), kinds(with(q.copy(options = List(6) { "o$it" }, correctOption = 5))))
        assertTrue(DraftIssueKind.OPTION_TEXT in kinds(with(q.copy(options = listOf("a", "x".repeat(301))))))
        assertTrue(DraftIssueKind.OPTION_TEXT in kinds(with(q.copy(options = listOf("a", "  ")))))
        assertTrue(DraftIssueKind.CORRECT_OPTION in kinds(with(q.copy(correctOption = 2))))
        assertTrue(DraftIssueKind.QUESTION_LESSON in kinds(with(q.copy(lessonId = "inexistente"))))
        assertTrue(DraftIssueKind.QUESTION_TEXT in kinds(with(q.copy(questionText = " "))))
        assertEquals(emptySet(), kinds(with(q.copy(lessonId = null))))
        val tooMany = (1..101).map { q.copy(id = "q$it") }
        assertTrue(DraftIssueKind.QUESTION_COUNT in kinds(sampleDraft().copy(questions = tooMany)))
    }

    @Test fun duplicateOptionsAndOversizedBodiesAreRejected() {
        val q = sampleDraft().questions[0]
        assertTrue(DraftIssueKind.DUPLICATE_OPTION in kinds(sampleDraft().copy(questions = listOf(q.copy(options = listOf("a", " a "), correctOption = 0)))))
        val big = sampleDraft().copy(lessons = (1..50).map { DraftLesson("l$it", "L", "x".repeat(20000), it, null) }, questions = emptyList())
        assertTrue(DraftIssueKind.BODY_SIZE in kinds(big))
    }

    @Test fun passingScoreAndDuplicatesAreChecked() {
        assertTrue(DraftIssueKind.PASSING_SCORE in kinds(sampleDraft().copy(passingScore = 101)))
        assertTrue(DraftIssueKind.PASSING_SCORE in kinds(sampleDraft().copy(passingScore = -1)))
        val base = sampleDraft()
        assertTrue(DraftIssueKind.DUPLICATE_ID in kinds(base.copy(lessons = base.lessons + base.lessons)))
    }

    @Test fun publishNeedsALessonAndAQuestion() {
        assertEquals(listOf("Agrega al menos una lección.", "Agrega al menos una pregunta."), TrainingDraftRules.publishBlockers(sampleDraft().copy(lessons = emptyList(), questions = emptyList())))
        assertTrue(TrainingDraftRules.publishBlockers(sampleDraft().copy(passingScore = 500)).isNotEmpty())
    }

    @Test fun newCourseRules() {
        assertTrue(TrainingDraftRules.validateNewCourse(NewCourse("M-01", "Higiene", "", 15)).isEmpty())
        assertEquals(setOf(DraftIssueKind.COURSE_CODE, DraftIssueKind.COURSE_TITLE, DraftIssueKind.COURSE_DURATION),
            TrainingDraftRules.validateNewCourse(NewCourse("", "a", "", 0)).map { it.kind }.toSet())
        assertTrue(TrainingDraftRules.validateNewCourse(NewCourse("M".repeat(50), "Higiene", "", 15)).isEmpty())
        assertTrue(TrainingDraftRules.validateNewCourse(NewCourse("M".repeat(51), "Higiene", "", 15)).isNotEmpty())
        // El backend exige mayúsculas y no admite punto: el cliente rechaza antes de enviar.
        assertTrue(TrainingDraftRules.validateNewCourse(NewCourse("m-01", "Higiene", "", 15)).isNotEmpty())
        assertTrue(TrainingDraftRules.validateNewCourse(NewCourse("M.01", "Higiene", "", 15)).isNotEmpty())
        assertTrue(TrainingDraftRules.validateNewCourse(NewCourse("M-01", "Higiene", "", 1441)).isNotEmpty())
        assertTrue(TrainingDraftRules.validateNewCourse(NewCourse("M-01", "Higiene", "x".repeat(2001), 15)).isNotEmpty())
    }

    @Test fun videoRulesCheckNameTypeSizeAndSignature() {
        val mp4 = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0, 0, 0, 0)
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0)
        assertNull(TrainingVideoRules.validate("a.mp4", "video/mp4", mp4))
        assertNull(TrainingVideoRules.validate("a.webm", "video/webm", webm))
        assertNotNull(TrainingVideoRules.validate("a.mp4", "video/webm", mp4))
        assertNotNull(TrainingVideoRules.validate("a.avi", "video/avi", mp4))
        assertNotNull(TrainingVideoRules.validate("../a.mp4", "video/mp4", mp4))
        assertNotNull(TrainingVideoRules.validate("a.mp4", "video/mp4", byteArrayOf()))
        assertEquals("video/mp4", TrainingVideoRules.mimeForName("CLASE.MP4"))
        assertNull(TrainingVideoRules.mimeForName("clase.mov"))
    }

    @Test fun editsAddMoveAndRemoveKeepOrderAndReferences() {
        var draft = sampleDraft()
        draft = TrainingDraftEdits.addLesson(draft, LESSON_2)
        assertEquals(listOf(1, 2), draft.lessons.map { it.displayOrder })
        draft = TrainingDraftEdits.moveLesson(draft, LESSON_2, -1)
        assertEquals(listOf(LESSON_2, LESSON_1), draft.lessons.map { it.id })
        assertEquals(listOf(1, 2), draft.lessons.map { it.displayOrder })
        draft = TrainingDraftEdits.removeLesson(draft, LESSON_1)
        assertNull(draft.questions.single().lessonId)
        draft = TrainingDraftEdits.addQuestion(draft, QUESTION_2, LESSON_2)
        assertEquals(2, draft.questions.last().options.size)
        draft = TrainingDraftEdits.removeQuestion(draft, QUESTION_1)
        assertEquals(listOf(1), draft.questions.map { it.displayOrder })
    }

    @Test fun optionEditsKeepTheCorrectAnswerConsistent() {
        var draft = sampleDraft().copy(questions = listOf(sampleDraft().questions[0].copy(options = listOf("a", "b", "c"), correctOption = 1)))
        draft = TrainingDraftEdits.removeOption(draft, QUESTION_1, 0)
        assertEquals(listOf("b", "c"), draft.questions[0].options)
        assertEquals(0, draft.questions[0].correctOption)
        // No se puede bajar de dos opciones ni superar seis.
        assertEquals(draft, TrainingDraftEdits.removeOption(draft, QUESTION_1, 0))
        repeat(10) { draft = TrainingDraftEdits.addOption(draft, QUESTION_1) }
        assertEquals(TrainingLimits.MAX_OPTIONS, draft.questions[0].options.size)
        draft = TrainingDraftEdits.setCorrect(draft, QUESTION_1, 5)
        assertEquals(5, draft.questions[0].correctOption)
        draft = TrainingDraftEdits.removeOption(draft, QUESTION_1, 5)
        assertEquals(0, draft.questions[0].correctOption)
    }
}
