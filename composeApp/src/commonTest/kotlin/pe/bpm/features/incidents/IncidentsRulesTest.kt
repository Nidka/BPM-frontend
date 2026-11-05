package pe.bpm.features.incidents

import kotlin.test.*
import pe.bpm.features.incidents.data.prepareIncident
import pe.bpm.features.incidents.domain.*

class IncidentsRulesTest {
    @Test
    fun administrativeRolesSuperviseWithoutReporting() {
        assertFalse(IncidentsAccess.canReport("ADMIN"))
        assertFalse(IncidentsAccess.canReport("SUPER_ADMIN"))
        assertTrue(IncidentsAccess.canReport("CHEF"))
        assertTrue(IncidentsAccess.canReport("WORKER"))
    }

    @Test
    fun catalogAuthoringRequiresAdministrator() {
        assertTrue(IncidentsAccess.canAuthor("ADMIN"))
        assertTrue(IncidentsAccess.canAuthor("SUPER_ADMIN"))
        assertFalse(IncidentsAccess.canAuthor("CHEF"))
        assertFalse(IncidentsAccess.canAuthor("WORKER"))
    }

    @Test
    fun assignmentRequiresFutureDeadlineAndSelectedResponsible() {
        val form =
            IncidentForm(
                "id",
                "2026-11-01T00:00:00Z",
                "ASSIGN",
                mapOf("description" to "Corregir", "dueAt" to "2026-10-01T00:00:00Z"),
            )
        assertTrue(
            IncidentsRules.errors(form, "2026-11-01T00:00:00Z").containsKey("responsibleUserId")
        )
        assertTrue(IncidentsRules.errors(form, "2026-11-01T00:00:00Z").containsKey("dueAt"))
    }

    @Test
    fun commandPayloadIsImmutableAndForbiddenFieldsAreExcluded() {
        val form =
            IncidentForm(
                "op",
                "2026-11-01T00:00:00Z",
                "START",
                mapOf("description" to "old", "note" to "old"),
                incidentId = "incident",
                rowVersion = 3,
            )
        val pending = prepareIncident(form, "site", "key-12345678")
        assertFalse(pending.body.contains("description"))
        assertFalse(pending.body.contains("note"))
        assertTrue(pending.body.contains("operationId"))
        assertEquals(pending, prepareIncident(form, "site", "key-12345678"))
    }
}
