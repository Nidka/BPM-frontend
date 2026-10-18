package pe.bpm.features.workspace

import pe.bpm.features.workspace.presentation.WorkspaceFormKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceFormAccessTest {
    @Test fun directOrganizationFormRequiresPlatformAdministration() {
        assertFalse(WorkspaceFormKind.Organization.isAllowed(platformAdmin = false, canManage = true))
        assertTrue(WorkspaceFormKind.Organization.isAllowed(platformAdmin = true, canManage = false))
    }

    @Test fun directTeamAndLocationFormsRequireManagementOfCurrentOrganization() {
        for (kind in listOf(WorkspaceFormKind.Location, WorkspaceFormKind.Member)) {
            assertFalse(kind.isAllowed(platformAdmin = false, canManage = false))
            assertFalse(kind.isAllowed(platformAdmin = true, canManage = false))
            assertTrue(kind.isAllowed(platformAdmin = false, canManage = true))
        }
    }
}
