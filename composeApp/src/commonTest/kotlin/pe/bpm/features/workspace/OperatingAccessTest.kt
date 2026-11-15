package pe.bpm.features.workspace

import kotlin.test.*
import pe.bpm.features.incidents.domain.*
import pe.bpm.features.operations.domain.*
import pe.bpm.features.workspace.presentation.*

/** El ADMIN miembro opera como el CHEF; SUPER_ADMIN sin membresía y WORKER conservan sus límites. */
class OperatingAccessTest {
    @Test fun adminAndChefOperateButSuperAdminWithoutMembershipDoesNot() {
        assertTrue(OperatingScope.canOperate("ADMIN", false))
        assertTrue(OperatingScope.canOperate("CHEF", false))
        // SUPER_ADMIN sin membresía: sin rol de organización y como administrador de plataforma.
        assertFalse(OperatingScope.canOperate(null, true))
        assertFalse(OperatingScope.canOperate("SUPER_ADMIN", true))
        assertFalse(OperatingScope.canOperate("WORKER", false))
        assertTrue(WorkspaceDestination.Operations in workspaceDestinations("ADMIN", false))
        assertFalse(WorkspaceDestination.Operations in workspaceDestinations(null, true))
    }

    @Test fun adminKeepsTheBarWithinFiveEntriesAndStillReachesTeamFromCompany() {
        val available = workspaceDestinations("ADMIN", false)
        val primary = workspacePrimaryDestinations(available)
        assertTrue(primary.size <= 5)
        assertTrue(WorkspaceDestination.Operations in primary)
        assertTrue(WorkspaceDestination.Company in primary)
        assertTrue(WorkspaceDestination.Team in available)
    }

    @Test fun receiptsCleaningAndStockAreAvailableToAdminAndChefOnly() {
        for (role in listOf("ADMIN", "CHEF")) {
            assertTrue(OperationsAccess.canCreate(role, OperationsResource.RECEIPTS), role)
            assertTrue(OperationsAccess.canCreate(role, OperationsResource.EXECUTIONS), role)
            assertTrue(StockAccess.canRecord(role), role)
        }
        assertFalse(OperationsAccess.canCreate("SUPER_ADMIN", OperationsResource.RECEIPTS))
        assertFalse(OperationsAccess.canCreate("SUPER_ADMIN", OperationsResource.EXECUTIONS))
        assertFalse(StockAccess.canRecord("SUPER_ADMIN"))
        assertFalse(OperationsAccess.canCreate("WORKER", OperationsResource.RECEIPTS))
        assertTrue(OperationsAccess.canCreate("WORKER", OperationsResource.EXECUTIONS))
        assertFalse(StockAccess.canRecord("WORKER"))
        // Los catálogos siguen siendo administrativos.
        assertTrue(OperationsAccess.canCreate("ADMIN", OperationsResource.PLANS))
        assertFalse(OperationsAccess.canCreate("CHEF", OperationsResource.PLANS))
    }

    @Test fun incidentsAreReportedAndAttendedByAdminChefAndWorkerNotBySuperAdmin() {
        for (role in listOf("ADMIN", "CHEF", "WORKER")) {
            assertTrue(IncidentsAccess.canReport(role), role)
            assertTrue(IncidentsAccess.canAttend(role), role)
        }
        assertFalse(IncidentsAccess.canReport("SUPER_ADMIN"))
        assertFalse(IncidentsAccess.canAttend("SUPER_ADMIN"))
    }

    private fun action(responsible: String) =
        CorrectiveAction("a1", responsible, "Nombre", "Corregir", "2026-11-20T00:00:00Z", "RESOLVED", "Hecho")

    @Test fun validateIsOfferedToTheResponsibleAsSelfValidationAndMarksSelfValidatedActions() {
        val allowed = setOf(IncidentActionType.VERIFY, IncidentActionType.RETURN)
        val actions = listOf(action("admin-1"))
        val mine = IncidentsAccess.visibleActions(allowed, IncidentStatus.RESOLVED, actions, "admin-1")
        assertTrue(IncidentActionType.VERIFY in mine)
        assertTrue(IncidentActionType.RETURN in mine)
        assertTrue(IncidentsAccess.isSelfVerification(actions, "admin-1"))
        assertFalse(IncidentsAccess.isSelfVerification(actions, "chef-2"))
        assertFalse(IncidentsAccess.isSelfVerification(actions, null))
        assertFalse(actions.single().selfValidated)
        assertTrue(actions.single().copy(selfValidated = true).selfValidated)
        assertEquals("Otra persona de tu equipo debe validar esta corrección", IncidentsAccess.SELF_VALIDATION_DENIED)
        val other = IncidentsAccess.visibleActions(allowed, IncidentStatus.RESOLVED, actions, "chef-2")
        assertTrue(IncidentActionType.VERIFY in other)
        assertTrue(IncidentsAccess.visibleActions(allowed, IncidentStatus.VERIFIED, actions, "chef-2").isEmpty())
    }
}
