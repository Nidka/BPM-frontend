package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.incidents.presentation.IncidentsScreen
import pe.bpm.features.incidents.presentation.IncidentsViewModel
import pe.bpm.features.workspace.presentation.WorkspaceState
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

/** La raíz proporciona contexto autorizado y conecta fotos mediante callbacks. */
@Composable
internal fun IncidentsContent(
    userId: String,
    role: String,
    workspace: WorkspaceState,
    workspaceActions: WorkspaceViewModel,
    incidents: IncidentsViewModel,
    onEvidence: (String, String?, String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Personal (WORKER): sale del reporte (la X y «Listo»). Con él el WORKER ve el reporte rápido en modo enfoque. */
    onClose: (() -> Unit)? = null,
) {
    val org = workspace.workspace?.organization?.id ?: return
    var siteId by rememberSaveable(userId, org) { mutableStateOf<String?>(null) }
    var siteName by rememberSaveable(userId, org) { mutableStateOf("") }
    var zone by rememberSaveable(userId, org) { mutableStateOf("") }
    var choosing by rememberSaveable(userId, org) { mutableStateOf(true) }
    val state by incidents.state.collectAsStateWithLifecycle()
    val editor by incidents.editor.state.collectAsStateWithLifecycle()
    LaunchedEffect(userId, org, role, siteId, zone) {
        incidents.open(userId, org, role, siteId, zone)
    }
    DisposableEffect(incidents, userId, org) { onDispose { incidents.reset() } }
    // El personal con una sola sede reporta sin elegirla; con varias elige una vez.
    val worker = role == "WORKER" && onClose != null
    val onlySite = if (worker) workspace.workspace?.locations?.singleOrNull() else null
    LaunchedEffect(onlySite?.id) {
        if (onlySite != null && siteId == null) {
            siteId = onlySite.id
            siteName = onlySite.name
            zone = onlySite.timezone
            choosing = false
        }
    }
    if (worker && !choosing && siteId != null) {
        val ready = state.userId == userId && state.organizationId == org && state.siteId == siteId && state.role == role
        var showList by rememberSaveable(userId, org) { mutableStateOf(false) }
        when {
            !ready -> LinearProgressIndicator(modifier.fillMaxWidth())
            showList && editor.form == null -> Column(modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmTextButton({ showList = false }, modifier = Modifier.heightIn(min = BpmSpacing.touch)) {
                    BpmIcon(BpmSymbol.Back)
                    Spacer(Modifier.width(BpmSpacing.sm))
                    Text("Volver a reportar")
                }
                IncidentsScreen(incidents, Modifier.weight(1f), onEvidence)
            }
            else -> pe.bpm.features.incidents.presentation.WorkerReportContent(incidents, onClose!!, onEvidence,
                onOpenList = { incidents.picker(null); showList = true }, modifier = modifier.fillMaxSize())
        }
        return
    }
    Column(
        modifier.fillMaxSize().then(if (worker) Modifier.padding(16.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (choosing) {
            Text("¿En qué sede trabajarás?", style = MaterialTheme.typography.titleLarge)
            WorkspaceSiteSelector(
                workspace,
                workspaceActions,
                siteId,
                siteName,
                editor.form == null && !editor.busy,
            ) {
                siteId = it.id
                siteName = it.name
                zone = it.timezone
                choosing = false
            }
            if (siteId != null)
                BpmTextButton({ choosing = false }) { Text("Seguir con la selección actual") }
        } else {
            Text("Sede: $siteName", style = MaterialTheme.typography.titleMedium)
            BpmTextButton({ choosing = true }, enabled = editor.form == null && !editor.busy) {
                Text("Cambiar sede")
            }
            if (
                state.userId == userId &&
                    state.organizationId == org &&
                    state.siteId == siteId &&
                    state.role == role
            )
                IncidentsScreen(incidents, Modifier.weight(1f), onEvidence)
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
