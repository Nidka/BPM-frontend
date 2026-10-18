package pe.bpm.features.workspace.presentation

import pe.bpm.features.workspace.domain.*

data class WorkspaceState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val organizations: List<Organization> = emptyList(),
    val selected: String? = null,
    val workspace: Workspace? = null,
    val errors: List<WorkspaceError> = emptyList(),
    val notice: String? = null,
    val revision: Int = 0,
)
