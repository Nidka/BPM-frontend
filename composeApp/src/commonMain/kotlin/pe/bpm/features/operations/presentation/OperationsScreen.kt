package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.*
import pe.bpm.features.operations.domain.*

/**
 * La sede se selecciona en el contenedor de la aplicación, sin dependencias entre funcionalidades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperationsScreen(
    viewModel: OperationsViewModel,
    modifier: Modifier = Modifier,
    /** Acción del CHEF sobre un lote (registrar consumo o descarte); la raíz la conecta. */
    onLotMovement: ((StorageLot) -> Unit)? = null,
    /** Fotos de una ejecución de limpieza propia (ejecución, ¿se puede editar?). */
    onCleaningPhotos: ((CleaningExecution) -> Unit)? = null,
    /** Cabecera del personal (WORKER): va dentro del desplazamiento; recibe si la lista está visible (sin formulario ni detalle). */
    workerHeader: (@Composable (listVisible: Boolean) -> Unit)? = null,
    /** Personal (WORKER): abre «Reportar un problema»; la raíz lo conecta. Sin él no hay botón flotante. */
    onReport: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    // El personal (WORKER) tiene su propia presentación; ADMIN y CHEF siguen como antes.
    val worker = state.role == "WORKER"
    // Registro rápido de una tarea (modo enfoque, pantalla completa): reemplaza a toda la lista mientras dura.
    if (worker && (state.form != null || state.taskDone != null)) {
        WorkerQuickTask(viewModel, onCleaningPhotos, "Volver a Tareas", modifier.fillMaxSize())
        return
    }
    val listVisible =
        state.form == null && state.detail == null && state.saved == null && state.picker == null
    // La cabecera del personal usa todo el ancho; lo demás (pestañas, lista, avisos) va en máx. 840 dp.
    Box(modifier.fillMaxWidth()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(if (worker) BpmSpacing.md else 12.dp),
    ) {
        if (!worker)
            Text("Operación diaria", style = MaterialTheme.typography.headlineSmall)
        val body: @Composable () -> Unit = {
            if (worker && state.form == null && state.detail == null) {
                BpmSegmentedTabs(
                    listOf(
                        BpmTabOption(OperationsResource.PLANS.name, "Por hacer"),
                        BpmTabOption(OperationsResource.EXECUTIONS.name, "Hechas"),
                    ),
                    state.resource.name,
                    { viewModel.resource(OperationsResource.valueOf(it)) },
                )
            } else if (state.form == null && state.detail == null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OperationsResource.entries
                        .filter { OperationsAccess.canRead(state.role, it) }
                        .forEach { resource ->
                            BpmFilterChip(
                                state.resource == resource,
                                { viewModel.resource(resource) },
                                label = { Text(resource.title) },
                                enabled = !state.loading && !state.busy,
                                reserveCheck = true,
                            )
                        }
                }
            }
            state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it) }
            pe.bpm.core.offline.PendingOperationsNotice("supply-receipts", "cleaning-executions")
            if (state.loading && state.rows.isEmpty()) BpmSkeletonList(rows = 3, card = false)
            // Lista–detalle: desde 840 dp el detalle de un registro va junto a la lista.
            val listBody: @Composable () -> Unit = {
                    if (state.resource.needsSite && state.siteId == null)
                        Text("Selecciona una sede para consultar esta sección.")
                    else if (worker && state.resource == OperationsResource.PLANS)
                        WorkerTodayList(state, viewModel, onCleaningPhotos)
                    else if (worker && state.resource == OperationsResource.EXECUTIONS)
                        WorkerDoneList(state, viewModel, onCleaningPhotos)
                    else {
                        BpmSearchField(
                            state.query,
                            viewModel::query,
                            "Buscar en ${state.resource.title.lowercase()}",
                            loading = state.loading,
                        )
                        if (!worker && OperationsAccess.canCreate(state.role, state.resource))
                            BpmChunkyButton(if (state.resource == OperationsResource.EXECUTIONS)
                                    "Registrar limpieza"
                                else
                                    when (state.resource) {
                                        OperationsResource.SUPPLIERS -> "Agregar proveedor"
                                        OperationsResource.PRODUCTS -> "Agregar producto"
                                        OperationsResource.RECEIPTS -> "Registrar recepción"
                                        OperationsResource.PLANS -> "Crear plan de limpieza"
                                        else -> "Registrar limpieza"
                                    }, viewModel::newForm, Modifier.bpmPrimaryButtonWidth(), enabled = !state.loading)
                        if (state.failure != null)
                            BpmTextButton(viewModel::refresh, enabled = !state.loading) {
                                Text("Reintentar consulta")
                            }
                        if (state.rows.isEmpty() && !state.loading && state.failure == null)
                            EmptyState("Sin registros", "Todavía no hay registros para esta consulta. Cambia la búsqueda para ver otros.")
                        if (state.resource == OperationsResource.LOTS)
                            Text(
                                "Los lotes más antiguos se muestran primero (PEPS). Revisa también su fecha de vencimiento antes de usarlos.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        val shownRows =
                            if (worker && state.resource == OperationsResource.PLANS)
                                sortByDueTime(state.rows) { (it as? OperationsEntry.PlanEntry)?.value?.dueTime.orEmpty() }
                            else state.rows
                        BpmGridLayout(shownRows) { entry, cardModifier ->
                            if (worker) WorkerTaskItem(entry, viewModel, onCleaningPhotos, cardModifier)
                            else BpmCard(cardModifier, onClick = { viewModel.detail(entry) }, onClickLabel = "Ver detalle", selected = state.detail == entry) {
                                Column(Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(entry.title(), style = MaterialTheme.typography.titleMedium)
                                    Text(entry.subtitle())
                                    BpmChunkyButton("Ver detalle", { viewModel.detail(entry) }, tone = BpmChunkyTone.White)
                                    if (
                                        entry is OperationsEntry.LotEntry &&
                                            onLotMovement != null &&
                                            StockAccess.canRecord(state.role) &&
                                            StockRules.available(entry.value)
                                    )
                                        BpmTextButton({ onLotMovement(entry.value) }) {
                                            Text("Registrar consumo o descarte")
                                        }
                                    if (entry is OperationsEntry.ExecutionEntry && onCleaningPhotos != null)
                                        BpmTextButton({ onCleaningPhotos(entry.value) }) {
                                            Text("Fotos de la limpieza")
                                        }
                                    if (
                                        entry is OperationsEntry.ProductEntry &&
                                            OperationsAccess.canCreate(
                                                state.role,
                                                OperationsResource.PRODUCTS,
                                            )
                                    )
                                        BpmTextButton({ viewModel.configure(entry.value) }) {
                                            Text("Configurar criterio térmico")
                                        }
                                }
                            }
                        }
                        PageNavigation(
                            state.page,
                            state.previous.isNotEmpty(),
                            state.nextToken.isNotEmpty(),
                            state.loading,
                            viewModel::previous,
                            viewModel::next,
                        )
                    }
            }
            when {
                state.picker != null -> OperationsPicker(state, viewModel)
                state.saved != null -> {
                    Text("Registro confirmado", style = MaterialTheme.typography.titleLarge)
                    OperationDetails(state.saved!!)
                    BpmChunkyButton("Volver a la lista", viewModel::closeForm, Modifier.bpmPrimaryButtonWidth(), enabled = !state.busy)
                }
                // Formulario: columna de lectura de 720 dp alineada con el título (specs/LAYOUT.md §4.1).
                state.form != null -> Column(Modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth()) { OperationForm(state, viewModel) }
                state.detail != null -> BpmListDetail(list = listBody, detail = { OperationDetails(state.detail!!, onBack = viewModel::closeDetail) })
                else -> listBody()
            }
        }
        // El personal: columna central de la maqueta (480/600 dp) con su cabecera y la lista de tareas por grupos.
        if (worker) BpmPlayColumns(Modifier.padding(bottom = if (listVisible) 96.dp else 0.dp)) { _ ->
            workerHeader?.invoke(listVisible)
            val detail = state.detail
            if (detail != null) OperationDetails(detail, onBack = viewModel::closeDetail)
            else WorkerTasksBody(state, viewModel, onCleaningPhotos)
        } else body()
    }
    // Botón flotante coral «Reportar»: sobre la columna, a su derecha, sin tapar la última fila.
    if (worker && listVisible && onReport != null) WorkerReportButton(onReport, enabled = !state.loading && !state.busy, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun OperationsPicker(state: OperationsState, vm: OperationsViewModel) {
    Text(
        "Seleccionar ${state.picker!!.title.lowercase()}",
        style = MaterialTheme.typography.titleLarge,
    )
    BpmSearchField(
        state.pickerQuery,
        vm::pickerQuery,
        "Buscar por nombre",
        loading = state.pickerLoading,
    )
    if (state.pickerRows.isEmpty() && !state.pickerLoading)
        Text("No hay coincidencias. Puedes cambiar la búsqueda.")
    state.pickerRows.forEach { entry -> // lista-apilada: selector dentro de un formulario
        BpmCard(Modifier.fillMaxWidth()) {
            Column(Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title())
                Text(entry.subtitle(), style = MaterialTheme.typography.bodySmall)
                val active = (entry as? OperationsEntry.PlanEntry)?.value?.active != false
                BpmChunkyButton(if (active) "Seleccionar" else "Plan inactivo", { vm.select(entry) }, tone = BpmChunkyTone.White, enabled = active && !state.pickerLoading)
            }
        }
    }
    PageNavigation(
        state.pickerPage,
        state.pickerPrevious.isNotEmpty(),
        state.pickerNext.isNotEmpty(),
        state.pickerLoading,
        vm::pickerPrevious,
        vm::pickerNext,
    )
    BpmTextButton(vm::closePicker) { Text("Volver al formulario") }
}

