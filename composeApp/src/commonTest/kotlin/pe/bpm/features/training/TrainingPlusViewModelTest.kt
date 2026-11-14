package pe.bpm.features.training

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*

internal const val ASSIGNMENT_ID = "90000000-0000-4000-8000-000000000001"
internal const val ACHIEVEMENT_ID = "a0000000-0000-4000-8000-000000000001"
internal const val PATH_ID = "b0000000-0000-4000-8000-000000000001"

internal fun sampleAssignment(status: AssignmentRecordStatus = AssignmentRecordStatus.ACTIVE, id: String = ASSIGNMENT_ID, target: AssignmentTarget = AssignmentTarget.User("uid", "Ana")) =
    Assignment(id, AssignmentCourse(COURSE_ID, "M-01", "Higiene"), target, null, status, STAMP, null)

internal fun sampleMyAssignment(status: AssignmentProgress = AssignmentProgress.PENDING, course: String = COURSE_ID) = MyAssignment(
    "90000000-0000-4000-8000-0000000000aa", TrainingCourseInfo(course, "M-01", "Higiene", "", 15), "2099-12-31T23:59:00Z", STAMP, status, ProgressStatus.NOT_STARTED, null, null)

internal fun sampleAchievement(awarded: Boolean = false) = Achievement(ACHIEVEMENT_ID, "FIRST_COURSE", "Primer curso", "Completa un curso", 20, AchievementCriterion.COURSES_COMPLETED, 1, true, if (awarded) STAMP else null)

internal fun samplePath(rowVersion: String = "1", status: PathStatus = PathStatus.DRAFT) = LearningPath(PATH_ID, "Inducción", "", status, rowVersion, null,
    listOf(PathCourse(COURSE_ID, "M-01", "Higiene", 1, CourseStatus.PUBLISHED, null)), null)

/** Repositorio falso programable de asignaciones, insignias, rutas y verificación. */
internal class FakePlusRepository : TrainingLearnerRepository, TrainingPlusAdminRepository {
    var myAssignmentsResult: (String?) -> TrainingResult<TrainingPage<MyAssignment>> = { TrainingResult.Success(TrainingPage(listOf(sampleMyAssignment()), null)) }
    val myAssignmentCalls = mutableListOf<String?>()
    var achievementsResult: TrainingResult<MyAchievements> = TrainingResult.Success(MyAchievements(ExperienceTotals(35, 20, 5, 10), listOf(sampleAchievement(true))))
    var pathsResult: TrainingResult<TrainingPage<LearningPath>> = TrainingResult.Success(TrainingPage(listOf(samplePath(status = PathStatus.PUBLISHED)), null))
    var pathResult: TrainingResult<LearningPath> = TrainingResult.Success(samplePath(status = PathStatus.PUBLISHED))

    override suspend fun myAssignments(org: String, pageToken: String?): TrainingResult<TrainingPage<MyAssignment>> { myAssignmentCalls += pageToken; return myAssignmentsResult(pageToken) }
    override suspend fun myAchievements(org: String) = achievementsResult
    override suspend fun paths(org: String, pageToken: String?) = pathsResult
    override suspend fun path(org: String, pathId: String) = pathResult

    val created = mutableListOf<NewAssignment>()
    var createResult: (NewAssignment) -> TrainingResult<Assignment> = { TrainingResult.Success(sampleAssignment(id = it.id)) }
    var listResult: (AssignmentRecordStatus?, String?, String?) -> TrainingResult<TrainingPage<Assignment>> = { _, _, _ -> TrainingResult.Success(TrainingPage(listOf(sampleAssignment()), null)) }
    val listCalls = mutableListOf<Triple<AssignmentRecordStatus?, String?, String?>>()
    val cancelled = mutableListOf<String>()
    override suspend fun createAssignment(org: String, input: NewAssignment): TrainingResult<Assignment> { created += input; return createResult(input) }
    override suspend fun assignments(org: String, status: AssignmentRecordStatus?, courseId: String?, pageToken: String?): TrainingResult<TrainingPage<Assignment>> {
        listCalls += Triple(status, courseId, pageToken); return listResult(status, courseId, pageToken)
    }
    override suspend fun cancelAssignment(org: String, assignmentId: String): TrainingResult<Assignment> {
        cancelled += assignmentId; return TrainingResult.Success(sampleAssignment(AssignmentRecordStatus.CANCELLED, assignmentId))
    }

