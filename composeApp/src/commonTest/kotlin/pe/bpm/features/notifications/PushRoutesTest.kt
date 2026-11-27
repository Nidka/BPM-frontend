package pe.bpm.features.notifications

import pe.bpm.features.notifications.domain.*
import kotlin.test.*

class PushRoutesTest {
    private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"

    @Test fun incidentAndControlPushesOpenTheRecord() {
        assertEquals("control/incidencias/$id", acceptedPushRoute("control/incidencias/$id"))
        assertEquals("seguimiento/$id", acceptedPushRoute("seguimiento/$id"))
        assertEquals("empresa/incidencias" to id, incidentDeepLink("empresa/incidencias/$id"))
        assertNull(incidentDeepLink("control/incidencias"))
        assertEquals("avisos", acceptedPushRoute("control/incidencias/no-uuid"))
        assertEquals("avisos", acceptedPushRoute("https://evil.example"))
    }

    @Test fun testPushResultTexts() {
        assertTrue(testPushText(TestPushOutcome.SENT).startsWith("Prueba enviada"))
        assertTrue(testPushText(TestPushOutcome.RATE_LIMITED).contains("un minuto"))
    }
}
