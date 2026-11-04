package pe.bpm.features.incidents

import pe.bpm.features.incidents.data.*
import kotlin.test.*

class LegacyIncidentDtoTest {
    private fun id(n: Int) = "49d000f4-e487-41cf-a166-${n.toString().padStart(12, '0')}"
    @Test fun readsVerifiedIncidentAndAllCancelledActionsWithFirebaseIdentifiers() {
        val actions = (1..101).map { n -> ActionDto(id(n), "firebase:acción.$n", "Persona", "Plan", "2026-11-04T15:00:00Z", "CANCELLED") }
        val dto = DetailDto(id(0), id(0), id(0), "Criterio", "Área", "HIGH", "VERIFIED", "Problema", "Persona", "2026-11-03T00:00:00Z", 2,
            "firebase:reportó.uid", 1, "Criterio normativo", true, null, actions, emptyList(), 4, 0)
        val detail = dto.domain()
        assertEquals("VERIFIED", detail.summary.status.name)
        assertEquals(101, detail.actions.size)
        assertEquals(4, detail.beforeCount)
        assertFailsWith<IllegalArgumentException> { dto.copy(beforeCount = -1).domain() }
        assertFailsWith<IllegalArgumentException> { dto.copy(afterCount = -1).domain() }
        assertTrue(detail.actions.all { it.status == "CANCELLED" })
        assertEquals("firebase:reportó.uid", detail.reportedBy)
        assertEquals("firebase:acción.1", AssigneeDto("firebase:acción.1", "Persona", "WORKER").domain().id)
    }
    @Test fun firebaseIdsCountUnicodeCodePointsLikeTheServer() {
        assertEquals("🧑".repeat(128), AssigneeDto("🧑".repeat(128), "Persona", "WORKER").domain().id)
        assertFailsWith<IllegalArgumentException> { AssigneeDto("🧑".repeat(129), "Persona", "WORKER").domain() }
    }
    @Test fun firebaseIdsStillRejectControlCharactersAndOverlength() {
        assertFailsWith<IllegalArgumentException> { AssigneeDto("uid\nprivate", "Persona", "WORKER").domain() }
        assertFailsWith<IllegalArgumentException> { AssigneeDto("a".repeat(129), "Persona", "WORKER").domain() }
    }
}
