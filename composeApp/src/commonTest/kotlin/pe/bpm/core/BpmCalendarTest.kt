package pe.bpm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pe.bpm.core.designsystem.BpmCalendar
import pe.bpm.core.designsystem.CivilDate

/** Fechas del selector: validación, día de la semana, sumar días, años bisiestos y texto en español. */
class BpmCalendarTest {
    @Test fun parsesRealDatesOnly() {
        assertEquals(CivilDate(2026, 11, 23), BpmCalendar.parse("2026-11-23"))
        assertEquals(CivilDate(2026, 11, 23), BpmCalendar.parse("2026-11-23T10:00:00Z"))
        assertNull(BpmCalendar.parse("2026-02-30")); assertNull(BpmCalendar.parse("2026-13-01")); assertNull(BpmCalendar.parse("hoy")); assertNull(BpmCalendar.parse(""))
        assertEquals(CivilDate(2028, 2, 29), BpmCalendar.parse("2028-02-29")); assertNull(BpmCalendar.parse("2027-02-29"))
    }

    @Test fun weekdayAndMonthGridAreRight() {
        assertEquals(0, CivilDate(2026, 11, 23).weekday)   // lunes
        assertEquals(6, CivilDate(2026, 11, 29).weekday)   // domingo
        assertEquals(6, BpmCalendar.leadingBlanks(2026, 11)) // 1 de noviembre de 2026 es domingo
        assertEquals(3, CivilDate(1970, 1, 1).weekday)     // jueves
        assertEquals(30, BpmCalendar.daysInMonth(2026, 11)); assertEquals(29, BpmCalendar.daysInMonth(2024, 2))
    }

    @Test fun addingDaysCrossesMonthsAndYears() {
        assertEquals(CivilDate(2026, 12, 1), CivilDate(2026, 11, 30).plusDays(1))
        assertEquals(CivilDate(2027, 1, 1), CivilDate(2026, 12, 31).plusDays(1))
        assertEquals(CivilDate(2026, 11, 23), CivilDate(2026, 11, 30).plusDays(-7))
        assertEquals(CivilDate(2028, 3, 1), CivilDate(2028, 2, 29).plusDays(1))
        assertEquals(0L, BpmCalendar.epochDay(CivilDate(1970, 1, 1)))
    }

    @Test fun textIsSpanishAndSortable() {
        assertEquals("2026-01-05", CivilDate(2026, 1, 5).iso)
        assertEquals("5 de enero de 2026", CivilDate(2026, 1, 5).spoken)
        assertEquals(true, CivilDate(2026, 11, 23) < CivilDate(2026, 12, 1))
        assertEquals(2027 to 1, BpmCalendar.nextMonth(2026, 12)); assertEquals(2025 to 12, BpmCalendar.previousMonth(2026, 1))
    }
}
