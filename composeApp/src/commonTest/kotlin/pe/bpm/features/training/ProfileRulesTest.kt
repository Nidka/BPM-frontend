package pe.bpm.features.training

import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileRulesTest {
    // 2026-11-21 es sábado; la semana va del lunes 16 al domingo 22 de noviembre de 2026.
    private val saturday = parseIsoDay("2026-11-21")!!

    private fun week(days: Int, activeToday: Boolean, last: String?, today: Long = saturday) =
        streakWeek(days, activeToday, last, today).joinToString("") { if (it.active) "x" else if (it.today) "o" else "." }

    @Test fun epochDayAndWeekdayAreRight() {
        assertEquals(0L, parseIsoDay("1970-01-01"))
        assertEquals(10_957L, parseIsoDay("2000-01-01"))
        assertEquals(3, mondayIndex(0))
        assertEquals(5, mondayIndex(saturday))
        assertEquals(parseIsoDay("2026-11-21"), parseIsoDay("2026-11-21T23:59:00Z"))
        assertEquals(null, parseIsoDay("2026-13-01"))
        assertEquals(null, parseIsoDay(null))
    }

    @Test fun streakActiveTodayEndsToday() {
        assertEquals("xxxxxx.", week(6, true, "2026-11-21"))
        // Una racha más larga que la semana marca toda la semana hasta hoy; el domingo (futuro) nunca.
        assertEquals("xxxxxx.", week(10, true, "2026-11-21"))
        assertEquals(".....x.", week(1, true, "2026-11-21"))
    }

    @Test fun streakNotYetActiveTodayEndsYesterday() {
        assertEquals("xxxxxo.", week(5, false, "2026-11-20"))
        assertEquals("...xxo.", week(2, false, "2026-11-20"))
    }

    @Test fun brokenOrEmptyStreakMarksNothing() {
        assertEquals(".....o.", week(4, false, "2026-11-18"))
        assertEquals(".....o.", week(0, false, null))
        assertEquals(".....o.", week(3, false, null))
    }

    @Test fun todayIsHighlightedEvenWhenActive() {
        val days = streakWeek(1, true, "2026-11-21", saturday)
        assertEquals(true, days[5].today && days[5].active)
        assertEquals(listOf("L", "M", "M", "J", "V", "S", "D"), days.map { it.letter })
        assertEquals("Sábado, hoy, con actividad", days[5].description)
        assertEquals("Domingo, por venir", days[6].description)
    }

    @Test fun streakWeekStartsOnMondayWhenTodayIsMonday() {
        val monday = parseIsoDay("2026-11-16")!!
        assertEquals("x......", week(1, true, "2026-11-16", monday))
        assertEquals("o......", week(0, false, null, monday))
    }

    @Test fun deviceDateFormatsOfEveryPlatform() {
        val expected = parseIsoDay("2026-11-21")
        assertEquals(expected, parseDeviceDate("21/11/2026 · 10:30 PET"))
        assertEquals(expected, parseDeviceDate("21/11/2026, 10:30"))
        assertEquals(expected, parseDeviceDate("21/11/26, 10:30"))
        assertEquals(parseIsoDay("2026-11-05"), parseDeviceDate("5/11/2026"))
        assertEquals(null, parseDeviceDate("hoy"))
    }

    @Test fun streakTitleReadsNaturally() {
        assertEquals("Empieza tu racha hoy", streakTitle(0))
        assertEquals("Racha de 1 día", streakTitle(1))
        assertEquals("Racha de 6 días", streakTitle(6))
    }

    private fun badge(id: String, criterion: AchievementCriterion, threshold: Int, awarded: String?, active: Boolean = true) =
        Achievement(id, id, id, "", 10, criterion, threshold, active, awarded)

    @Test fun badgeCriterionText() {
        assertEquals("Completa 1 curso", badgeCriterion(badge("a", AchievementCriterion.COURSES_COMPLETED, 1, null)))
        assertEquals("Completa 3 cursos", badgeCriterion(badge("a", AchievementCriterion.COURSES_COMPLETED, 3, null)))
        assertEquals("Aprende 7 días seguidos", badgeCriterion(badge("a", AchievementCriterion.STREAK_DAYS, 7, null)))
        assertEquals("Saca el puntaje perfecto en 1 evaluación", badgeCriterion(badge("a", AchievementCriterion.PERFECT_SCORE, 1, null)))
    }

    @Test fun badgesEarnedFirstThenPendingNearestFirst() {
        val list = listOf(
            badge("far", AchievementCriterion.STREAK_DAYS, 30, null),
            badge("old", AchievementCriterion.COURSES_COMPLETED, 1, "2026-10-01T10:00:00Z"),
            badge("near", AchievementCriterion.STREAK_DAYS, 7, null),
            badge("new", AchievementCriterion.PERFECT_SCORE, 1, "2026-11-01T10:00:00Z"),
            badge("off", AchievementCriterion.COURSES_COMPLETED, 9, null, active = false),
        )
        assertEquals(listOf("new", "old", "near", "far"), badgeOrder(list).map { it.id })
    }
}
