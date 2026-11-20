package pe.bpm.features.training

import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAssignment
import pe.bpm.features.training.domain.ProgressStatus
import pe.bpm.features.training.domain.TrainingCourseInfo
import pe.bpm.features.training.presentation.assignmentChip
import pe.bpm.features.training.presentation.assignmentsForToday
import pe.bpm.features.training.presentation.nextAssignment
import pe.bpm.features.training.presentation.readableDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkerTodayTest {
    private fun assignment(id: String, status: AssignmentProgress, dueAt: String? = null) = MyAssignment(
        id, TrainingCourseInfo(id, id.uppercase(), "Curso $id", "", 10), dueAt, "2026-11-01T00:00:00Z", status,
        if (status == AssignmentProgress.IN_PROGRESS) ProgressStatus.IN_PROGRESS else ProgressStatus.NOT_STARTED, null, null)

    @Test fun readableDateUsesDayAndShortMonth() {
        assertEquals("25 nov", readableDate("2026-11-25T10:00:00Z"))
        assertEquals("2 dic", readableDate("2026-12-02"))
        assertEquals("", readableDate(null))
        assertEquals("sin-fecha", readableDate("sin-fecha"))
    }

    @Test fun nextAssignmentPrefersInProgressThenOverdueThenPendingByDueDate() {
        val pending = assignment("a", AssignmentProgress.PENDING, "2026-11-20T00:00:00Z")
        val overdue = assignment("b", AssignmentProgress.OVERDUE, "2026-11-10T00:00:00Z")
        val progress = assignment("c", AssignmentProgress.IN_PROGRESS, "2026-12-30T00:00:00Z")
        assertEquals("c", nextAssignment(listOf(pending, overdue, progress))?.assignmentId)
        assertEquals("b", nextAssignment(listOf(pending, overdue))?.assignmentId)
        val later = assignment("d", AssignmentProgress.PENDING, "2026-12-01T00:00:00Z")
        val undated = assignment("e", AssignmentProgress.PENDING)
        assertEquals("a", nextAssignment(listOf(undated, later, pending))?.assignmentId)
        assertEquals("e", nextAssignment(listOf(undated))?.assignmentId)
    }

    @Test fun completedOrEmptyHasNoNextStep() {
        assertNull(nextAssignment(emptyList()))
        assertNull(nextAssignment(listOf(assignment("x", AssignmentProgress.COMPLETED))))
    }

    @Test fun todayListPutsCompletedLastAndLimitsToThree() {
        val list = listOf(assignment("done", AssignmentProgress.COMPLETED), assignment("p", AssignmentProgress.PENDING),
            assignment("o", AssignmentProgress.OVERDUE), assignment("i", AssignmentProgress.IN_PROGRESS))
        assertEquals(listOf("i", "o", "p"), assignmentsForToday(list).map { it.assignmentId })
    }

    @Test fun chipReflectsStatusAndDueDate() {
        assertEquals("Vencida" to BpmTone.Danger, assignmentChip(assignment("a", AssignmentProgress.OVERDUE, "2026-11-01T00:00:00Z")))
        assertEquals("Completada" to BpmTone.Success, assignmentChip(assignment("a", AssignmentProgress.COMPLETED)))
        assertEquals("Vence 25 nov" to BpmTone.Warning, assignmentChip(assignment("a", AssignmentProgress.PENDING, "2026-11-25T00:00:00Z")))
        assertEquals("Pendiente" to BpmTone.Neutral, assignmentChip(assignment("a", AssignmentProgress.PENDING)))
    }
}
