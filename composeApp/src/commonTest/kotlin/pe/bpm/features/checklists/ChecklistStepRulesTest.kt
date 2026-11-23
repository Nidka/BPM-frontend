package pe.bpm.features.checklists

import pe.bpm.features.checklists.domain.ChecklistItem
import pe.bpm.features.checklists.domain.ChecklistResponseType
import pe.bpm.features.checklists.domain.ChecklistRun
import pe.bpm.features.checklists.domain.RunAnswerInput
import pe.bpm.features.checklists.domain.RunFailure
import pe.bpm.features.checklists.presentation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecklistStepRulesTest {
    private fun item(id: String, type: ChecklistResponseType = ChecklistResponseType.COMPLIANCE, required: Boolean = true, min: Double? = null, max: Double? = null, unit: String = "") =
        ChecklistItem(id, "Higiene", "Pregunta $id", type, 1, required, unit, min, max, false)

    private val compliance = item("c")
    private val temperature = item("t", ChecklistResponseType.TEMPERATURE, min = 0.0, max = 5.0, unit = "°C")

    @Test fun answeredFollowsTheResponseType() {
        assertFalse(stepAnswered(compliance, RunAnswerInput()))
        assertTrue(stepAnswered(compliance, RunAnswerInput("NOT_APPLICABLE")))
        assertFalse(stepAnswered(compliance, RunAnswerInput("quizá")))
        assertTrue(stepAnswered(temperature, RunAnswerInput("3,8")))
        assertFalse(stepAnswered(temperature, RunAnswerInput("tres")))
        assertTrue(stepAnswered(item("x", ChecklistResponseType.TEXT), RunAnswerInput("Todo en orden")))
        assertFalse(stepAnswered(item("x", ChecklistResponseType.TEXT), RunAnswerInput("  ")))
    }

    @Test fun anOptionalQuestionCanBeSkippedButARequiredOneCannot() {
        assertFalse(stepCanAdvance(compliance, RunAnswerInput()))
        assertTrue(stepCanAdvance(item("o", required = false), RunAnswerInput()))
        assertFalse(stepCanAdvance(temperature, RunAnswerInput("4,,")))
    }

    @Test fun failingIsNoCumpleOrOutOfRange() {
        assertTrue(stepFailing(compliance, RunAnswerInput("NON_COMPLIANT")))
        assertFalse(stepFailing(compliance, RunAnswerInput("COMPLIANT")))
        assertTrue(stepFailing(temperature, RunAnswerInput("7")))
        assertTrue(stepFailing(temperature, RunAnswerInput("-1")))
        assertFalse(stepFailing(temperature, RunAnswerInput("5")))
        assertFalse(stepFailing(temperature, RunAnswerInput("")))
    }

    @Test fun criterionIsOnlyForNumbers() {
        assertEquals("Criterio publicado: 0 a 5 °C", stepCriterion(temperature))
        assertEquals("Criterio publicado: 1,5 a 8", stepCriterion(item("n", ChecklistResponseType.NUMBER, min = 1.5, max = 8.0)))
        assertNull(stepCriterion(compliance))
    }

    @Test fun resumeStartsAtTheFirstUnansweredQuestion() {
        val items = listOf(compliance, item("d"), temperature)
        assertEquals(0, firstPendingStep(items, emptyMap()))
        assertEquals(2, firstPendingStep(items, mapOf("c" to RunAnswerInput("COMPLIANT"), "d" to RunAnswerInput("NON_COMPLIANT"))))
        assertEquals(2, firstPendingStep(items, items.associate { it.id to RunAnswerInput(if (it.id == "t") "3" else "COMPLIANT") }))
        assertEquals(2, answeredSteps(items, mapOf("c" to RunAnswerInput("COMPLIANT"), "t" to RunAnswerInput("3"))))
        assertFalse(stepsReadyToReview(items, mapOf("c" to RunAnswerInput("COMPLIANT"))))
        assertTrue(stepsReadyToReview(items + item("opt", required = false), items.associate { it.id to RunAnswerInput(if (it.id == "t") "3" else "COMPLIANT") }))
    }

    private fun state(status: String, by: String = "u1", user: String = "u1", conflicted: Boolean = false, failure: RunFailure? = null) = ChecklistRunState(
        userId = user, run = ChecklistRun("r1", "o", "l", "v", by, status, 1, listOf(compliance), emptyList()), conflicted = conflicted, failure = failure,
    )

    @Test fun theStageIsUsedForMyDraftOrAFinishedRunButNotForConflictsOrOthers() {
        assertTrue(usesStepStage(state("DRAFT"), "u1", "r1"))
        assertTrue(usesStepStage(state("SUBMITTED"), "u1", "r1"))
        assertFalse(usesStepStage(state("DRAFT", by = "otra"), "u1", "r1"))
        assertFalse(usesStepStage(state("DRAFT"), "u1", "otra-revision"))
        assertFalse(usesStepStage(state("DRAFT", conflicted = true), "u1", "r1"))
        assertFalse(usesStepStage(state("DRAFT", failure = RunFailure.CONFLICT), "u1", "r1"))
        assertFalse(usesStepStage(ChecklistRunState(userId = "u1"), "u1", "r1"))
        assertFalse(usesStepStage(state("DRAFT", user = "u2"), "u1", "r1"))
    }
}
