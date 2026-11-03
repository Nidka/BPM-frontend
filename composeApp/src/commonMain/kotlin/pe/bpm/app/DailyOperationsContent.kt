package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.operations.presentation.OperationsScreen
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.workspace.domain.WorkspaceResource
import pe.bpm.features.workspace.presentation.WorkspaceState
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

/** La raíz adapta sedes autorizadas; operaciones no depende de workspace. */
@Composable
internal fun DailyOperationsContent(
    userId: String,
    role: String,
    workspace: WorkspaceState,
    workspaceActions: WorkspaceViewModel,
    operations: OperationsViewModel,
    modifier: Modifier = Modifier,
) {
    val org = workspace.workspace?.organization?.id ?: return
    var siteId by rememberSaveable(userId, org) { mutableStateOf<String?>(null) }
    var siteName by rememberSaveable(userId, org) { mutableStateOf("") }
    var choosingSite by rememberSaveable(userId, org) { mutableStateOf(false) }
    val state by operations.state.collectAsStateWithLifecycle()
    val page = workspace.pages[WorkspaceResource.Locations]
    val sites = workspace.workspace.locations
    LaunchedEffect(userId, org, role, siteId) { operations.open(userId, org, role, siteId) }
    DisposableEffect(operations, userId, org) { onDispose { operations.reset() } }
    LaunchedEffect(state.resource, state.siteId) {
        if (state.resource.needsSite && siteId == null) choosingSite = true
    }
    Column(
        modifier.fillMaxSize().widthIn(max = 1100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (choosingSite) {
            Text("¿En qué sede trabajarás?", style = MaterialTheme.typography.titleLarge)
            BpmPagedChoiceSelector(
                sites.map { BpmChoiceOption(it.id, it.name) },
                siteId,
                siteName,
                page?.request?.query.orEmpty(),
                (page?.previous?.size ?: 0) + 1,
                page?.loading == true,
                page?.errors?.isNotEmpty() == true,
                page?.previous?.isNotEmpty() == true,
                page?.nextToken?.isNotEmpty() == true,
                state.form == null && !state.busy,
                "Buscar sede",
                "No hay sedes disponibles para esta búsqueda.",
                onSearch = { workspaceActions.pageSearch(WorkspaceResource.Locations, it) },
                onPrevious = { workspaceActions.pagePrevious(WorkspaceResource.Locations) },
                onNext = { workspaceActions.pageNext(WorkspaceResource.Locations) },
                onRetry = { workspaceActions.pageRetry(WorkspaceResource.Locations) },
                onSelect = {
                    siteId = it.id
                    siteName = it.label
                    choosingSite = false
                },
            )
            if (siteId != null || !state.resource.needsSite)
                BpmTextButton(onClick = { choosingSite = false }) {
                    Text("Seguir con la selección actual")
                }
        } else {
            if (siteId != null)
                Text("Sede: $siteName", style = MaterialTheme.typography.titleMedium)
            BpmTextButton(
                onClick = { choosingSite = true },
                enabled = state.form == null && !state.busy,
            ) {
                Text(if (siteId == null) "Elegir sede" else "Cambiar sede")
            }
            if (
                state.userId == userId &&
                    state.organizationId == org &&
                    state.role == role &&
                    state.siteId == siteId
            )
                OperationsScreen(operations, Modifier.weight(1f))
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
