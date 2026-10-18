package pe.bpm.features.workspace.presentation

import pe.bpm.core.domain.PageRequest
import pe.bpm.features.workspace.domain.WorkspaceError

/** Estado de una sola página; no acumula todos los registros en memoria. */
data class WorkspacePageState(
    val request: PageRequest = PageRequest(),
    val previous: List<String> = emptyList(),
    val nextToken: String = "",
    val loading: Boolean = false,
    val errors: List<WorkspaceError> = emptyList(),
) {
    val number get() = previous.size + 1
}
