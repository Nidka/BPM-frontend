package pe.bpm.features.workspace

import pe.bpm.features.workspace.domain.WorkspaceError
import pe.bpm.features.workspace.presentation.*
import kotlin.test.*

class WorkspacePlaceholderTest {
    @Test fun deniedAccessCannotLookLikeAnUnassignedAccountOrOfferOrganizationCreation() {
        val state = WorkspaceState(errors = listOf(WorkspaceError("ACCESS_DENIED", "Acceso denegado")))
        for (platformAdmin in listOf(false, true)) {
            val view = workspacePlaceholder(state, platformAdmin)
            assertEquals("Acceso restringido", view.title)
            assertFalse(view.canCreate)
            assertFalse(view.canRetry)
        }
    }

    @Test fun unavailableServiceOffersRetryWithoutClaimingThatTheAccountHasNoOrganization() {
        val view = workspacePlaceholder(WorkspaceState(errors = listOf(WorkspaceError("CONNECTION_ERROR", "Sin conexión"))), true)
        assertTrue(view.canRetry)
        assertFalse(view.canCreate)
        assertEquals("No pudimos cargar tu espacio", view.title)
    }

    @Test fun onlyAnAuthorizedPlatformAdministratorCanCreateTheInitialOrganization() {
        assertTrue(workspacePlaceholder(WorkspaceState(), true).canCreate)
        assertFalse(workspacePlaceholder(WorkspaceState(), false).canCreate)
    }
}