/** Ancho máximo de la lista «Mis tareas» del personal (pestañas, lista e incidencias). */
internal val WorkerTasksMaxWidth = 840.dp

/** Pide los planes de hoy de la sede una vez por sede: «Por hacer» y «Hechas · Hoy» salen del mismo `view=today`. */
@Composable
internal fun LoadToday(state: OperationsState, viewModel: OperationsViewModel) {
    LaunchedEffect(state.siteId, state.resource) {
        if (!state.todayLoaded && !state.todayLoading) viewModel.refreshToday()
    }
}

/** Título de sección de la lista del personal («Hoy», «Historial»). */
@Composable
internal fun WorkerSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
}

/**
 * «Por hacer» del personal: solo las tareas activas que aún no están hechas (vencidas, luego por hora límite y al
 * final las sin hora), en una columna de filas G6. Sin búsqueda ni páginas (las recorre el modelo de vista). Chips del servidor.
 */
@Composable
private fun WorkerTodayList(state: OperationsState, viewModel: OperationsViewModel, onCleaningPhotos: ((CleaningExecution) -> Unit)?) {
    LoadToday(state, viewModel)
    if (state.todayLoading && !state.todayLoaded) BpmSkeletonList(rows = 3, card = false)
    state.todayFailure?.let { failure ->
        Text(failureText(failure), color = MaterialTheme.colorScheme.error)
        BpmTextButton(viewModel::refreshToday, enabled = !state.todayLoading) { Text("Reintentar consulta") }
    }
    val pending = pendingPlans(state.todayPlans)
    if (state.todayLoaded && state.todayFailure == null && pending.isEmpty()) {
        if (state.todayPlans.isEmpty()) EmptyState("Sin tareas para hoy", "Esta sede no tiene planes de limpieza activos.")
        else EmptyState("Todo al día", "Terminaste las tareas de este periodo. Míralas en «Hechas».")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        pending.forEach { plan -> WorkerTaskItem(OperationsEntry.PlanEntry(plan), viewModel, onCleaningPhotos) }
    }
}

