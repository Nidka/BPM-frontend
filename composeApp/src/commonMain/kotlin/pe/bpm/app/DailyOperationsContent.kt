package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.operations.domain.CleaningExecution
import pe.bpm.features.operations.domain.ShiftAccess
import pe.bpm.features.operations.domain.StockAccess
import pe.bpm.features.operations.presentation.OperationsScreen
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.operations.presentation.ShiftsScreen
import pe.bpm.features.operations.presentation.ShiftsViewModel
import pe.bpm.features.operations.presentation.StockMovementsScreen
import pe.bpm.features.operations.presentation.StockMovementsViewModel
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
 sourceSiteId:String?=null,sourceSiteName:String="",sourceReceiptId:String?=null,
    shifts: ShiftsViewModel? = null,
    stock: StockMovementsViewModel? = null,
    onCleaningPhotos: ((CleaningExecution) -> Unit)? = null,
    /** Personal (WORKER): abre «Reportar un problema» desde el botón flotante de «Tareas». */
    onIncidents: (() -> Unit)? = null,
) {
    val org = workspace.workspace?.organization?.id ?: return
    var siteId by rememberSaveable(userId, org) { mutableStateOf<String?>(sourceSiteId) }
    var siteName by rememberSaveable(userId, org) { mutableStateOf(sourceSiteName) }
    var choosingSite by rememberSaveable(userId, org) { mutableStateOf(false) }
    val state by operations.state.collectAsStateWithLifecycle()
    LaunchedEffect(userId, org, role, siteId) {
        operations.open(userId, org, role, siteId)
        if(sourceReceiptId!=null && siteId==sourceSiteId) operations.receiptDetail(sourceReceiptId)
    }
    DisposableEffect(operations, userId, org) { onDispose { operations.reset() } }
    // Secciones adicionales: turnos (todos menos WORKER) y movimientos de stock (supervisión y CHEF).
    val showShifts = shifts != null && role != "WORKER" && ShiftAccess.canRead(role)
    val showStock = stock != null && StockAccess.canView(role)
    var section by rememberSaveable(userId, org) { mutableStateOf("daily") }
    LaunchedEffect(userId, org, role, siteId, showShifts, showStock) {
        if (showShifts) shifts?.open(userId, org, role, siteId)
        if (showStock) stock?.open(userId, org, role, siteId)
    }
    DisposableEffect(shifts, stock, userId, org) { onDispose { shifts?.reset(); stock?.reset() } }
    val stockState = stock?.state?.collectAsStateWithLifecycle()?.value
    // Un movimiento confirmado cambia el saldo: se vuelve a consultar el stock PEPS.
    LaunchedEffect(stockState?.saved?.input?.id) { if (stockState?.saved != null) operations.refresh() }
    // El personal con una sola sede la usa sin preguntar; con varias elige como siempre.
    // Solo cuenta si la lista de sedes está completa (una página, sin búsqueda ni más páginas).
    val sitesPage = workspace.pages[pe.bpm.features.workspace.domain.WorkspaceResource.Locations]
    val sitesComplete = sitesPage == null || (sitesPage.nextToken.isEmpty() && sitesPage.previous.isEmpty() && sitesPage.request.query.isBlank())
    val onlySite = if (role == "WORKER" && sitesComplete) workspace.workspace?.locations?.singleOrNull() else null
    LaunchedEffect(role, siteId, onlySite?.id) {
        if (role == "WORKER" && siteId == null && onlySite != null) {
            siteId = onlySite.id
            siteName = onlySite.name
        }
    }
    LaunchedEffect(state.resource, state.siteId, onlySite?.id) {
        if (state.resource.needsSite && siteId == null && !(role == "WORKER" && onlySite != null)) choosingSite = true
    }
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (choosingSite) {
            if (role == "WORKER") Text("Tareas", style = BpmPlayType.h1, color = BpmPlayColors.ink)
            Text("¿En qué sede trabajarás?", style = MaterialTheme.typography.titleLarge)
            WorkspaceSiteSelector(
                workspace,
                workspaceActions,
                siteId,
                siteName,
                state.form == null && !state.busy,
            ) {
                siteId = it.id
                siteName = it.name
                choosingSite = false
            }
            if (siteId != null || !state.resource.needsSite)
                BpmTextButton(onClick = { choosingSite = false }) {
                    Text("Seguir con la selección actual")
                }
        } else {
            if (role != "WORKER") {
                if (siteId != null)
                    Text("Sede: $siteName", style = MaterialTheme.typography.titleMedium)
                BpmTextButton(
                    onClick = { choosingSite = true },
                    enabled = state.form == null && !state.busy,
                ) {
                    Text(if (siteId == null) "Elegir sede" else "Cambiar sede")
                }
            }
            if ((showShifts || showStock) && state.form == null) {
                // Tres chips no caben en una fila a 360 dp: se envuelven en lugar de cortarse.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BpmFilterChip(section == "daily", { section = "daily" }, label = { Text("Operación") })
                    if (showStock) BpmFilterChip(section == "stock", { section = "stock" }, label = { Text("Consumos y descartes") })
                    if (showShifts) BpmFilterChip(section == "shifts", { section = "shifts" }, label = { Text("Turnos") })
                }
            }
            if (section == "stock" && showStock && state.form == null) StockMovementsScreen(stock!!, Modifier.weight(1f))
            else if (section == "shifts" && showShifts && state.form == null) pe.bpm.core.offline.ServerOnly(Modifier.weight(1f)) { ShiftsScreen(shifts!!, Modifier.weight(1f)) }
            else if (
                state.userId == userId &&
                    state.organizationId == org &&
                    state.role == role &&
                    state.siteId == siteId
            )
                OperationsScreen(
                    operations,
                    Modifier.weight(1f),
                    onLotMovement = if (showStock && stock != null) { lot -> stock.start(lot); section = "stock" } else null,
                    onCleaningPhotos = onCleaningPhotos,
                    onReport = if (role == "WORKER") onIncidents else null,
                    workerHeader = if (role == "WORKER") { listVisible ->
                        WorkerTasksHeader(
                            siteName = siteName.takeIf { siteId != null },
                            onChangeSite = { choosingSite = true },
                            enabled = !state.loading && !state.busy,
                            showActions = listVisible,
                        )
                    } else null,
                )
            else BpmSkeletonList(rows = 4, card = false)
        }
    }
}
