package pe.bpm.features.workspace

import pe.bpm.features.workspace.presentation.*
import kotlin.test.*

class WorkspaceNavigationTest {
    @Test fun workerCannotOpenOperationsOrAdministrationByDirectRoute() {
        val allowed = workspaceDestinations("WORKER", false)
        assertTrue(WorkspaceDestination.Learn in allowed)
        for (route in listOf("control", "empresa", "equipo")) {
            assertFalse(WorkspaceDestination.fromRoute(route) in allowed)
        }
    }

    @Test fun unknownRoleAndMissingMembershipFailClosed() {
        for (role in listOf(null, "SUPER_ADMIN", "worker", "UNRECOGNIZED")) {
            assertEquals(listOf(WorkspaceDestination.Home, WorkspaceDestination.Account), workspaceDestinations(role, false))
        }
        assertNull(WorkspaceDestination.fromRoute("https://attacker.example"))
    }

    @Test fun platformAdminAndChefHaveSeparateResponsibilities() {
        val admin = workspaceDestinations(null, true)
        assertTrue(WorkspaceDestination.Company in admin)
        assertFalse(WorkspaceDestination.Operations in admin)
        assertFalse(WorkspaceDestination.Learn in admin)
        val chef = workspaceDestinations("CHEF", false)
        assertTrue(WorkspaceDestination.Operations in chef)
        assertTrue(WorkspaceDestination.Learn in chef)
        assertFalse(WorkspaceDestination.Company in chef)
    }

    @Test fun organizationAdminCanLearnWithoutCrowdingThePrimaryNavigation() {
        val allowed = workspaceDestinations("ADMIN", false)
        assertTrue(WorkspaceDestination.Learn in allowed)
        assertTrue(workspacePrimaryDestinations(allowed).size <= 5)
        assertTrue(WorkspaceDestination.Account in workspacePrimaryDestinations(allowed))
    }
}
