package pe.bpm.features.workspace.presentation

import pe.bpm.features.workspace.domain.*

data class WorkspaceState(
    val canChooseOrganization: Boolean = false,
    val organizationPage: WorkspacePageState = WorkspacePageState(),
    val pages: Map<pe.bpm.features.workspace.domain.WorkspaceResource, WorkspacePageState> = emptyMap(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val organizations: List<Organization> = emptyList(),
    val selected: String? = null,
    val workspace: Workspace? = null,
    val errors: List<WorkspaceError> = emptyList(),
    val notice: String? = null,
    val revision: Int = 0,
)
