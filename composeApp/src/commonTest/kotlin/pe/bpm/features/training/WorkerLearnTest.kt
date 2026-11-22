package pe.bpm.features.training

import kotlin.test.*
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.activeCourseId

/** Nodo «Empezar» del camino: solo el primer curso sin terminar y con lección siguiente. */
class WorkerLearnTest {
    private fun course(id: String, done: List<String> = emptyList(), status: ProgressStatus = ProgressStatus.IN_PROGRESS): TrainingCourse =
        sampleCourse().let { base ->
            base.copy(
                course = base.course.copy(id = id),
                progress = TrainingProgress(status, null, null, 0, null, completedLessonIds = done, completedLessons = done.size, totalLessons = 2, percent = 0),
            )
        }

    @Test fun firstUnfinishedCourseWithANextLessonIsActive() {
        val first = course("a", listOf(LESSON_1))
        val second = course("b")
        assertEquals("a", activeCourseId(listOf(first, second)))
    }

    @Test fun completedAndFullyDoneCoursesAreSkipped() {
        val completed = course("done", listOf(LESSON_1, LESSON_2), ProgressStatus.COMPLETED)
        val allLessonsDone = course("sin-trofeo", listOf(LESSON_1, LESSON_2))
        val next = course("siguiente")
        assertEquals("siguiente", activeCourseId(listOf(completed, allLessonsDone, next)))
    }

    @Test fun aNotYetLoadedCourseStopsTheSearchSoTheBubbleNeverJumps() {
        assertNull(activeCourseId(listOf(null, course("b"))))
        assertNull(activeCourseId(listOf(course("a", listOf(LESSON_1, LESSON_2)), null)))
    }

    @Test fun noActiveCourseWhenEverythingIsCompletedOrEmpty() {
        assertNull(activeCourseId(emptyList()))
        assertNull(activeCourseId(listOf(course("x", listOf(LESSON_1, LESSON_2), ProgressStatus.COMPLETED))))
    }
}
