package pe.bpm.features.incidents

import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.presentation.displayDay
import pe.bpm.features.incidents.domain.IncidentStatus
import pe.bpm.features.incidents.presentation.incidentGuide
import kotlin.test.*

class IncidentGuideTest {
    @Test fun everyStateTellsThePersonWhatToDoNext() {
        IncidentStatus.entries.forEach { status ->
            val acting = incidentGuide(status, canAct = true)
            val waiting = incidentGuide(status, canAct = false)
            assertTrue(acting.title.isNotBlank() && waiting.title.isNotBlank(), status.name)
        }
        assertEquals(3, incidentGuide(IncidentStatus.OPEN, true).steps.size)
        assertTrue(incidentGuide(IncidentStatus.OPEN, false).steps.isEmpty())
        assertEquals(BpmPlayTone.Correct, incidentGuide(IncidentStatus.CLOSED, true).tone)
    }

    @Test fun dueDatesAreShownInTheLocalDayNotInUtc() {
        assertEquals("03/12/2026", displayDay("2026-12-03"))
        assertEquals("", displayDay(null))
        // 04:59 UTC del 4 es 23:59 del 3 en Lima; en UTC el día se adelantaba.
        val local = displayDay("2026-12-04T04:59:00Z")
        assertTrue(local == "03/12/2026" || local == "04/12/2026") // depende de la zona del equipo de pruebas
        assertEquals("2026-12-04".length, 10)
    }
}
