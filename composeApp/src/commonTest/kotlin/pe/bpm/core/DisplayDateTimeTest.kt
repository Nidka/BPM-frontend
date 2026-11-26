package pe.bpm.core

import pe.bpm.core.presentation.displayClock
import pe.bpm.core.presentation.formatClock
import pe.bpm.core.presentation.formatDateTime
import kotlin.test.*

class DisplayDateTimeTest {
    private val lima = -300
    // 2026-11-26T01:41:00Z = 25/11/2026 8:41 p. m. en Lima.
    private val instant = 1_795_657_260_000L

    @Test fun formatsTwelveHourClockWithoutZoneAbbreviation() {
        assertEquals("25/11/2026 · 8:41 p. m.", formatDateTime(instant, lima))
    }

    @Test fun midnightAndNoonUseTwelve() {
        assertEquals("12:00 a. m.", displayClock("00:00"))
        assertEquals("12:30 p. m.", displayClock("12:30"))
        assertEquals("1:05 a. m.", displayClock("01:05"))
        assertEquals("11:59 p. m.", displayClock("23:59"))
    }

    @Test fun dateRollsOverWithTheOffset() {
        assertEquals("26/11/2026 · 1:41 a. m.", formatDateTime(instant, 0))
        assertEquals("25/11/2026 · 8:30 p. m.", formatDateTime(instant - 11 * 60_000L, lima))
        assertEquals("01/01/1970 · 12:00 a. m.", formatDateTime(0, 0))
        assertEquals("31/12/1969 · 7:00 p. m.", formatDateTime(0, lima))
        assertEquals("29/02/2028 · 12:00 a. m.", formatDateTime(1_835_395_200_000L, 0))
    }

    @Test fun invalidClockValuesAreReturnedUntouched() {
        assertEquals("", displayClock(""))
        assertEquals("mañana", displayClock("mañana"))
        assertEquals("25:00", displayClock("25:00"))
        assertEquals("8:41 p. m.", formatClock(instant, lima))
    }
}
