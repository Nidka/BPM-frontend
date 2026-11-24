package pe.bpm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import pe.bpm.core.designsystem.BpmTime
import pe.bpm.core.designsystem.TimeOfDay

/** Horas del selector: validación, texto «HH:mm», vuelta de medianoche y lectura en palabras. */
class BpmTimeOfDayTest {
    @Test fun parsesRealTimesOnly() {
        assertEquals(TimeOfDay(8, 30), BpmTime.parse("08:30"))
        assertEquals(TimeOfDay(8, 5), BpmTime.parse("8:05"))
        assertEquals(TimeOfDay(23, 59), BpmTime.parse("23:59:00"))
        assertNull(BpmTime.parse("24:00")); assertNull(BpmTime.parse("12:60")); assertNull(BpmTime.parse("")); assertNull(BpmTime.parse("ahora"))
    }

    @Test fun textHasTwoDigits() {
        assertEquals("08:05", TimeOfDay(8, 5).text)
        assertEquals("00:00", TimeOfDay(0, 0).text)
        assertEquals("17:30", TimeOfDay(17, 30).text)
    }

    @Test fun minutesWrapAroundMidnight() {
        assertEquals(TimeOfDay(0, 5), BpmTime.fromMinutes(1445))
        assertEquals(TimeOfDay(23, 55), BpmTime.fromMinutes(-5))
        assertEquals(TimeOfDay(13, 30), BpmTime.fromMinutes(810))
    }

    @Test fun spokenTextUsesTwelveHourClock() {
        assertEquals("8 y 30 de la mañana", TimeOfDay(8, 30).spoken)
        assertEquals("12 en punto del mediodía", TimeOfDay(12, 0).spoken)
        assertEquals("6 en punto de la tarde", TimeOfDay(18, 0).spoken)
        assertEquals("9 y 15 de la noche", TimeOfDay(21, 15).spoken)
        assertEquals("12 en punto de la medianoche", TimeOfDay(0, 0).spoken)
    }

    @Test fun orderIsChronological() { assertEquals(true, TimeOfDay(8, 59) < TimeOfDay(9, 0)) }
}