/**
 * «Hechas» del personal: arriba «Hoy» (las tareas que el servidor marca como hechas en su periodo) y luego el
 * historial de limpiezas registradas (con búsqueda y páginas), sin repetir las que ya están en «Hoy».
 */
@Composable
internal fun WorkerDoneList(state: OperationsState, viewModel: OperationsViewModel, onCleaningPhotos: ((CleaningExecution) -> Unit)?) {
    LoadToday(state, viewModel)
    val doneToday = donePlans(state.todayPlans)
    val history = withoutDoneToday(state.rows, doneToday) { (it as? OperationsEntry.ExecutionEntry)?.value?.input?.id.orEmpty() }
    if (doneToday.isNotEmpty()) {
        WorkerSectionTitle("Hoy")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            doneToday.forEach { plan -> WorkerTaskItem(OperationsEntry.PlanEntry(plan), viewModel, onCleaningPhotos) }
        }
        WorkerSectionTitle("Historial")
    }
    BpmSearchField(state.query, viewModel::query, "Buscar en hechas", loading = state.loading)
    if (state.failure != null)
        BpmTextButton(viewModel::refresh, enabled = !state.loading) { Text("Reintentar consulta") }
    if (history.isEmpty() && doneToday.isEmpty() && !state.loading && state.failure == null)
        EmptyState("Sin registros", "Todavía no hay limpiezas registradas. Cambia la búsqueda para ver otras.")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        history.forEach { entry -> WorkerTaskItem(entry, viewModel, onCleaningPhotos) }
    }
    PageNavigation(state.page, state.previous.isNotEmpty(), state.nextToken.isNotEmpty(), state.loading, viewModel::previous, viewModel::next)
}
