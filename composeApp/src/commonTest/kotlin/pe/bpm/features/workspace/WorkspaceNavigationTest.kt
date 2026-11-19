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

    @Test fun workerGetsCleaningTasksWithoutStockOrAdministration() {
        val allowed = workspaceDestinations("WORKER", false)
        assertTrue(WorkspaceDestination.Tasks in allowed)
        assertFalse(WorkspaceDestination.Operations in allowed)
        assertFalse(WorkspaceDestination.Company in allowed)
        assertTrue(workspacePrimaryDestinations(allowed).size <= 5)
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
        assertFalse(WorkspaceDestination.Account in workspacePrimaryDestinations(admin))
        val chef = workspaceDestinations("CHEF", false)
        assertTrue(WorkspaceDestination.Operations in chef)
        assertTrue(WorkspaceDestination.Learn in chef)
        assertFalse(WorkspaceDestination.Company in chef)
    }

    @Test fun organizationAdminSupervisesAndAlsoPerformsDailyReviews() {
        val allowed = workspaceDestinations("ADMIN", false)
        assertTrue(WorkspaceDestination.Supervision in allowed)
        assertTrue(WorkspaceDestination.Operations in allowed)
        assertTrue(WorkspaceDestination.Learn in allowed)
        val primary = workspacePrimaryDestinations(allowed)
        assertEquals(listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Learn,
            WorkspaceDestination.Supervision, WorkspaceDestination.Company), primary)
    }

    @Test fun accountLivesInTheHeaderAndChefHasNoTeamList() {
        for (role in listOf("ADMIN", "CHEF", "WORKER")) {
            val primary = workspacePrimaryDestinations(workspaceDestinations(role, false))
            assertFalse(WorkspaceDestination.Account in primary, role)
            assertTrue(WorkspaceDestination.Account in workspaceDestinations(role, false), role)
        }
        assertFalse(WorkspaceDestination.Team in workspaceDestinations("CHEF", false))
        assertEquals(listOf(WorkspaceDestination.Home, WorkspaceDestination.Tasks, WorkspaceDestination.Learn),
            workspacePrimaryDestinations(workspaceDestinations("WORKER", false)))
    }

    private fun nav(role: String?, platform: Boolean, research: Boolean = false, grants: Boolean = false) =
        workspaceNavigation(workspacePrimaryDestinations(workspaceDestinations(role, platform)), workspaceShortcuts(role, platform, research, grants))
            .map { when (it) { is WorkspaceNavItem.Section -> it.destination.label; is WorkspaceNavItem.Shortcut -> it.shortcut.label } }

    @Test fun chefGetsIncidentsAsItsOwnDestinationAndNoTeam() {
        assertEquals(listOf("Inicio", "Control", "Incidencias", "Aprender"), nav("CHEF", false))
    }

    @Test fun researcherWithoutOrganizationSeesHomeAndResearch() {
        assertEquals(listOf("Inicio", "Investigación"), nav(null, false, research = true))
    }

    @Test fun bpmTeamSeesCompaniesTrackingAndAccessWithinFiveEntries() {
        val items = nav(null, true, research = true, grants = true)
        assertTrue(items.size <= 5)
        assertEquals(listOf("Inicio", "Empresas", "Seguimiento", "Acceso", "Empresa"), items)
    }

    @Test fun adminAndWorkerHaveNoShortcuts() {
        assertEquals(5, nav("ADMIN", false).size)
        assertEquals(listOf("Inicio", "Mis tareas", "Aprender"), nav("WORKER", false))
    }
}
