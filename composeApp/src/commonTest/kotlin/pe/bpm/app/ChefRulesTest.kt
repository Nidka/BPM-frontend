package pe.bpm.app

import pe.bpm.features.checklists.domain.AssignedChecklistRun
import pe.bpm.features.checklists.domain.ChecklistAssignment
import pe.bpm.features.checklists.domain.ChecklistFrequency
import pe.bpm.features.checklists.domain.ShiftState
import pe.bpm.features.workspace.domain.Location
import pe.bpm.features.workspace.domain.Member
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChefRulesTest {
    private fun control(id: String, run: AssignedChecklistRun? = null, shift: ShiftState? = null, active: Boolean = true, end: String = "") = ChecklistAssignment(
        id, "t-$id", "Temperatura de cámaras", "v1", "sede-1", "Sede principal", ChecklistFrequency.DAILY, "12:00", "America/Lima", active, "2026-11-01T00:00:00Z",
        run = run, shiftState = shift, shiftEndTime = end,
    )

    private fun run(status: String, by: String = "chef", name: String = "Carlos", submitted: String? = null) = AssignedChecklistRun("r", status, by, name, "2026-11-22T14:00:00Z", submitted)

    @Test fun attentionListsOnlyRealReasonsInOrderOfSeverity() {
        assertTrue(chefAttention(0, 0, 0).isEmpty())
        assertTrue(chefAttention(null, null, null).isEmpty())
        assertEquals(listOf("1 control vencido", "2 incidencias abiertas", "1 curso vencido tuyo"), chefAttention(1, 2, 1).map { it.text })
        assertEquals(listOf("3 controles vencidos"), chefAttention(3, null, 0).map { it.text })
        assertEquals(listOf(ChefAttentionKind.OpenIncidents), chefAttention(0, 1, null).map { it.kind })
    }

    @Test fun controlStateFollowsTheRunAndTheShift() {
        assertEquals(ChefControlState.Pending, controlState(control("a"), "chef"))
        assertEquals(ChefControlState.Pending, controlState(control("a", shift = ShiftState.OPEN), "chef"))
        assertEquals(ChefControlState.Late, controlState(control("a", shift = ShiftState.CLOSED), "chef"))
        assertEquals(ChefControlState.MineInProgress, controlState(control("a", run("DRAFT")), "chef"))
        assertEquals(ChefControlState.OthersInProgress, controlState(control("a", run("DRAFT", by = "otra")), "chef"))
        assertEquals(ChefControlState.Done, controlState(control("a", run("SUBMITTED"), shift = ShiftState.CLOSED), "chef"))
    }

    @Test fun controlsAreOrderedLateThenInProgressThenPendingAndDoneLast() {
        val items = listOf(control("done", run("SUBMITTED")), control("pending"), control("mine", run("DRAFT")), control("late", shift = ShiftState.CLOSED), control("off", active = false))
        assertEquals(listOf("late", "mine", "pending", "done"), controlOrder(items, "chef").map { it.id })
        assertEquals(1 to 4, controlsDone(items))
        assertEquals("Empezar", controlAction(ChefControlState.Pending))
        assertEquals("Continuar", controlAction(ChefControlState.MineInProgress))
        assertEquals("Ver", controlAction(ChefControlState.Done))
    }

    @Test fun controlDetailSaysWhatIsMissingWithTheSite() {
        val clock = { _: String -> "9:12" }
        assertEquals("Hasta las 12:00 · Sede principal", controlDetail(control("a"), "chef", clock))
        assertEquals("Venció a las 10:00 · Sede principal", controlDetail(control("a", shift = ShiftState.CLOSED, end = "10:00"), "chef", clock))
        assertEquals("Hecho 9:12 · Carlos · Sede principal", controlDetail(control("a", run("SUBMITTED", submitted = "2026-11-22T14:12:00Z")), "chef", clock))
        assertEquals("En curso · tu revisión · Sede principal", controlDetail(control("a", run("DRAFT")), "chef", clock))
    }

    private fun member(id: String, name: String, role: String, vararg sites: String) = Member(id, name, "$id@bpm.test", role, sites.toList())

    @Test fun teamIsThePeopleOfTheChefSitesWithoutThemselvesNorAdmins() {
        val members = listOf(
            member("w1", "pedro Personal", "WORKER", "s1"), member("w2", "Lucía Quispe", "WORKER", "s1", "s2"), member("w3", "Otra Sede", "WORKER", "s9"),
            member("chef", "Carlos", "CHEF", "s1"), member("c2", "Ana Jefa", "CHEF", "s2"), member("adm", "Admin", "ADMIN"),
        )
        assertEquals(listOf("Ana Jefa", "Lucía Quispe", "pedro Personal"), teamOf(members, setOf("s1", "s2"), "chef").map { it.name })
        assertTrue(teamOf(emptyList(), setOf("s1"), "chef").isEmpty())
    }

    @Test fun memberLineShowsRoleAndOnlySharedSites() {
        val sites = listOf(Location("s1", "Sede principal", ""), Location("s2", "Sede norte", ""))
        assertEquals("Personal · Sede principal, Sede norte", memberLine(member("w", "A", "WORKER", "s1", "s2", "s9"), sites))
        assertEquals("Jefe de cocina · Sin sede en común", memberLine(member("c", "B", "CHEF", "s9"), sites))
    }
}
