package pe.bpm.features.workspace.presentation

/** Public display data. Permissions remain enforced by the backend. Contains no token. */
data class WorkspaceViewer(
    val id: String,
    val displayName: String,
    val email: String,
    val canCreateOrganization: Boolean,
)
