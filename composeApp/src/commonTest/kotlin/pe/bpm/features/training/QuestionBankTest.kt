package pe.bpm.features.training

import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import pe.bpm.core.designsystem.BpmPathNodeState
import pe.bpm.features.training.data.*
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*

/** Banco oficial de preguntas: DTO, camino por unidades, práctica con alternativas barajadas, repaso, evaluación sorteada y banco de administración. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestionBankTest {
    // ---- DTO ----------------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }
    private val baseCourse = """"course":{"id":"$COURSE_ID","code":"BPM-M01","title":"Módulo 1","description":"x","estimatedDurationMinutes":25},
        "version":{"id":"$DRAW_VERSION","versionNumber":3,"passingScore":80},
        "lessons":[{"id":"$UNIT_1","title":"U1","content":"x","displayOrder":1,"keyPoints":["Idea"]}],
        "questions":[{"id":"$BQ_1","lessonId":"$UNIT_1","questionText":"¿Qué?","options":["a","b","c","d","e"],"displayOrder":1}],
        "progress":{"status":"NOT_STARTED","experiencePoints":0},"certificate":null"""

    @Test fun courseWithBankAndReviewParsesAndAnOlderServerStillWorks() {
        val withBank = json.decodeFromString<Envelope<CourseDetailDto>>("""{"data":{$baseCourse,"questionBank":{"version":1,"itemsPerAttempt":5,"poolSize":20},"review":{"dueCount":2,"questionIds":["$BQ_1"]}}}""").data.domain(COURSE_ID)
        assertEquals(QuestionBankInfo(1, 5, 20), withBank.bank)
        assertEquals(ReviewInfo(2, listOf(BQ_1)), withBank.review)
        val older = json.decodeFromString<Envelope<CourseDetailDto>>("""{"data":{$baseCourse}}""").data.domain(COURSE_ID)
        assertNull(older.bank)
        assertEquals(ReviewInfo(), older.review)
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString<Envelope<CourseDetailDto>>("""{"data":{$baseCourse,"review":{"dueCount":1,"questionIds":["no-es-uuid"]}}}""").data.domain(COURSE_ID)
        }
    }

    @Test fun studyFeedbackAndResultsCarryTheItemAndItsSources() {
        val study = json.decodeFromString<StudyDto>("""{"correct":true,"correctOption":1,"explanation":"j","itemCode":"M01-03","bloom":"Aplicar","sources":[{"code":"P01","label":"NTS 142 (P01)","title":"t","url":"https://x.pe/a"}],"xpEarned":2}""")
        assertEquals("M01-03", checkedItemCode(study.itemCode))
        assertEquals(listOf(SourceRef("P01", "NTS 142 (P01)", "t", "https://x.pe/a")), study.sources.domain())
        // Un enlace inseguro se descarta y un código fuera del banco se rechaza.
        assertEquals("", SourceDto("P01", "NTS", "t", "http://x.pe").domain().url)
        assertFailsWith<IllegalArgumentException> { checkedItemCode("M09-01") }
        val result = json.decodeFromString<ResultDto>("""{"questionId":"$BQ_1","selectedOption":3,"correct":false,"correctOption":0,"explanation":"j","itemCode":"M01-01","questionText":"¿Qué?","options":["a","b","c","d","e"],"sources":[]}""")
        assertEquals(5, result.options.size)
        val old = json.decodeFromString<ResultDto>("""{"questionId":"$BQ_1","selectedOption":0,"correct":true,"correctOption":0}""")
        assertNull(old.itemCode); assertTrue(old.options.isEmpty())
    }

    @Test fun sourceLineJoinsTheShortLabels() {
        assertEquals("Fuente: NTS 142 (P01) · Codex CXC 1-1969 (C01)", sourceLine(listOf(p01, c01)))
        assertEquals("", sourceLine(emptyList()))
    }

    @Test fun failureCodesMapToPublicReasons() {
        assertEquals(TrainingFailure.EVALUATION_NOT_STARTED, failureFor(409, "TRAINING_EVALUATION_NOT_STARTED"))
        assertEquals(TrainingFailure.NO_QUESTION_BANK, failureFor(422, "TRAINING_NO_QUESTION_BANK"))
        assertEquals(TrainingFailure.VERSION_OUTDATED, failureFor(409, "TRAINING_VERSION_OUTDATED"))
        assertEquals(TrainingFailure.INVALID, failureFor(422, "OTRO"))
    }

    // ---- Camino por unidades ------------------------------------------------------------------

    @Test fun bankPathHasOneLabelledNodePerUnitAndAnEvaluationTrophy() {
        val nodes = pathNodes(bankCourse(done = listOf(UNIT_1)), canEvaluate = true)
        assertEquals(listOf(BpmPathNodeState.Done, BpmPathNodeState.Current, BpmPathNodeState.Trophy), nodes.map { it.state })
        assertEquals("1. Inocuidad, peligros y riesgos · 2 preguntas", nodes[0].caption)
        assertEquals("2. Lavado de manos · 1 pregunta", nodes[1].caption)
        assertEquals("Evaluación del módulo · 5 preguntas · aprueba con 4/5", nodes[2].caption)
        assertFalse(nodes[2].enabled)
        assertTrue(nodes[0].label.contains("2 preguntas") && nodes[0].label.endsWith("completada"))
        // Con todas las unidades hechas el trofeo se abre.
        assertTrue(pathNodes(bankCourse(done = listOf(UNIT_1, UNIT_2)), true).last().enabled)
    }

    @Test fun reviewNodeShowsOnlyWithDueItemsAndMembership() {
        val due = bankCourse(done = listOf(UNIT_1), due = listOf(BQ_1, BQ_2))
        val nodes = pathNodes(due, canEvaluate = true)
        assertEquals(listOf(PathNodeKind.LESSON, PathNodeKind.LESSON, PathNodeKind.REVIEW, PathNodeKind.FINAL), nodes.map { it.kind })
        assertEquals("Repaso · 2 por repasar", nodes[2].caption)
        assertTrue(nodes[2].enabled && nodes[2].lessonId == null)
        assertEquals("Repaso · 1 por repasar", reviewCaption(1))
        assertTrue(pathNodes(bankCourse(done = listOf(UNIT_1)), true).none { it.kind == PathNodeKind.REVIEW })
        assertTrue(pathNodes(due, canEvaluate = false).none { it.kind == PathNodeKind.REVIEW })
    }

    @Test fun classicCoursesKeepTheirPathWithoutCaptionsOrReview() {
        val classic = sampleCourse()
        val nodes = pathNodes(classic.copy(review = ReviewInfo(3, listOf(QUESTION_1))), canEvaluate = true)
        assertEquals(3, nodes.size)
        assertTrue(nodes.all { it.caption.isEmpty() })
    }

    @Test fun passingFractionAndStartBubble() {
        assertEquals("4/5", passingFraction(bankCourse()))
        assertEquals(4, passingCorrect(5, 80)); assertEquals(5, passingCorrect(5, 81)); assertEquals(1, passingCorrect(1, 80))
        assertEquals("Empezar", startBubbleLabel(bankCourse()))
        assertEquals("Continuar", startBubbleLabel(bankCourse(done = listOf(UNIT_1))))
        assertEquals("Evaluación del módulo · aprueba con 4/5", evaluationChipLabel(AttemptType.FINAL, 80, "4/5"))
        assertEquals("Evaluación final · aprueba con 80", evaluationChipLabel(AttemptType.FINAL, 80))
    }

    @Test fun resultReviewPrefersTheBankPayloadAndFallsBackToTheCourse() {
        val course = bankCourse()
        val bank = QuestionResult(BQ_1, 2, false, 4, "j", itemCode = "M01-01", questionText = "Texto del servidor", options = listOf("x", "y", "z", "w", "v"))
        assertEquals(ResultReview("Texto del servidor", "z", "v"), resultReview(course, bank))
        val classic = QuestionResult(BQ_1, 0, false, 1, "j")
        assertEquals(ResultReview("¿Qué es inocuo?", "A uno", "B dos"), resultReview(course, classic))
    }

    // ---- Práctica con alternativas barajadas ---------------------------------------------------

    @Test fun studyStateMapsDisplayedPositionsBackToTheOriginalIndex() {
        val q = bankCourse().questions.first()
        val study = StudyState(listOf(q), shuffles = mapOf(q.id to listOf(3, 0, 4, 1, 2)))
        assertEquals(listOf("D cuatro", "A uno", "E cinco", "B dos", "C tres"), study.shownOptions(q))
        assertEquals(4, study.originalOption(q, 2))
        assertEquals(2, study.shownPosition(q, 4))
        for (shown in q.options.indices) assertEquals(shown, study.shownPosition(q, study.originalOption(q, shown)))
        // Sin barajar (curso común) las posiciones coinciden.
        val plain = StudyState(listOf(q))
        assertEquals(q.options, plain.shownOptions(q)); assertEquals(3, plain.originalOption(q, 3)); assertEquals(3, plain.shownPosition(q, 3))
    }

    private fun vm(repo: FakeTrainingRepository, withDraw: Boolean = true): TrainingViewModel {
        var n = 0
        return TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo), SubmitTrainingAttempt(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP },
            startDraw = if (withDraw) StartTrainingEvaluation(repo) else null, random = Random(7))
    }

    private suspend fun TestScope.withVm(repo: FakeTrainingRepository, withDraw: Boolean = true, block: suspend (TrainingViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = vm(repo, withDraw)
        try { model.open("user", "org", COURSE_ID); advanceUntilIdle(); block(model) } finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private fun bankRepo(due: List<String> = emptyList()) = FakeTrainingRepository().apply { course = TrainingResult.Success(bankCourse(due = due)) }

    @Test fun practiceSendsTheOriginalIndexAndShowsTheCorrectAnswerWhereItWasDisplayed() = runTest {
        val repo = bankRepo().apply { study = { _, selected -> TrainingResult.Success(StudyFeedback(selected == 1, 1, "Justificación", "M01-01", listOf(p01), 2)) } }
        withVm(repo) { model ->
            model.startStudy(UNIT_1, repeatMissed = true)
            val study = model.state.value.study!!
            assertEquals(2, study.questions.size)
            assertTrue(study.shuffles.values.all { it.sorted() == listOf(0, 1, 2, 3, 4) })
            assertTrue(study.shuffles.values.any { it != listOf(0, 1, 2, 3, 4) }, "alguna pregunta debe barajarse")
            val question = study.current!!
            // Elige la alternativa mostrada en la posición de la correcta (índice original 1): debe ser correcta.
            val shownCorrect = study.shownPosition(question, 1)
            model.answerStudy(shownCorrect); advanceUntilIdle()
            assertEquals(question.id to 1, repo.studyCalls.last())
            val feedback = model.state.value.study!!.feedback!!
            assertTrue(feedback.correct)
            assertEquals(shownCorrect, feedback.correctOption)
            assertEquals(2, model.state.value.study!!.xpEarned)
            // Un fallo: la posición correcta mostrada coincide con la del texto de la alternativa original 1.
            model.nextStudyQuestion()
            val second = model.state.value.study!!
            val wrongShown = (second.shownPosition(second.current!!, 1) + 1) % 5
            model.answerStudy(wrongShown); advanceUntilIdle()
            val after = model.state.value.study!!
            assertFalse(after.feedback!!.correct)
            assertEquals("B dos", after.shownOptions(second.current!!)[after.feedback!!.correctOption])
            // La pregunta fallada se añade una vez al final para repasarla.
            assertEquals(3, after.questions.size)
        }
    }

    @Test fun classicCoursesDoNotShuffle() = runTest {
        withVm(FakeTrainingRepository()) { model ->
            model.startStudy(LESSON_1)
            assertTrue(model.state.value.study!!.shuffles.isEmpty())
        }
    }

    @Test fun reviewPracticesOnlyTheDueQuestions() = runTest {
        withVm(bankRepo(due = listOf(BQ_3, BQ_1))) { model ->
            model.startReview()
            val study = model.state.value.study!!
            assertTrue(study.review && study.repeatMissed)
            assertEquals(listOf(BQ_3, BQ_1), study.questions.map { it.id })
            assertEquals(TrainingPane.STUDY, model.state.value.screen)
        }
        withVm(bankRepo()) { model ->
            model.startReview()
            assertNull(model.state.value.study)
            assertNotNull(model.state.value.notice)
        }
    }

    @Test fun lessonEvaluationIsReplacedByPracticeInBankCourses() = runTest {
        withVm(bankRepo()) { model ->
            model.startModuleEvaluation(UNIT_1)
            assertNull(model.state.value.evaluation)
        }
    }

    // ---- Evaluación sorteada -----------------------------------------------------------------

    @Test fun finalEvaluationDrawsOnTheServerAndSubmitsWithTheDrawId() = runTest {
        val repo = bankRepo()
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL)
            assertTrue(model.state.value.evaluation!!.starting)
            advanceUntilIdle()
            val evaluation = model.state.value.evaluation!!
            assertFalse(evaluation.starting)
            val drawn = evaluation.drawn!!
            assertEquals(1, repo.drawCalls.size)
            assertEquals(drawn.id, repo.drawCalls.single().first)
            assertEquals(drawn.questions, model.state.value.evaluationQuestions)
            assertFalse(model.canSubmitEvaluation())
            // Responde por posición mostrada; el intento usa el id y la versión del sorteo.
            drawn.questions.forEachIndexed { i, q -> model.answerEvaluation(q.id, i) }
            assertTrue(model.canSubmitEvaluation())
            model.submitEvaluation(); advanceUntilIdle()
            val sent = repo.attempts.single()
            assertEquals(drawn.id, sent.id)
            assertEquals(drawn.versionId, sent.versionId)
            assertEquals(AttemptType.FINAL, sent.attemptType)
            assertEquals(drawn.questions.map { it.id }.toSet(), sent.answers.map { it.questionId }.toSet())
            assertNotNull(model.state.value.evaluation?.outcome)
            // Intentar otra vez pide un sorteo nuevo (rotación) con otro id.
            model.closeEvaluation(); model.startEvaluation(AttemptType.FINAL); advanceUntilIdle()
            assertEquals(2, repo.drawCalls.size)
            assertNotEquals(repo.drawCalls[0].first, repo.drawCalls[1].first)
        }
    }

    @Test fun drawFailureOffersARetryAndNeverFakesAnEvaluation() = runTest {
        val repo = bankRepo().apply { draw = { _, _ -> TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL); advanceUntilIdle()
            val failed = model.state.value.evaluation!!
            assertNull(failed.drawn)
            assertEquals(TrainingFailure.CONNECTION, failed.failure)
            assertTrue(model.state.value.evaluationQuestions.isEmpty())
            assertEquals("Necesitas conexión para iniciar la evaluación.", drawFailureMessage(failed.failure))
            assertFalse(model.canSubmitEvaluation())
            repo.draw = { id, type -> TrainingResult.Success(sampleDraw(id, type)) }
            model.retryDraw(); advanceUntilIdle()
            assertNotNull(model.state.value.evaluation?.drawn)
            assertNull(model.state.value.evaluation?.failure)
        }
    }

    @Test fun withoutADrawerTheBankEvaluationCannotStart() = runTest {
        withVm(bankRepo(), withDraw = false) { model ->
            model.startEvaluation(AttemptType.FINAL); advanceUntilIdle()
            assertNull(model.state.value.evaluation?.drawn)
            assertEquals(TrainingFailure.UNAVAILABLE, model.state.value.evaluation?.failure)
        }
    }

    @Test fun anOutdatedVersionRedrawsWithTheCurrentQuestions() = runTest {
        val repo = bankRepo().apply { attemptResults.add { TrainingResult.Failure(TrainingFailure.VERSION_OUTDATED) } }
        withVm(repo) { model ->
            model.startEvaluation(AttemptType.FINAL); advanceUntilIdle()
            val first = model.state.value.evaluation!!.drawn!!
            first.questions.forEachIndexed { i, q -> model.answerEvaluation(q.id, i) }
            model.submitEvaluation(); advanceUntilIdle()
            assertEquals(2, repo.drawCalls.size)
            val again = model.state.value.evaluation!!.drawn
            assertNotNull(again)
            assertNotEquals(first.id, again.id)
        }
    }

    @Test fun useCaseBuildsTheDrawnAttemptAndRejectsMismatches() {
        val drawn = sampleDraw("50000000-0000-4000-8000-000000000001", AttemptType.FINAL)
        val submit = SubmitTrainingAttempt(FakeTrainingRepository())
        val answers = drawn.questions.associate { it.id to 0 }
        val attempt = submit.prepare(bankCourse(), AttemptType.FINAL, answers, { "60000000-0000-4000-8000-000000000001" }, STAMP, STAMP, drawn = drawn)!!
        assertEquals(drawn.id, attempt.id); assertEquals(drawn.versionId, attempt.versionId); assertNull(attempt.lessonId)
        assertNull(submit.prepare(bankCourse(), AttemptType.DIAGNOSTIC, answers, { "x" }, STAMP, STAMP, drawn = drawn))
        assertNull(submit.prepare(bankCourse(), AttemptType.FINAL, answers.toList().drop(1).toMap(), { "x" }, STAMP, STAMP, drawn = drawn))
        assertNull(submit.prepare(bankCourse(), AttemptType.FINAL, answers.mapValues { 9 }, { "x" }, STAMP, STAMP, drawn = drawn))
    }

    // ---- Banco de administración -------------------------------------------------------------

    private fun authoring(repo: FakeTrainingRepository, withBank: Boolean = true) = TrainingAuthoringViewModel(ListAdminCourses(repo), CreateTrainingCourse(repo), LoadTrainingDraft(repo),
        SaveTrainingDraft(repo), PublishTrainingCourse(repo), UploadLessonVideo(repo), loadBank = if (withBank) LoadQuestionBank(repo) else null) { "id" }

    private suspend fun TestScope.withAuthoring(repo: FakeTrainingRepository, block: suspend (TrainingAuthoringViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = authoring(repo)
        try { model.open("admin", "org", true); advanceUntilIdle(); block(model) } finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun adminOpensTheBankReadOnlyGroupedByUnit() = runTest {
        val repo = FakeTrainingRepository()
        withAuthoring(repo) { model ->
            model.openBank(COURSE_ID, "Módulo 1"); advanceUntilIdle()
            val view = model.state.value.bank!!
            assertFalse(view.loading)
            assertEquals(listOf(COURSE_ID), repo.bankCalls)
            val bank = view.bank!!
            assertEquals(3, bank.items.size)
            assertEquals(listOf("Inocuidad, peligros y riesgos" to 2, "Lavado de manos" to 1), bankUnits(bank).map { it.first to it.second.size })
            assertEquals("B", bank.items[0].key); assertEquals("E", optionLetter(bank.items[2].correctOption))
            assertEquals(2, bank.items.count { it.recommended })
            model.closeBank()
            assertNull(model.state.value.bank)
        }
    }

    @Test fun adminSeesAnoticeWhenTheCourseHasNoBankAndAFailureOtherwise() = runTest {
        val repo = FakeTrainingRepository().apply { bank = TrainingResult.Failure(TrainingFailure.NO_QUESTION_BANK) }
        withAuthoring(repo) { model ->
            model.openBank(COURSE_ID, "Curso común"); advanceUntilIdle()
            assertNull(model.state.value.bank)
            assertEquals("Este curso no usa el banco oficial de preguntas.", model.state.value.notice)
            repo.bank = TrainingResult.Failure(TrainingFailure.CONNECTION)
            model.openBank(COURSE_ID, "Curso"); advanceUntilIdle()
            assertEquals(TrainingFailure.CONNECTION, model.state.value.bank?.failure)
        }
    }

    @Test fun officialDraftQuestionsAreReadOnly() {
        val official = DraftQuestion(QUESTION_1, LESSON_1, "Texto oficial", listOf("a", "b"), 1, "j", 1, itemCode = "M01-03")
        val draft = sampleDraft().copy(questions = listOf(official))
        assertTrue(official.official)
        assertEquals(draft, TrainingDraftEdits.updateQuestion(draft, QUESTION_1, text = "Cambiado"))
        assertEquals(draft, TrainingDraftEdits.setOption(draft, QUESTION_1, 0, "otra"))
        assertEquals(draft, TrainingDraftEdits.setCorrect(draft, QUESTION_1, 0))
        assertEquals(draft, TrainingDraftEdits.removeQuestion(draft, QUESTION_1))
        val common = sampleDraft()
        assertEquals("Cambiado", TrainingDraftEdits.updateQuestion(common, QUESTION_1, text = "Cambiado").questions.single().questionText)
        assertTrue(TrainingDraftEdits.removeQuestion(common, QUESTION_1).questions.isEmpty())
    }

    @Test fun draftDtoKeepsTheItemCodeAndRejectsUnknownOnes() {
        val dto = json.decodeFromString<DraftQuestionDto>("""{"id":"$QUESTION_1","lessonId":null,"questionText":"P","options":["a","b"],"correctOption":1,"explanation":"e","displayOrder":1,"itemCode":"M02-07"}""")
        assertEquals("M02-07", checkedItemCode(dto.itemCode))
        assertNull(json.decodeFromString<DraftQuestionDto>("""{"id":"$QUESTION_1","questionText":"P","options":["a","b"],"correctOption":1,"displayOrder":1}""").itemCode)
    }

    @Test
    fun `la justificacion repetida de la respuesta correcta no se vuelve a mostrar`() {
        assertFalse(showExplanation("Cuando no causa daño.", "Cuando no causa daño"))
        assertFalse(showExplanation("", "Algo"))
        assertTrue(showExplanation("Porque la norma lo exige.", "Cuando no causa daño"))
    }
}
