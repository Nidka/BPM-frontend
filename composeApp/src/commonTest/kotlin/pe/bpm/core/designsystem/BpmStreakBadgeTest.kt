package pe.bpm.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class BpmStreakBadgeTest {
    @Test fun titleAndHintFollowTheStreak() {
        assertEquals("Sin racha todavía", streakTitle(0))
        assertEquals("1 día seguido", streakTitle(1))
        assertEquals("12 días seguidos", streakTitle(12))
        assertEquals("Hoy ya aprendiste. ¡Sigue así!", streakHint(3, true))
        assertEquals("Aprende hoy para no perder tu racha.", streakHint(3, false))
        assertEquals("Aprende hoy para empezar una racha.", streakHint(0, false))
    }

    @Test fun weekMarksEndTodayOrYesterday() {
        val off = false
        val on = true
        // Aprendió hoy: la racha termina en el último punto.
        assertEquals(listOf(off, off, off, off, on, on, on), streakWeekMarks(3, activeToday = true))
        // Racha vigente sin actividad hoy: termina ayer y el punto de hoy queda vacío.
        assertEquals(listOf(off, off, off, on, on, on, off), streakWeekMarks(3, activeToday = false))
        assertEquals(List(7) { on }, streakWeekMarks(30, activeToday = true))
        assertEquals(listOf(on, on, on, on, on, on, off), streakWeekMarks(30, activeToday = false))
        assertEquals(List(7) { off }, streakWeekMarks(0, activeToday = false))
        assertEquals(listOf(off, off, off, off, off, off, on), streakWeekMarks(1, activeToday = true))
    }
}
