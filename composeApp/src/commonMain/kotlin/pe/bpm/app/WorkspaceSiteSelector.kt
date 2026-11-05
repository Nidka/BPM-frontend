package pe.bpm.app

import androidx.compose.runtime.Composable
import pe.bpm.core.designsystem.BpmChoiceOption
import pe.bpm.core.designsystem.BpmPagedChoiceSelector
import pe.bpm.features.workspace.domain.Location
import pe.bpm.features.workspace.domain.WorkspaceResource
import pe.bpm.features.workspace.presentation.WorkspaceState
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

/** Adapta la página de sedes del workspace sin acoplar funcionalidades entre sí. */
@Composable
internal fun WorkspaceSiteSelector(
    workspace: WorkspaceState,
    actions: WorkspaceViewModel,
    selectedId: String?,
    selectedName: String,
    enabled: Boolean,
    onSelect: (Location) -> Unit,
) {
    val sites = workspace.workspace?.locations.orEmpty()
    val page = workspace.pages[WorkspaceResource.Locations]
    BpmPagedChoiceSelector(
        sites.map { BpmChoiceOption(it.id, it.name) },
        selectedId,
        selectedName,
        page?.request?.query.orEmpty(),
        (page?.previous?.size ?: 0) + 1,
        page?.loading == true,
        page?.errors?.isNotEmpty() == true,
        page?.previous?.isNotEmpty() == true,
        page?.nextToken?.isNotEmpty() == true,
        enabled,
        "Buscar sede",
        "No hay sedes disponibles para esta búsqueda.",
        onSearch = { actions.pageSearch(WorkspaceResource.Locations, it) },
        onPrevious = { actions.pagePrevious(WorkspaceResource.Locations) },
        onNext = { actions.pageNext(WorkspaceResource.Locations) },
        onRetry = { actions.pageRetry(WorkspaceResource.Locations) },
        onSelect = { option -> sites.firstOrNull { it.id == option.id }?.let(onSelect) },
    )
}
