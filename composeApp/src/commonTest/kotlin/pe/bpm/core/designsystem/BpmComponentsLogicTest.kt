package pe.bpm.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BpmComponentsLogicTest {
    private fun answered(total: Int, vararg done: Int) = List(total) { (it + 1) in done }

    @Test fun summaryWithNoAnswersListsFirstThreeMissingAndTheRest() {
        assertEquals("0 de 10 respondidas · faltan 1, 2, 3 y 7 más", questionNavigatorSummary(answered(10)))
    }

    @Test fun summaryListsAllMissingWhenThreeOrFewer() {
        assertEquals("7 de 10 respondidas · faltan 8, 9 y 10", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6, 7)))
        assertEquals("8 de 10 respondidas · faltan 9 y 10", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6, 7, 8)))
        assertEquals("9 de 10 respondidas · falta 10", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
    }

    @Test fun summaryWithMoreThanThreeMissingAbbreviates() {
        assertEquals("6 de 10 respondidas · faltan 7, 8, 9 y 1 más", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6)))
        assertEquals("3 de 10 respondidas · faltan 4, 5, 6 y 4 más", questionNavigatorSummary(answered(10, 1, 2, 3)))
    }

    @Test fun summaryWhenAllAnsweredOrEmpty() {
        assertEquals("Todas respondidas", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
        assertEquals("Sin preguntas", questionNavigatorSummary(emptyList()))
    }

    @Test fun summaryIgnoresWhichQuestionIsCurrent() {
        // La actual cuenta como respondida solo si tiene respuesta: el resumen depende únicamente de `answered`.
        val marks = questionMarks(listOf(true, false, true), current = 1)
        assertEquals(listOf(BpmQuestionMark.Answered, BpmQuestionMark.Current, BpmQuestionMark.Answered), marks)
        assertEquals("2 de 3 respondidas · falta 2", questionNavigatorSummary(listOf(true, false, true)))
    }

    @Test fun marksDistinguishThreeStates() {
        assertEquals(listOf(BpmQuestionMark.Answered, BpmQuestionMark.Unanswered, BpmQuestionMark.Current),
            questionMarks(listOf(true, false, true), current = 2))
    }

    @Test fun supportPaneWidthByWindowClass() {
        assertNull(supportPaneWidth(BpmWindowClass.Compact))
        assertEquals(260.dp, supportPaneWidth(BpmWindowClass.Medium))
        assertEquals(300.dp, supportPaneWidth(BpmWindowClass.Expanded))
        assertEquals(300.dp, supportPaneWidth(BpmWindowClass.Large))
    }
}
