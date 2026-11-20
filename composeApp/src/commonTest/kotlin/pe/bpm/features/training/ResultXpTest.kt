package pe.bpm.features.training

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.xpEarnedChip
import pe.bpm.features.training.presentation.xpRepeatedNotice

class ResultXpTest {
    private fun attempt(type: AttemptType, passed: Boolean, xp: Int?) = AttemptSummary("a1", type, if (passed) 90 else 40, passed, STAMP, xp)

    @Test fun chipShowsOnlyPositiveXp() {
        assertEquals("+120 XP", xpEarnedChip(attempt(AttemptType.FINAL, true, 120)))
        assertNull(xpEarnedChip(attempt(AttemptType.FINAL, true, 0)))
        assertNull(xpEarnedChip(attempt(AttemptType.FINAL, true, null)))
    }

    @Test fun repeatedNoticeIsOnlyForARepeatedPassedFinal() {
        assertEquals("Sin XP nuevo: ya tenías este puntaje", xpRepeatedNotice(attempt(AttemptType.FINAL, true, 0)))
        assertNull(xpRepeatedNotice(attempt(AttemptType.FINAL, true, null)))
        assertNull(xpRepeatedNotice(attempt(AttemptType.FINAL, true, 50)))
        assertNull(xpRepeatedNotice(attempt(AttemptType.FINAL, false, 0)))
        assertNull(xpRepeatedNotice(attempt(AttemptType.MODULE, true, 0)))
        assertNull(xpRepeatedNotice(attempt(AttemptType.DIAGNOSTIC, true, 0)))
    }
}
