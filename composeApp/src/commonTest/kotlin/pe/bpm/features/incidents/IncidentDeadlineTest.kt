package pe.bpm.features.incidents

import kotlin.test.*
import kotlin.time.Instant
import pe.bpm.features.incidents.presentation.incidentDeadline

class IncidentDeadlineTest {
    @Test
    fun respectsSiteZoneAndRejectsInvalidOrAmbiguousTimes() {
        assertEquals(
            Instant.parse("2026-11-03T15:30:00Z"),
            Instant.parse(incidentDeadline("2026-11-03", "10:30", "America/Lima")!!),
        )
        assertNull(incidentDeadline("2026-02-30", "10:30", "America/Lima"))
        assertNull(incidentDeadline("2026-11-03", "25:30", "America/Lima"))
        assertNull(incidentDeadline("2026-11-03", "10:30", "Unknown/Zone"))
        assertNull(incidentDeadline("2026-11-01", "01:30", "America/New_York"))
        assertNull(incidentDeadline("2026-03-08", "02:30", "America/New_York"))
        assertNull(incidentDeadline("2026-04-05", "01:45", "Australia/Lord_Howe"))
    }
}
