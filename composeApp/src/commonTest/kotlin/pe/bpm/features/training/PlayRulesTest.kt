package pe.bpm.features.training

import androidx.compose.ui.input.key.Key
import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.designsystem.BpmPathNodeState
import pe.bpm.core.designsystem.BpmPlayKey
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.core.designsystem.bpmPlayKey
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*

/** Reglas puras del juego del personal: pasos de lección, camino, módulos, teclado y práctica con repaso. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayRulesTest {
    private fun lesson(points: List<String> = emptyList(), video: Boolean = false) =
        TrainingLesson("l1", "Lección", "Texto", 1, if (video) LessonVideo("m", "video/mp4", 1) else null, keyPoints = points)

    @Test fun lessonStepsAreVideoThenOneIdeaPerKeyPointOrTheFullText() {
        assertEquals(listOf(LessonStep.Reading), lessonSteps(lesson()))
        assertEquals(listOf(LessonStep.Video, LessonStep.Reading), lessonSteps(lesson(video = true)))
        assertEquals(listOf(LessonStep.Idea(1, 2, "Uno"), LessonStep.Idea(2, 2, "Dos")), lessonSteps(lesson(listOf(" Uno ", "", "Dos"))))
        assertEquals(LessonStep.Video, lessonSteps(lesson(listOf("Uno"), video = true)).first())
    }

    @Test fun ideaIconFollowsKeywordsAndFallsBackInRotation() {
        assertEquals(BpmSymbol.Thermometer, ideaSymbol("Frío a 5 °C o menos", 0))
        assertEquals(BpmSymbol.Hand, ideaSymbol("Lávate las manos 20 segundos", 1))
        assertEquals(BpmSymbol.Spray, ideaSymbol("Desinfecta la mesa", 2))
        assertEquals(BpmSymbol.Star, ideaSymbol("Algo sin palabra clave", 0))
        assertEquals(BpmSymbol.Bolt, ideaSymbol("Algo sin palabra clave", 1))
        assertEquals(BpmSymbol.Star, ideaSymbol("Algo sin palabra clave", 4))
    }

    @Test fun sequenceProgressIsClamped() {
        assertEquals(0f, sequenceProgress(0, 0))
        assertEquals(0.5f, sequenceProgress(2, 4))
        assertEquals(1f, sequenceProgress(9, 4))
    }

    private fun course(done: List<String>, status: ProgressStatus = ProgressStatus.IN_PROGRESS, questions: Boolean = true) = sampleCourse().copy(
        questions = if (questions) sampleCourse().questions else emptyList(),
        progress = TrainingProgress(status, null, null, 0, null, completedLessonIds = done, completedLessons = done.size, totalLessons = 2, percent = 0))

    @Test fun pathHasDoneCurrentLockedAndALockedTrophyUntilAllLessonsAreDone() {
        val nodes = pathNodes(course(listOf(LESSON_1)), canEvaluate = true)
        assertEquals(listOf(BpmPathNodeState.Done, BpmPathNodeState.Current, BpmPathNodeState.Trophy), nodes.map { it.state })
        assertEquals(listOf(true, true, false), nodes.map { it.enabled })
        assertEquals(LESSON_2, nodes[1].lessonId)
        assertNull(nodes.last().lessonId)
        val none = pathNodes(course(emptyList()), true)
        assertEquals(listOf(BpmPathNodeState.Current, BpmPathNodeState.Locked, BpmPathNodeState.Trophy), none.map { it.state })
        assertFalse(none[1].enabled)
    }

    @Test fun trophyOpensOnlyWithEveryLessonDoneQuestionsAndMembership() {
        val all = listOf(LESSON_1, LESSON_2)
        assertTrue(pathNodes(course(all), true).last().enabled)
        assertFalse(pathNodes(course(all), false).last().enabled)
        assertFalse(pathNodes(course(all, questions = false), true).last().enabled)
        assertTrue(pathNodes(course(emptyList(), ProgressStatus.COMPLETED), true).all { it.state == BpmPathNodeState.Done || it.state == BpmPathNodeState.Trophy })
    }

    @Test fun modulesFollowTheCaminoOrderWithCompletedLast() {
        val done = sampleMyAssignment(AssignmentProgress.COMPLETED, "c-done")
        val progress = sampleMyAssignment(AssignmentProgress.IN_PROGRESS, "c-progress")
        val pending = sampleMyAssignment(AssignmentProgress.PENDING, "c-pending")
        val list = listOf(done, pending, progress)
        assertEquals(listOf("c-progress", "c-pending", "c-done"), pathOrder(list).map { it.course.id })
        assertEquals(1 to 3, moduleOf(list, "c-progress"))
        assertEquals(3 to 3, moduleOf(list, "C-DONE"))
        assertNull(moduleOf(list, "otro"))
    }

    @Test fun dailyGoalIsOneLesson() {
        assertEquals("0/1", lessonGoalLabel(false))
        assertEquals("1/1", lessonGoalLabel(true))
    }

    @Test fun keyboardMapsDigitsToOptionsAndEnterToConfirm() {
        assertEquals(BpmPlayKey.Choose(0), bpmPlayKey(Key.One))
        assertEquals(BpmPlayKey.Choose(8), bpmPlayKey(Key.NumPad9))
        assertEquals(BpmPlayKey.Confirm, bpmPlayKey(Key.Enter))
        assertEquals(BpmPlayKey.Confirm, bpmPlayKey(Key.NumPadEnter))
        assertNull(bpmPlayKey(Key.Zero))
        assertNull(bpmPlayKey(Key.A))
    }

    // ---- Práctica con repaso y fin de lección -------------------------------------------------------

    private suspend fun TestScope.withVm(repo: FakeTrainingRepository, block: suspend (TrainingViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var n = 0
        val model = TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo), SubmitTrainingAttempt(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP })
        try { model.open("user", "org", COURSE_ID); advanceUntilIdle(); block(model) }
        finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun missedQuestionIsRepeatedOnceAtTheEndWhenRequested() = runTest {
        withVm(FakeTrainingRepository()) { model ->
            model.startStudy(LESSON_1, repeatMissed = true)
            model.answerStudy(0); advanceUntilIdle() // la correcta es la opción 1
            assertEquals(2, model.state.value.study?.questions?.size)
            assertEquals(1, model.state.value.study?.originalCount)
            model.nextStudyQuestion()
            assertFalse(model.state.value.study!!.finished)
            model.answerStudy(0); advanceUntilIdle() // vuelve a fallar: no se repite otra vez
            assertEquals(2, model.state.value.study?.questions?.size)
            model.nextStudyQuestion()
            assertTrue(model.state.value.study!!.finished)
            assertEquals(0, model.state.value.study?.accuracyPercent)
        }
    }

    @Test fun firstTryAnswersCountForAccuracyAndNothingIsRepeated() = runTest {
        withVm(FakeTrainingRepository()) { model ->
            model.startStudy(LESSON_1, repeatMissed = true)
            model.answerStudy(1); advanceUntilIdle()
            assertEquals(1, model.state.value.study?.questions?.size)
            model.nextStudyQuestion()
            assertTrue(model.state.value.study!!.finished)
            assertEquals(100, model.state.value.study?.accuracyPercent)
            model.restartStudy()
            assertTrue(model.state.value.study!!.repeatMissed)
        }
    }

    @Test fun studyWithoutTheGameFlagNeverAddsRepeats() = runTest {
        withVm(FakeTrainingRepository()) { model ->
            model.startStudy(LESSON_1)
            model.answerStudy(0); advanceUntilIdle()
            assertEquals(1, model.state.value.study?.questions?.size)
        }
    }

    @Test fun completeLessonSavesProgressWithoutChangingTheScreen() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.completeLesson(LESSON_2); advanceUntilIdle()
            assertEquals(TrainingPane.OVERVIEW, model.state.value.screen)
            assertEquals(listOf<String?>(LESSON_2), repo.completedCalls)
            assertTrue(LESSON_2 in model.state.value.course!!.progress.completedLessonIds)
            model.completeLesson("inexistente"); advanceUntilIdle()
            assertEquals(1, repo.completedCalls.size)
        }
    }

    @Test fun learnerLoadsEachAssignedCourseForTheCamino() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val plus = FakePlusRepository().apply {
            myAssignmentsResult = { TrainingResult.Success(TrainingPage(listOf(sampleMyAssignment(course = COURSE_ID), sampleMyAssignment(course = "10000000-0000-4000-8000-000000000002")), null)) }
        }
        val training = FakeTrainingRepository().apply { course = TrainingResult.Success(sampleCourse()) }
        val vm = TrainingLearnerViewModel(LoadMyAssignments(plus), LoadMyAchievements(plus), LoadLearningPaths(plus), loadCourse = LoadTrainingCourse(training))
        try {
            vm.open("u", "org", true); advanceUntilIdle()
            vm.loadPathCourses(); advanceUntilIdle()
            assertEquals(2, training.courseCalls.size)
            assertEquals(setOf(COURSE_ID, "10000000-0000-4000-8000-000000000002"), vm.state.value.pathCourses.keys)
            assertFalse(vm.state.value.pathCoursesLoading)
            training.course = TrainingResult.Failure(TrainingFailure.CONNECTION)
            vm.loadPathCourses(); advanceUntilIdle()
            assertEquals(2, vm.state.value.pathCoursesFailed.size)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun learnerWithoutMembershipOrLoaderLoadsNoCourses() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val plus = FakePlusRepository()
        val training = FakeTrainingRepository()
        val noMember = TrainingLearnerViewModel(LoadMyAssignments(plus), LoadMyAchievements(plus), LoadLearningPaths(plus), loadCourse = LoadTrainingCourse(training))
        val noLoader = TrainingLearnerViewModel(LoadMyAssignments(plus), LoadMyAchievements(plus), LoadLearningPaths(plus))
        try {
            noMember.open("u", "org", false); noMember.loadPathCourses(); noLoader.open("u", "org", true); advanceUntilIdle(); noLoader.loadPathCourses(); advanceUntilIdle()
            assertTrue(training.courseCalls.isEmpty())
            assertTrue(noLoader.state.value.pathCourses.isEmpty())
        } finally { noMember.viewModelScope.cancel(); noLoader.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
