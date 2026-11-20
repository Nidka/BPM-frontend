package pe.bpm.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BpmComponentsLogicTest {
    private fun answered(total: Int, vararg done: Int) = List(total) { (it + 1) in done }

    @Test fun summaryCountsPendingQuestionsWithoutListingThem() {
        assertEquals("10 por responder", questionNavigatorSummary(answered(10)))
        assertEquals("3 por responder", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6, 7)))
        assertEquals("1 por responder", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6, 7, 8, 9)))
        assertEquals("5 por responder", questionNavigatorSummary(answered(8, 1, 2, 5)))
    }

    @Test fun summaryWhenAllAnsweredOrEmpty() {
        assertEquals("Todas respondidas · lista para enviar", questionNavigatorSummary(answered(10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)))
        assertEquals("Sin preguntas", questionNavigatorSummary(emptyList()))
    }

    @Test fun summaryIgnoresWhichQuestionIsCurrent() {
        // La actual cuenta como respondida solo si tiene respuesta: el resumen depende únicamente de `answered`.
        val marks = questionMarks(listOf(true, false, true), current = 1)
        assertEquals(listOf(BpmQuestionMark.Answered, BpmQuestionMark.Current, BpmQuestionMark.Answered), marks)
        assertEquals("1 por responder", questionNavigatorSummary(listOf(true, false, true)))
    }

    @Test fun marksDistinguishThreeStates() {
        assertEquals(listOf(BpmQuestionMark.Answered, BpmQuestionMark.Unanswered, BpmQuestionMark.Current),
            questionMarks(listOf(true, false, true), current = 2))
    }

    @Test fun supportPaneWidthByWindowClass() {
        assertNull(supportPaneWidth(BpmWindowClass.Compact))
        // G2: el panel de apoyo solo aparece desde Expanded (840 dp); en Medium el contenido va en una columna.
        assertNull(supportPaneWidth(BpmWindowClass.Medium))
        assertEquals(300.dp, supportPaneWidth(BpmWindowClass.Expanded))
        assertEquals(300.dp, supportPaneWidth(BpmWindowClass.Large))
    }
}
