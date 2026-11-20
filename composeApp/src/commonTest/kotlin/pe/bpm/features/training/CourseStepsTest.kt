package pe.bpm.features.training

import pe.bpm.core.designsystem.BpmStepState
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.assignmentMeta
import pe.bpm.features.training.presentation.assignmentProgress
import pe.bpm.features.training.presentation.courseRing
import pe.bpm.features.training.presentation.continueAssignment
import pe.bpm.features.training.presentation.courseMainActionLabel
import pe.bpm.features.training.presentation.lessonStepStates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CourseStepsTest {
    private val lessons = listOf("a", "b", "c", "d").mapIndexed { index, id -> TrainingLesson(id, "Lección $id", "texto", index + 1, null) }
    private fun course(status: ProgressStatus, last: String? = null, lessons: List<TrainingLesson> = this.lessons) =
        sampleCourse().copy(lessons = lessons, progress = TrainingProgress(status, last, null, 0, null))

    private val done = BpmStepState.Done
    private val current = BpmStepState.Current
    private val pending = BpmStepState.Pending

    @Test fun notStartedMarksOnlyTheFirstLessonAsCurrent() {
        assertEquals(listOf(current, pending, pending, pending), lessonStepStates(course(ProgressStatus.NOT_STARTED)))
    }

    @Test fun inProgressMarksPreviousAsDoneAndFollowingAsPending() {
        assertEquals(listOf(done, done, current, pending), lessonStepStates(course(ProgressStatus.IN_PROGRESS, "c")))
        assertEquals(listOf(current, pending, pending, pending), lessonStepStates(course(ProgressStatus.IN_PROGRESS, "a")))
    }

    @Test fun inProgressWithoutAKnownLastLessonStartsAtTheFirst() {
        assertEquals(listOf(current, pending, pending, pending), lessonStepStates(course(ProgressStatus.IN_PROGRESS, null)))
        assertEquals(listOf(current, pending, pending, pending), lessonStepStates(course(ProgressStatus.IN_PROGRESS, "retirada")))
    }

    @Test fun completedMarksEveryLessonAsDone() {
        assertEquals(listOf(done, done, done, done), lessonStepStates(course(ProgressStatus.COMPLETED, "b")))
    }

    private fun serverCourse(status: ProgressStatus, last: String?, completed: List<String>, percent: Int) =
        sampleCourse().copy(lessons = lessons, progress = TrainingProgress(status, last, null, 0, null, completed, completed.size, lessons.size, percent))

    @Test fun serverProgressUsesCompletedLessonIdsInsteadOfTheApproximation() {
        // Saltó la lección b: la aproximación diría que a y b están hechas; el servidor dice que solo a y c.
        assertEquals(listOf(done, current, done, pending), lessonStepStates(serverCourse(ProgressStatus.IN_PROGRESS, "b", listOf("a", "c"), 50)))
        // La última abierta ya terminada: la actual es la primera sin terminar.
        assertEquals(listOf(done, done, current, pending), lessonStepStates(serverCourse(ProgressStatus.IN_PROGRESS, "b", listOf("a", "b"), 50)))
        // Con el servidor sin nada terminado y sin última lección, la primera es la actual.
        assertEquals(listOf(current, pending, pending, pending), lessonStepStates(serverCourse(ProgressStatus.NOT_STARTED, null, emptyList(), 0)))
        assertEquals(listOf(done, done, done, done), lessonStepStates(serverCourse(ProgressStatus.COMPLETED, "d", listOf("a"), 100)))
    }

    @Test fun ringUsesThePercentWhenTheServerSendsIt() {
        val server = courseRing(serverCourse(ProgressStatus.IN_PROGRESS, "b", listOf("a"), 25))!!
        assertEquals("25 %", server.label)
        assertEquals(0.25f, server.progress)
        assertEquals("100 %", courseRing(serverCourse(ProgressStatus.COMPLETED, "d", listOf("a"), 40))!!.label)
        // Sin avance del servidor: lecciones hechas según la aproximación.
        val approx = courseRing(course(ProgressStatus.IN_PROGRESS, "c"))!!
        assertEquals("2/4", approx.label)
        assertEquals(0.5f, approx.progress)
        assertNull(courseRing(course(ProgressStatus.IN_PROGRESS, "a", emptyList())))
    }

    @Test fun assignmentProgressIsHiddenUntilTheServerReportsIt() {
        val base = assignment("a", AssignmentProgress.IN_PROGRESS)
        assertNull(assignmentProgress(base))
        assertEquals(0.6f, assignmentProgress(base.copy(percent = 60)))
        assertEquals(1f, assignmentProgress(base.copy(percent = 140)))
    }

    @Test fun courseWithoutLessonsHasNoSteps() {
        assertEquals(emptyList(), lessonStepStates(course(ProgressStatus.IN_PROGRESS, "a", emptyList())))
    }

    @Test fun mainActionFollowsTheProgress() {
        assertEquals("Empezar el curso", courseMainActionLabel(course(ProgressStatus.NOT_STARTED)))
        assertEquals("Continuar lección 3", courseMainActionLabel(course(ProgressStatus.IN_PROGRESS, "c")))
        assertEquals("Empezar el curso", courseMainActionLabel(course(ProgressStatus.IN_PROGRESS, null)))
        assertEquals("Repasar el curso", courseMainActionLabel(course(ProgressStatus.COMPLETED, "d")))
    }

    private fun assignment(id: String, status: AssignmentProgress, dueAt: String? = null, best: Int? = null) = MyAssignment(
        id, TrainingCourseInfo(id, id.uppercase(), "Curso $id", "", 30), dueAt, "2026-11-01T00:00:00Z", status, ProgressStatus.NOT_STARTED, best, null)

    @Test fun continueOnlyOffersAssignmentsInProgress() {
        val inProgress = assignment("b", AssignmentProgress.IN_PROGRESS, "2026-12-01T00:00:00Z")
        val sooner = assignment("c", AssignmentProgress.IN_PROGRESS, "2026-11-25T00:00:00Z")
        assertEquals("c", continueAssignment(listOf(assignment("a", AssignmentProgress.OVERDUE), inProgress, sooner))?.assignmentId)
        assertNull(continueAssignment(listOf(assignment("a", AssignmentProgress.PENDING), assignment("d", AssignmentProgress.COMPLETED))))
    }

    @Test fun assignmentMetaUsesOnlyRealData() {
        assertEquals("30 min", assignmentMeta(assignment("a", AssignmentProgress.PENDING)))
        assertEquals("30 min · mejor puntaje 85", assignmentMeta(assignment("a", AssignmentProgress.COMPLETED, best = 85)))
    }
}
