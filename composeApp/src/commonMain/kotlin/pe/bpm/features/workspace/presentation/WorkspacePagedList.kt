package pe.bpm.features.workspace.presentation

import pe.bpm.core.designsystem.BpmSecondaryButton
import pe.bpm.core.designsystem.BpmTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.PageNavigation
import pe.bpm.core.designsystem.BpmSearchField
import pe.bpm.features.workspace.domain.WorkspaceResource

@Composable internal fun WorkspacePagedList(
    resource: WorkspaceResource, state: WorkspaceState,
    onSearch: (WorkspaceResource, String) -> Unit, onNext: (WorkspaceResource) -> Unit,
    onPrevious: (WorkspaceResource) -> Unit, onRetry: (WorkspaceResource) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val page = state.pages[resource] ?: WorkspacePageState()
    val label = when (resource) {
        WorkspaceResource.Locations -> "Buscar sedes"
        WorkspaceResource.Members -> "Buscar personas"
        WorkspaceResource.Courses -> "Buscar capacitaciones"
        WorkspaceResource.Incidents -> "Buscar incidencias"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BpmSearchField(page.request.query, { onSearch(resource, it) }, label, enabled = !state.loading && !state.saving, loading = page.loading)
        when {
            page.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Cargando resultados…") }
            page.errors.isNotEmpty() -> { Errors(page.errors); BpmSecondaryButton(onClick = { onRetry(resource) }) { Text("Reintentar") } }
            else -> content()
        }
        PageNavigation(page.number, page.previous.isNotEmpty(), page.nextToken.isNotEmpty(), page.loading || page.errors.isNotEmpty(),
            onPrevious = { onPrevious(resource) }, onNext = { onNext(resource) })
    }
}