    val updatedAchievements = mutableListOf<AchievementEdit>()
    override suspend fun achievements(org: String): TrainingResult<List<Achievement>> = TrainingResult.Success(listOf(sampleAchievement()))
    override suspend fun updateAchievement(org: String, achievementId: String, edit: AchievementEdit): TrainingResult<Achievement> {
        updatedAchievements += edit
        return TrainingResult.Success(sampleAchievement().copy(name = edit.name, points = edit.points, active = edit.active))
    }

    val savedPaths = mutableListOf<PathInput>()
    var pathSave: (PathInput) -> TrainingResult<LearningPath> = { TrainingResult.Success(samplePath(rowVersion = "2")) }
    var published = 0
    var archived = 0
    override suspend fun adminPaths(org: String, status: PathStatus?, pageToken: String?): TrainingResult<TrainingPage<LearningPath>> = TrainingResult.Success(TrainingPage(listOf(samplePath()), null))
    override suspend fun adminPath(org: String, pathId: String) = TrainingResult.Success(samplePath(rowVersion = "9"))
    override suspend fun createPath(org: String, input: PathInput): TrainingResult<LearningPath> { savedPaths += input; return pathSave(input) }
    override suspend fun updatePath(org: String, input: PathInput): TrainingResult<LearningPath> { savedPaths += input; return pathSave(input) }
    override suspend fun publishPath(org: String, pathId: String): TrainingResult<LearningPath> { published++; return TrainingResult.Success(samplePath("3", PathStatus.PUBLISHED)) }
    override suspend fun archivePath(org: String, pathId: String): TrainingResult<LearningPath> { archived++; return TrainingResult.Success(samplePath("4", PathStatus.ARCHIVED)) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingPlusViewModelTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val publishedCourse = AdminCourse(COURSE_ID, "M-01", "Higiene", CourseStatus.PUBLISHED, 1, false)
    private fun ids(): () -> String { var n = 0; return { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" } }

    private suspend fun TestScope.scoped(vararg models: ViewModelHolder, block: suspend () -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { models.forEach { it.close() }; Dispatchers.resetMain() }
    }

    private class ViewModelHolder(val close: () -> Unit)
    private fun androidx.lifecycle.ViewModel.holder() = ViewModelHolder { viewModelScope.cancel() }

    // ---- Asignaciones y logros de quien aprende ---------------------------------------------

    @Test fun learnerLoadsAssignmentsWithStatusAndPaginatesWithoutDuplicates() = runTest {
        val repo = FakePlusRepository().apply {
            myAssignmentsResult = { token ->
                if (token == null) TrainingResult.Success(TrainingPage(listOf(sampleMyAssignment(AssignmentProgress.OVERDUE)), "next"))
                else TrainingResult.Success(TrainingPage(listOf(sampleMyAssignment(AssignmentProgress.OVERDUE), sampleMyAssignment(course = "10000000-0000-4000-8000-000000000002")), null))
            }
        }
        val vm = TrainingLearnerViewModel(LoadMyAssignments(repo), LoadMyAchievements(repo), LoadLearningPaths(repo))
        scoped(vm.holder()) {
            vm.open("u", "org", true); advanceUntilIdle()
            assertEquals(AssignmentProgress.OVERDUE, vm.state.value.assignments.single().status)
            assertEquals("next", vm.state.value.assignmentsNext)
            vm.moreAssignments(); advanceUntilIdle()
            assertEquals(2, vm.state.value.assignments.size)
            assertNull(vm.state.value.assignmentsNext)
            // Reabrir el mismo contexto solo refresca.
            vm.open("u", "org", true); advanceUntilIdle()
            assertEquals(listOf(null, "next", null), repo.myAssignmentCalls)
        }
    }

    @Test fun superAdminWithoutMembershipNeverRequestsPersonalData() = runTest {
        val repo = FakePlusRepository()
        val vm = TrainingLearnerViewModel(LoadMyAssignments(repo), LoadMyAchievements(repo), LoadLearningPaths(repo))
        scoped(vm.holder()) {
            vm.open("u", "org", false); vm.loadAchievements(); advanceUntilIdle()
            assertTrue(repo.myAssignmentCalls.isEmpty())
            assertNull(vm.state.value.achievements)
            vm.loadPaths(); advanceUntilIdle()
            assertEquals(1, vm.state.value.paths.size)
        }
    }

    @Test fun assignmentFailureIsReportedAndLateResultsFromAnotherUserAreDiscarded() = runTest {
        val repo = FakePlusRepository().apply { myAssignmentsResult = { TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        val vm = TrainingLearnerViewModel(LoadMyAssignments(repo), LoadMyAchievements(repo), LoadLearningPaths(repo))
        scoped(vm.holder()) {
            vm.open("u", "org", true); advanceUntilIdle()
            assertEquals(TrainingFailure.CONNECTION, vm.state.value.assignmentsFailure)
            repo.myAssignmentsResult = { TrainingResult.Success(TrainingPage(listOf(sampleMyAssignment()), null)) }
            vm.refreshAssignments(); advanceUntilIdle()
            assertNull(vm.state.value.assignmentsFailure)
            vm.open("otro", "org", true)
            assertTrue(vm.state.value.assignments.isEmpty())
            vm.reset(); advanceUntilIdle()
            assertNull(vm.state.value.userId)
        }
    }

    @Test fun achievementsAndPathDetailLoadAndBackClosesDetail() = runTest {
        val repo = FakePlusRepository()
        val vm = TrainingLearnerViewModel(LoadMyAssignments(repo), LoadMyAchievements(repo), LoadLearningPaths(repo))
        scoped(vm.holder()) {
            vm.open("u", "org", true); vm.loadAchievements(); vm.loadPaths(); advanceUntilIdle()
            assertEquals(35, vm.state.value.achievements?.totals?.experiencePoints)
            assertEquals(1, vm.state.value.achievements?.earned?.size)
            vm.openPath(PATH_ID); advanceUntilIdle()
            assertEquals(PATH_ID, vm.state.value.pathDetail?.id)
            assertTrue(vm.back())
            assertNull(vm.state.value.pathDetail)
            assertFalse(vm.back())
            repo.pathResult = TrainingResult.Failure(TrainingFailure.NOT_FOUND)
            vm.openPath(PATH_ID); advanceUntilIdle()
            assertEquals(TrainingFailure.NOT_FOUND, vm.state.value.pathDetailFailure)
            assertTrue(vm.back())
        }
    }

    // ---- Administración de asignaciones ------------------------------------------------------

    private fun assignmentVm(repo: FakePlusRepository, repoCourses: FakeTrainingRepository) =
        TrainingAssignmentAdminViewModel(ListTrainingAssignments(repo), CreateTrainingAssignment(repo), CancelTrainingAssignment(repo), ListAdminCourses(repoCourses), ids()) { now }

    @Test fun adminListsWithFiltersAndPaginationAndCancels() = runTest {
        val repo = FakePlusRepository()
        val courses = FakeTrainingRepository().apply { adminCourses = TrainingResult.Success(listOf(publishedCourse)) }
        val vm = assignmentVm(repo, courses)
        scoped(vm.holder()) {
            vm.open("org", true); advanceUntilIdle()
            assertEquals(Triple(AssignmentRecordStatus.ACTIVE, null, null), repo.listCalls.last())
            vm.filterStatus(AssignmentRecordStatus.CANCELLED); advanceUntilIdle()
            vm.filterCourse(COURSE_ID); advanceUntilIdle()
            assertEquals(Triple(AssignmentRecordStatus.CANCELLED, COURSE_ID, null), repo.listCalls.last())
            vm.filterStatus(AssignmentRecordStatus.ACTIVE); advanceUntilIdle()
            vm.cancel(ASSIGNMENT_ID); advanceUntilIdle()
            assertEquals(listOf(ASSIGNMENT_ID), repo.cancelled)
            // En la vista de activas la asignación cancelada desaparece.
            assertTrue(vm.state.value.items.isEmpty())
            assertNotNull(vm.state.value.notice)
        }
    }

    @Test fun nonAdminNeverLoadsAssignments() = runTest {
        val repo = FakePlusRepository()
        val vm = assignmentVm(repo, FakeTrainingRepository())
        scoped(vm.holder()) { vm.open("org", false); advanceUntilIdle(); assertTrue(repo.listCalls.isEmpty()) }
    }

    @Test fun creationReusesTheSameIdOnRetryAndRenewsItAfterEditing() = runTest {
        val repo = FakePlusRepository().apply { createResult = { TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        val courses = FakeTrainingRepository().apply { adminCourses = TrainingResult.Success(listOf(publishedCourse)) }
        val vm = assignmentVm(repo, courses)
        scoped(vm.holder()) {
            vm.open("org", true); advanceUntilIdle()
            vm.startCreate(); advanceUntilIdle()
            // Formulario incompleto: no se envía y se muestran los problemas.
            vm.create(); advanceUntilIdle()
            assertTrue(repo.created.isEmpty())
            assertTrue(vm.state.value.form?.showIssues == true)
            vm.edit { it.copy(courseId = COURSE_ID, courseTitle = "Higiene", userId = "uid", userName = "Ana", dueDate = "2026-12-31") }
            vm.create(); advanceUntilIdle()
            assertEquals(TrainingFailure.CONNECTION, vm.state.value.formFailure)
            vm.create(); advanceUntilIdle()
            assertEquals(2, repo.created.size)
            assertEquals(repo.created[0], repo.created[1])
            assertEquals("2027-01-01T04:59:00Z", repo.created[0].dueAt)
            // Editar el contenido genera una identidad nueva.
            vm.edit { it.copy(dueDate = "2027-01-15") }
            repo.createResult = { TrainingResult.Success(sampleAssignment(id = it.id)) }
            vm.create(); advanceUntilIdle()
            assertNotEquals(repo.created[1].id, repo.created[2].id)
            assertNull(vm.state.value.form)
        }
    }

    @Test fun duplicateAssignmentsAreReportedWithoutClosingTheForm() = runTest {
        val repo = FakePlusRepository().apply { createResult = { TrainingResult.Failure(TrainingFailure.ASSIGNMENT_EXISTS) } }
        val vm = assignmentVm(repo, FakeTrainingRepository())
        scoped(vm.holder()) {
            vm.open("org", true); advanceUntilIdle(); vm.startCreate()
            vm.edit { it.copy(courseId = COURSE_ID, kind = TargetKind.ROLE, role = "CHEF") }
            vm.create(); advanceUntilIdle()
            assertEquals(TrainingFailure.ASSIGNMENT_EXISTS, vm.state.value.formFailure)
            assertNotNull(vm.state.value.form)
            assertIs<AssignmentTarget.Role>(repo.created.single().target)
            assertTrue(vm.back())
            assertNull(vm.state.value.form)
        }
    }

    // ---- Insignias ---------------------------------------------------------------------------

    @Test fun achievementEditingValidatesAndSaves() = runTest {
        val repo = FakePlusRepository()
        val vm = TrainingAchievementAdminViewModel(ListTrainingAchievements(repo), UpdateTrainingAchievement(repo))
        scoped(vm.holder()) {
            vm.open("org", true); advanceUntilIdle()
            vm.startEdit(ACHIEVEMENT_ID)
            vm.edit { it.copy(points = "5000") }
            vm.save(); advanceUntilIdle()
            assertTrue(repo.updatedAchievements.isEmpty())
            assertTrue(vm.state.value.editor?.showIssues == true)
            vm.edit { it.copy(name = " Nuevo nombre ", points = "30", active = false) }
            vm.save(); advanceUntilIdle()
            assertEquals(AchievementEdit("Nuevo nombre", "Completa un curso", 30, 1, false), repo.updatedAchievements.single())
            assertNull(vm.state.value.editor)
            assertEquals("Nuevo nombre", vm.state.value.items.single().name)
        }
    }

    // ---- Rutas -------------------------------------------------------------------------------

    private val path = { repo: FakePlusRepository, courses: FakeTrainingRepository -> LearningPathAdminViewModel(ManageLearningPaths(repo), ListAdminCourses(courses), ids()) }

    @Test fun pathCreationOrderingAndPublication() = runTest {
        val repo = FakePlusRepository()
        val courses = FakeTrainingRepository().apply { adminCourses = TrainingResult.Success(listOf(publishedCourse, publishedCourse.copy(id = "10000000-0000-4000-8000-000000000002", code = "M-02"))) }
        val vm = path(repo, courses)
        scoped(vm.holder()) {
            vm.open("org", true); advanceUntilIdle()
            vm.startCreate(); advanceUntilIdle()
            assertTrue(vm.state.value.editor!!.isNew)
            vm.save(); advanceUntilIdle()
            // Sin título no se envía.
            assertTrue(repo.savedPaths.isEmpty())
            vm.edit { it.copy(title = "Inducción") }
            vm.state.value.allCourses.forEach(vm::addCourse)
            vm.addCourse(publishedCourse)
            assertEquals(2, vm.state.value.editor!!.courses.size)
            vm.moveCourse("10000000-0000-4000-8000-000000000002", -1)
            vm.save(); advanceUntilIdle()
            assertEquals(listOf("10000000-0000-4000-8000-000000000002", COURSE_ID), repo.savedPaths.single().courseIds)
            assertNull(repo.savedPaths.single().rowVersion)
            // Tras guardar la ruta ya existe y adopta el rowVersion devuelto.
            assertEquals("2", vm.state.value.editor?.rowVersion)
            assertFalse(vm.state.value.editor!!.dirty)
            vm.publish(); advanceUntilIdle()
            assertEquals(1, repo.published)
            assertEquals(PathStatus.PUBLISHED, vm.state.value.editor?.status)
            vm.archive(); advanceUntilIdle()
            assertEquals(PathStatus.ARCHIVED, vm.state.value.editor?.status)
            // Una ruta archivada no admite cambios.
            vm.edit { it.copy(title = "Otro") }
            assertEquals("Inducción", vm.state.value.editor?.title)
        }
    }

    @Test fun staleRowVersionKeepsLocalCopyAndOffersReload() = runTest {
        val repo = FakePlusRepository().apply { pathSave = { TrainingResult.Failure(TrainingFailure.PATH_CONFLICT) } }
        val vm = path(repo, FakeTrainingRepository())
        scoped(vm.holder()) {
            vm.open("org", true); advanceUntilIdle()
            vm.openPath(samplePath("1"))
            vm.edit { it.copy(title = "Mi cambio local") }
            vm.save(); advanceUntilIdle()
            assertEquals("1", repo.savedPaths.single().rowVersion)
            assertEquals(TrainingFailure.PATH_CONFLICT, vm.state.value.editorFailure)
            assertEquals("Mi cambio local", vm.state.value.editor?.title)
            assertTrue(vm.state.value.editor!!.dirty)
            // Publicar con cambios sin guardar no está permitido.
            vm.publish(); advanceUntilIdle()
            assertEquals(0, repo.published)
            vm.reloadEditor(); advanceUntilIdle()
            assertEquals("9", vm.state.value.editor?.rowVersion)
            assertEquals("Inducción", vm.state.value.editor?.title)
            assertNull(vm.state.value.editorFailure)
        }
    }

    // ---- Verificación pública ----------------------------------------------------------------

    @Test fun verificationDistinguishesFoundNotFoundAndFailure() = runTest {
        var result: TrainingResult<CertificateVerification> = TrainingResult.Success(CertificateVerification(CertificateStatus.REVOKED, "Curso", 2, STAMP, "Org", "Ana P."))
        val repo = object : CertificateVerificationRepository { override suspend fun verify(code: String) = result }
        val vm = CertificateVerificationViewModel(VerifyTrainingCertificate(repo))
        scoped(vm.holder()) {
            vm.open("abcdefghijklmnopqrstuvwx"); advanceUntilIdle()
            val found = assertIs<VerificationOutcome.Found>(vm.state.value.outcome)
            assertEquals(CertificateStatus.REVOKED, found.certificate.status)
            vm.edit("corto"); vm.check(); advanceUntilIdle()
            assertEquals(VerificationOutcome.NotFound, vm.state.value.outcome)
            result = TrainingResult.Failure(TrainingFailure.RATE_LIMITED)
            vm.edit("ABCDEFGHIJKLMNOPQRSTUVWX"); vm.check(); advanceUntilIdle()
            assertEquals(VerificationOutcome.Failed(TrainingFailure.RATE_LIMITED), vm.state.value.outcome)
        }
    }

    // ---- Evaluación por módulo en el ViewModel de aprendizaje -------------------------------

    @Test fun moduleEvaluationOnlyAsksTheLessonQuestionsAndKeepsTheCourseOpen() = runTest {
        val repo = FakeTrainingRepository().apply {
            attemptResults += { attempt ->
                val base = (outcomeFor(attempt, passed = true) as TrainingResult.Success).value
                TrainingResult.Success(base.copy(achievements = listOf(sampleAchievement(true))))
            }
        }
        var n = 0
        val vm = TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo), SubmitTrainingAttempt(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP }, { now })
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            vm.open("u", "org", COURSE_ID); advanceUntilIdle()
            vm.startModuleEvaluation(LESSON_2)
            // La lección 2 no tiene preguntas: no se abre la evaluación.
            assertEquals(TrainingPane.OVERVIEW, vm.state.value.screen)
            assertNotNull(vm.state.value.notice)
            vm.startModuleEvaluation(LESSON_1)
            assertEquals(TrainingPane.EVALUATION, vm.state.value.screen)
            assertEquals(listOf(QUESTION_1), vm.state.value.evaluationQuestions.map { it.id })
            vm.answerEvaluation(QUESTION_2, 0)
            assertTrue(vm.state.value.evaluation!!.answers.isEmpty())
            assertFalse(vm.canSubmitEvaluation())
            vm.answerEvaluation(QUESTION_1, 1)
            assertTrue(vm.canSubmitEvaluation())
            vm.submitEvaluation(); advanceUntilIdle()
            val sent = repo.attempts.single()
            assertEquals(AttemptType.MODULE, sent.attemptType)
            assertEquals(LESSON_1, sent.lessonId)
            assertEquals(listOf(QUESTION_1), sent.answers.map { it.questionId })
            val outcome = vm.state.value.evaluation?.outcome
            assertEquals(1, outcome?.achievements?.size)
            // El módulo no completa el curso ni crea constancia.
            assertNotEquals(ProgressStatus.COMPLETED, vm.state.value.course?.progress?.status)
            assertNull(vm.state.value.course?.certificate)
            // Un módulo no se puede iniciar como evaluación de curso.
            vm.closeEvaluation()
            vm.startEvaluation(AttemptType.MODULE)
            assertNull(vm.state.value.evaluation)
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun moduleEvaluationRetriesWithTheSameIdentifiersAfterAnUncertainFailure() = runTest {
        val repo = FakeTrainingRepository().apply { attemptResults += { TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        var n = 0
        val vm = TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo), SubmitTrainingAttempt(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP }, { now })
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            vm.open("u", "org", COURSE_ID); advanceUntilIdle()
            vm.startModuleEvaluation(LESSON_1); vm.answerEvaluation(QUESTION_1, 0)
            vm.submitEvaluation(); advanceUntilIdle()
            assertEquals(TrainingFailure.CONNECTION, vm.state.value.evaluation?.failure)
            vm.submitEvaluation(); advanceUntilIdle()
            assertEquals(2, repo.attempts.size)
            assertEquals(repo.attempts[0], repo.attempts[1])
            assertEquals(LESSON_1, repo.attempts[1].lessonId)
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
