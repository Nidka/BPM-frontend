package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.PageNavigation
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
        OutlinedTextField(page.request.query, { onSearch(resource, it) }, label = { Text(label) }, singleLine = true, enabled = !state.loading && !state.saving,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        when {
            page.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Cargando resultados…") }
            page.errors.isNotEmpty() -> { Errors(page.errors); OutlinedButton(onClick = { onRetry(resource) }) { Text("Reintentar") } }
            else -> content()
        }
        PageNavigation(page.number, page.previous.isNotEmpty(), page.nextToken.isNotEmpty(), page.loading || page.errors.isNotEmpty(),
            onPrevious = { onPrevious(resource) }, onNext = { onNext(resource) })
    }
}
