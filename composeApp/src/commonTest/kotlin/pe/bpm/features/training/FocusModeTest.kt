package pe.bpm.features.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pe.bpm.features.training.domain.AttemptType
import pe.bpm.features.training.presentation.*

class FocusModeTest {
    private fun evaluation(outcomeQueued: Boolean = false, submitting: Boolean = false) =
        EvaluationState(AttemptType.FINAL, "2026-11-20T10:00:00Z", queued = outcomeQueued, submitting = submitting)

    @Test fun onlyTheCourseOverviewKeepsNavigation() {
        assertFalse(isFocusMode(TrainingState(screen = TrainingPane.OVERVIEW)))
        for (pane in listOf(TrainingPane.LESSON, TrainingPane.STUDY, TrainingPane.EVALUATION))
            assertTrue(isFocusMode(TrainingState(screen = pane)), "$pane debe ocultar la navegación")
    }

    @Test fun leavingAnUnsentEvaluationAsksForConfirmation() {
        assertTrue(needsExitConfirmation(TrainingState(screen = TrainingPane.EVALUATION, evaluation = evaluation())))
        assertTrue(needsExitConfirmation(TrainingState(screen = TrainingPane.EVALUATION, evaluation = evaluation().copy(reviewing = true))))
    }

    @Test fun otherScreensLeaveWithoutConfirmation() {
        assertFalse(needsExitConfirmation(TrainingState(screen = TrainingPane.LESSON)))
        assertFalse(needsExitConfirmation(TrainingState(screen = TrainingPane.STUDY)))
        assertFalse(needsExitConfirmation(TrainingState(screen = TrainingPane.EVALUATION, evaluation = evaluation(outcomeQueued = true))))
        assertFalse(needsExitConfirmation(TrainingState(screen = TrainingPane.EVALUATION, evaluation = evaluation(submitting = true))))
        assertEquals(false, needsExitConfirmation(TrainingState()))
    }
}
