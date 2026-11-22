package pe.bpm.core.designsystem

import kotlin.test.*

class BpmStepFormRulesTest {
    @Test fun lastStepShowsSubmitLabelAndOthersShowNext() {
        assertEquals("Siguiente", BpmStepFormRules.primaryLabel(0, 3, "Enviar invitación"))
        assertEquals("Siguiente", BpmStepFormRules.primaryLabel(1, 3, "Enviar invitación"))
        assertEquals("Enviar invitación", BpmStepFormRules.primaryLabel(2, 3, "Enviar invitación"))
    }

    @Test fun stepIndexStaysInsideWhenAStepDisappears() {
        // Invitar como ADMIN quita el paso «Sedes»: el índice 2 vuelve al último paso disponible.
        assertEquals(1, BpmStepFormRules.clamp(2, 2))
        assertTrue(BpmStepFormRules.isLast(2, 2))
        assertEquals(0, BpmStepFormRules.clamp(-1, 3))
    }

    @Test fun progressLabelNamesTheStep() {
        assertEquals("Paso 2 de 3 · ¿Qué podrá hacer?", BpmStepFormRules.progressLabel(1, 3, "¿Qué podrá hacer?"))
    }

    @Test fun serverErrorsReturnToTheFirstStepWithAProblem() {
        assertEquals(1, BpmStepFormRules.firstStepWithError(listOf(false, true, true)))
        assertNull(BpmStepFormRules.firstStepWithError(listOf(false, false)))
    }
}
