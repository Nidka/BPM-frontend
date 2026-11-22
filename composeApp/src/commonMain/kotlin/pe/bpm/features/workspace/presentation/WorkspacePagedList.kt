package pe.bpm.features.workspace.presentation

import pe.bpm.core.designsystem.BpmSecondaryButton
import pe.bpm.core.designsystem.BpmTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.BpmPagedList
import pe.bpm.core.designsystem.BpmSpacing
import pe.bpm.core.designsystem.BpmSearchField
import pe.bpm.features.workspace.domain.WorkspaceResource

@Composable internal fun WorkspacePagedList(
    resource: WorkspaceResource, state: WorkspaceState,
    onSearch: (WorkspaceResource, String) -> Unit, onNext: (WorkspaceResource) -> Unit,
    onPrevious: (WorkspaceResource) -> Unit, onRetry: (WorkspaceResource) -> Unit,
    fullWidthSearch: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val page = state.pages[resource] ?: WorkspacePageState()
    val label = when (resource) {
        WorkspaceResource.Locations -> "Buscar sedes"
        WorkspaceResource.Members -> "Buscar personas"
        WorkspaceResource.Courses -> "Buscar capacitaciones"
        WorkspaceResource.Incidents -> "Buscar incidencias"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
        BpmSearchField(page.request.query, { onSearch(resource, it) }, label, enabled = !state.loading && !state.saving, loading = page.loading, fullWidth = fullWidthSearch)
        // Estados y paginación del sistema (specs/LAYOUT.md §3.3); el vacío lo decide cada lista dentro de [content].
        BpmPagedList(loading = page.loading, failure = page.errors.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.message },
            isEmpty = false, pageNumber = page.number, canPrevious = page.previous.isNotEmpty(), canNext = page.nextToken.isNotEmpty(),
            onRetry = { onRetry(resource) }, onPrevious = { onPrevious(resource) }, onNext = { onNext(resource) },
            loadingLabel = "Cargando resultados…", empty = {}, content = content)
    }
}
