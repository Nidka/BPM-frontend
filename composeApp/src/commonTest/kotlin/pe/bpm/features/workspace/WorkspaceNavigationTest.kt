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
        assertTrue(WorkspaceDestination.Team in chef)
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

    @Test fun accountLivesInTheHeaderForAdminAndInTheNavigationForChefAndWorker() {
        val admin = workspacePrimaryDestinations(workspaceDestinations("ADMIN", false), "ADMIN")
        assertFalse(WorkspaceDestination.Account in admin)
        assertTrue(WorkspaceDestination.Account in workspaceDestinations("ADMIN", false))
        for (role in listOf("CHEF", "WORKER")) assertTrue(WorkspaceDestination.Account in workspacePrimaryDestinations(workspaceDestinations(role, false), role), role)
        assertTrue(WorkspaceDestination.Team in workspaceDestinations("CHEF", false))
        assertFalse(WorkspaceDestination.Team in workspaceDestinations("WORKER", false))
    }

    @Test fun chefNavigationIsShiftControlsTeamLearnProfile() {
        val primary = workspacePrimaryDestinations(workspaceDestinations("CHEF", false), "CHEF")
        assertEquals(listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Team, WorkspaceDestination.Learn, WorkspaceDestination.Account), primary)
        assertEquals(listOf("Turno", "Controles", "Equipo", "Aprender", "Perfil"), primary.map { destinationLabel(it, "CHEF") })
        // Las incidencias dejan de ser un destino del jefe: viven dentro de Turno y Controles.
        assertTrue(workspaceShortcuts("CHEF", false, canResearch = false, canManageGrants = false).isEmpty())
        // El administrador conserva sus etiquetas.
        assertEquals("Inicio", destinationLabel(WorkspaceDestination.Home, "ADMIN"))
        assertEquals("Control", destinationLabel(WorkspaceDestination.Operations, "ADMIN"))
    }

    @Test fun workerNavigationIsTodayLearnTasksProfile() {
        val primary = workspacePrimaryDestinations(workspaceDestinations("WORKER", false), "WORKER")
        assertEquals(listOf(WorkspaceDestination.Home, WorkspaceDestination.Learn, WorkspaceDestination.Tasks, WorkspaceDestination.Account), primary)
        assertEquals(listOf("Hoy", "Aprender", "Tareas", "Perfil"), primary.map { destinationLabel(it, "WORKER") })
        // El administrador conserva sus etiquetas y «Mi cuenta» en la cabecera.
        assertEquals("Mi cuenta", destinationLabel(WorkspaceDestination.Account, "ADMIN"))
        assertEquals("Mis tareas", destinationLabel(WorkspaceDestination.Tasks, "ADMIN"))
    }

    private fun nav(role: String?, platform: Boolean, research: Boolean = false, grants: Boolean = false) =
        workspaceNavigation(workspacePrimaryDestinations(workspaceDestinations(role, platform), role), workspaceShortcuts(role, platform, research, grants))
            .map { when (it) { is WorkspaceNavItem.Section -> destinationLabel(it.destination, role); is WorkspaceNavItem.Shortcut -> it.shortcut.label } }

    @Test fun chefGetsFiveDestinationsAndNoIncidentsShortcut() {
        assertEquals(listOf("Turno", "Controles", "Equipo", "Aprender", "Perfil"), nav("CHEF", false))
    }

    @Test fun researcherWithoutOrganizationSeesHomeAndResearch() {
        assertEquals(listOf("Inicio", "Investigación"), nav(null, false, research = true))
    }

    @Test fun bpmTeamSeesCompaniesTrackingAndAccessWithinFiveEntries() {
        val items = nav(null, true, research = true, grants = true)
        assertTrue(items.size <= 5)
        assertEquals(listOf("Inicio", "Empresas", "Seguimiento", "Acceso", "Empresa"), items)
    }

    @Test fun everyRoleAndShortcutCombinationFitsFiveEntriesWithoutTruncation() {
        for (role in listOf(null, "WORKER", "CHEF", "ADMIN", "OWNER")) for (platform in listOf(false, true))
            for (research in listOf(false, true)) for (grants in listOf(false, true)) {
                val primary = workspacePrimaryDestinations(workspaceDestinations(role, platform), role)
                val shortcuts = workspaceShortcuts(role, platform, research, grants)
                val merged = workspaceNavigation(primary, shortcuts)
                val label = "rol=$role plataforma=$platform investigación=$research accesos=$grants"
                assertTrue(merged.size <= 5, label)
                // Lo que sale de la barra es lo que el cálculo omitió a propósito (Equipo, Aprender), nunca un atajo ni otra sección.
                val shown = merged.mapNotNull { (it as? WorkspaceNavItem.Shortcut)?.shortcut }
                assertEquals(shortcuts.toSet(), shown.toSet(), "atajo perdido: $label")
                val sections = merged.mapNotNull { (it as? WorkspaceNavItem.Section)?.destination }
                val lost = primary.filter { it !in sections }
                assertTrue(lost.all { it == WorkspaceDestination.Team || it == WorkspaceDestination.Learn }, "sección perdida $lost: $label")
            }
    }

    @Test fun adminAndWorkerHaveNoShortcuts() {
        assertEquals(5, nav("ADMIN", false).size)
        assertEquals(listOf("Hoy", "Aprender", "Tareas", "Perfil"), nav("WORKER", false))
    }
}
