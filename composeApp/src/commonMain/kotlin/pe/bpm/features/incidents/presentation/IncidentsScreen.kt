package pe.bpm.features.incidents.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.incidents.domain.*

/** La raíz conecta evidencia privada; esta funcionalidad no importa media. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncidentsScreen(
    viewModel: IncidentsViewModel,
    modifier: Modifier = Modifier,
    onEvidence: (incidentId: String, actionId: String?, stage: String, writable: Boolean) -> Unit =
        { _, _, _, _ ->
        },
) {
    val s by viewModel.state.collectAsState()
    val e by viewModel.editor.state.collectAsState()
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (s.catalogMode) "Criterios de incidencias" else "Incidencias y acciones correctivas",
            style = MaterialTheme.typography.headlineSmall,
        )
        s.failure?.let { Text(incidentFailureText(it), color = MaterialTheme.colorScheme.error) }
        s.notice?.let { Text(it) }
        pe.bpm.core.offline.PendingOperationsNotice("incidents")
        if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Lista–detalle: desde 840 dp el detalle de una incidencia se muestra junto a la lista.
        val listBody: @Composable () -> Unit = {
                if (IncidentsAccess.canAuthor(s.role))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BpmFilterChip(
                            !s.catalogMode,
                            { viewModel.catalogMode(false) },
                            label = { Text("Incidencias") },
                        )
                        BpmFilterChip(
                            s.catalogMode,
                            { viewModel.catalogMode(true) },
                            label = { Text("Configurar criterios") },
                        )
                    }
                BpmSearchField(
                    if (s.catalogMode) s.catalog.query else s.incidents.query,
                    viewModel::query,
                    "Buscar por nombre o descripción",
                    loading = s.loading,
                )
                if (!s.catalogMode)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        BpmFilterChip(
                            s.status == null,
                            { viewModel.filter(null) },
                            label = { Text("Todos los estados") },
                        )
                        IncidentStatus.entries.forEach { status ->
                            BpmFilterChip(
                                s.status == status,
                                { viewModel.filter(status) },
                                label = { Text(incidentStatusLabel(status.name)) },
                            )
                        }
                    }
                if (e.hasDraft)
                    BpmChunkyButton("Continuar borrador guardado", viewModel.editor::resume, Modifier.bpmPrimaryButtonWidth(), enabled = !s.loading)
                else if (s.catalogMode || IncidentsAccess.canReport(s.role))
                    BpmChunkyButton(if (s.catalogMode) "Crear criterio" else "Reportar incidencia", if (s.catalogMode) viewModel::newCatalog else viewModel::newReport, Modifier.bpmPrimaryButtonWidth(), enabled = !s.loading)
                if (s.failure != null)
                    BpmTextButton(viewModel::refresh) { Text("Reintentar consulta") }
                if (s.catalogMode) {
                    if (s.catalog.rows.isEmpty() && !s.loading && s.failure == null)
                        EmptyState("Sin criterios", "Todavía no hay criterios para esta consulta. Cambia la búsqueda o usa «Crear criterio».")
                    BpmGridLayout(s.catalog.rows) { value, cardModifier ->
                        BpmCard(cardModifier) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(value.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${value.code} · Versión ${value.versionNumber} · ${value.area}"
                                )
                                Text(value.normativeCriterion)
                                Text(
                                    "Riesgo: ${incidentRiskLabel(value.riskLevel)} · ${if (value.evidenceRequired) "Requiere fotos antes y después" else "Fotos opcionales"}"
                                )
                                BpmChunkyButton("Crear siguiente versión", { viewModel.newVersion(value) }, tone = BpmChunkyTone.White)
                            }
                        }
                    }
                    PageNavigation(
                        s.catalog.number,
                        s.catalog.previous.isNotEmpty(),
                        s.catalog.next.isNotEmpty(),
                        s.loading,
                        viewModel::previous,
                        viewModel::next,
                    )
                } else {
                    if (s.incidents.rows.isEmpty() && !s.loading && s.failure == null)
                        EmptyState("Sin incidencias", "No hay incidencias para esta consulta. Cambia la búsqueda o el estado para ver otras.")
                    if (s.role == "WORKER")
                        Text(
                            "Aquí ves las incidencias que reportaste o te asignaron.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    BpmGridLayout(s.incidents.rows) { value, cardModifier ->
                        BpmCard(cardModifier, onClick = { viewModel.detail(value.id) }, onClickLabel = "Ver incidencia", selected = s.detail?.summary?.id == value.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(value.name, style = MaterialTheme.typography.titleMedium)
                                pe.bpm.core.offline.PendingBadge(value.id)
                                Text(
                                    "${incidentStatusLabel(value.status.name)} · ${value.area} · Riesgo ${incidentRiskLabel(value.riskLevel)}"
                                )
                                Text(value.description)
                                Text(
                                    "Reportó ${value.reportedByName} · ${displayDateTime(value.createdAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                BpmChunkyButton("Ver incidencia", { viewModel.detail(value.id) }, tone = BpmChunkyTone.White)
                            }
                        }
                    }
                    PageNavigation(
                        s.incidents.number,
                        s.incidents.previous.isNotEmpty(),
                        s.incidents.next.isNotEmpty(),
                        s.loading,
                        viewModel::previous,
                        viewModel::next,
                    )
                }
        }
        when {
            s.siteId == null -> Text("Selecciona una sede para consultar y reportar incidencias.")
            !s.authorized ->
                if (!s.loading) BpmChunkyButton("Reintentar acceso", viewModel::refresh, tone = BpmChunkyTone.White)
            s.picker != null -> IncidentChoices(s, viewModel)
            e.form != null -> IncidentFormContent(s, e, viewModel)
            s.showHistory -> {
                Text("Historial de la incidencia", style = MaterialTheme.typography.titleLarge)
                if (s.history.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.history.failure?.let {
                    Text(incidentFailureText(it))
                    BpmTextButton(viewModel::history) { Text("Reintentar historial") }
                }
                s.history.rows.forEach { event -> // lista-apilada: cronología en orden de eventos
                    BpmCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${incidentStatusLabel(event.fromStatus)} → ${incidentStatusLabel(event.toStatus)}"
                            )
                            Text("${event.actorName} · ${displayDateTime(event.createdAt)}")
                            event.note?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                        }
                    }
                }
                PageNavigation(
                    s.history.number,
                    s.history.previous.isNotEmpty(),
                    s.history.next.isNotEmpty(),
                    s.history.loading,
                    viewModel::historyPrevious,
                    viewModel::historyNext,
                )
                BpmChunkyButton("Volver al detalle", viewModel::closeHistory, tone = BpmChunkyTone.White)
            }
            s.detail != null -> BpmListDetail(list = listBody, detail = { IncidentDetailContent(s, viewModel, onEvidence) })
            else -> listBody()
        }
    }
}

@Composable
private fun IncidentChoices(s: IncidentsState, vm: IncidentsViewModel) {
    val catalog = s.picker == IncidentPicker.CATALOG
    val loading = if (catalog) s.catalogChoices.loading else s.choices.loading
    val failure = if (catalog) s.catalogChoices.failure else s.choices.failure
    Text(
        if (catalog) "Seleccionar criterio" else "Seleccionar responsable",
        style = MaterialTheme.typography.titleLarge,
    )
    BpmSearchField(
        if (catalog) s.catalogChoices.query else s.choices.query,
        vm::pickerQuery,
        "Buscar por nombre",
        loading = loading,
    )
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    failure?.let {
        Text(incidentFailureText(it))
        BpmTextButton({ vm.picker(s.picker) }) { Text("Reintentar consulta") }
    }
    if (catalog)
        s.catalogChoices.rows.forEach { value -> // lista-apilada: selector dentro de un formulario
            BpmCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(value.name)
                    Text(
                        "${value.area} · ${incidentRiskLabel(value.riskLevel)} · Versión ${value.versionNumber}"
                    )
                    Text(value.normativeCriterion)
                    BpmChunkyButton("Seleccionar criterio", { vm.select(value) }, tone = BpmChunkyTone.White, enabled = !loading)
                }
            }
        }
    else
        s.choices.rows.forEach { value -> // lista-apilada: selector dentro de un formulario
            BpmCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(value.displayName)
                    Text(when (value.role) { "ADMIN" -> "Administrador"; "CHEF" -> "Chef"; else -> "Colaborador" })
                    BpmChunkyButton("Seleccionar responsable", { vm.select(value) }, tone = BpmChunkyTone.White, enabled = !loading)
                }
            }
        }
    val empty = if (catalog) s.catalogChoices.rows.isEmpty() else s.choices.rows.isEmpty()
    if (empty && !loading && failure == null) Text("No hay coincidencias para esta búsqueda.")
    PageNavigation(
        if (catalog) s.catalogChoices.number else s.choices.number,
        if (catalog) s.catalogChoices.previous.isNotEmpty() else s.choices.previous.isNotEmpty(),
        if (catalog) s.catalogChoices.next.isNotEmpty() else s.choices.next.isNotEmpty(),
        loading,
        vm::pickerPrevious,
        vm::pickerNext,
    )
    BpmChunkyButton("Volver al formulario", { vm.picker(null) }, tone = BpmChunkyTone.White)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncidentDetailContent(
    s: IncidentsState,
    vm: IncidentsViewModel,
    onEvidence: (String, String?, String, Boolean) -> Unit,
) {
    val d = s.detail ?: return
    val i = d.summary
    val workflow = IncidentsAccess.visibleActions(d.allowedActions, i.status, d.actions, s.userId)
    // Detalle del registro (specs/LAYOUT.md §5): la primera acción del flujo es la principal; las demás, secundarias.
    BpmDetailPane(i.name, status = BpmRecordStatus(incidentStatusLabel(i.status.name), incidentStatusTone(i.status)),
        subtitle = i.description, onBack = vm::closeDetail,
        actions = {
            workflow.forEachIndexed { index, type ->
                val self = type == IncidentActionType.VERIFY && IncidentsAccess.isSelfVerification(d.actions, s.userId)
                val label = if (self) "Validar sin otro validador" else incidentActionLabel(type)
                if (index == 0) BpmChunkyButton(label, { vm.action(type) }, Modifier.bpmPrimaryButtonWidth(), enabled = !s.loading)
                else BpmChunkyButton(label, { vm.action(type) }, tone = BpmChunkyTone.White, enabled = !s.loading)
            }
            BpmChunkyButton("Consultar historial", vm::history, tone = BpmChunkyTone.White)
        }) {
        pe.bpm.core.offline.PendingBadge(i.id)
        BpmSection("Datos de la incidencia") {
            BpmDetailFields(listOf(
                BpmDetailField("Área", i.area),
                BpmDetailField("Riesgo", incidentRiskLabel(i.riskLevel).replaceFirstChar { it.uppercase() }),
                BpmDetailField("Reportó", i.reportedByName),
                BpmDetailField("Fecha del reporte", displayDateTime(i.createdAt)),
            ))
        }
        BpmSection("Criterio aplicado", description = "Versión ${d.catalogVersion} del catálogo") { Text(d.normativeCriterion) }
        val supervisor = s.role in setOf("CHEF", "ADMIN", "SUPER_ADMIN")
        val beforeWritable =
            (supervisor || d.reportedBy == s.userId) &&
                i.status in
                    setOf(IncidentStatus.OPEN, IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS)
        BpmChunkyButton("Fotos antes (${d.beforeCount})", { onEvidence(i.id, null, "BEFORE", beforeWritable) }, tone = BpmChunkyTone.White)
        d.actions.forEach { action -> // lista-apilada: acciones del detalle en orden
            BpmCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Responsable: ${action.responsibleName}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(action.description)
                    Text("Plazo: ${displayDateTime(action.dueAt)}")
                    Text("Estado de la acción: ${incidentStatusLabel(action.status)}")
                    if (action.selfValidated) Eyebrow("Autovalidada")
                    action.resolutionNote
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Text("Corrección realizada: $it") }
                    val writable =
                        IncidentsAccess.canAttend(s.role) &&
                            action.responsibleUserId == s.userId &&
                            i.status == IncidentStatus.IN_PROGRESS &&
                            action.status == "IN_PROGRESS"
                    BpmChunkyButton("Fotos después", { onEvidence(i.id, action.id, "AFTER", writable) }, tone = BpmChunkyTone.White)
                }
            }
        }
        if (d.evidenceRequired)
            Text(
                "Para validar se necesita al menos una foto confirmada antes y una después de la acción."
            )
        Text("Siguiente paso", style = MaterialTheme.typography.titleMedium)
        Text(
            when (i.status) {
                IncidentStatus.OPEN -> "Un supervisor debe asignar responsable, plan y plazo."
                IncidentStatus.ASSIGNED -> "La persona responsable debe iniciar la atención."
                IncidentStatus.IN_PROGRESS ->
                    "La persona responsable debe realizar la corrección, adjuntar sus fotos y describir el resultado."
                IncidentStatus.RESOLVED ->
                    "Otra persona supervisora debe revisar la corrección y validarla o devolverla con un motivo."
                IncidentStatus.CLOSED -> "La corrección fue validada y la incidencia está cerrada."
                IncidentStatus.VERIFIED -> "Esta incidencia conserva una validación histórica."
            }
        )
    }
}

internal fun incidentStatusTone(v: IncidentStatus) =
    when (v) {
        IncidentStatus.OPEN, IncidentStatus.RESOLVED -> BpmTone.Warning
        IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS -> BpmTone.Info
        IncidentStatus.CLOSED, IncidentStatus.VERIFIED -> BpmTone.Success
    }

internal fun incidentActionLabel(v: IncidentActionType) =
    when (v) {
        IncidentActionType.ASSIGN -> "Asignar acción correctiva"
        IncidentActionType.START -> "Iniciar atención"
        IncidentActionType.RESOLVE -> "Registrar corrección"
        IncidentActionType.VERIFY -> "Validar y cerrar"
        IncidentActionType.RETURN -> "Devolver para corregir"
    }

internal fun incidentRiskLabel(v: IncidentRisk) =
    when (v) {
        IncidentRisk.LOW -> "bajo"
        IncidentRisk.MEDIUM -> "medio"
        IncidentRisk.HIGH -> "alto"
        IncidentRisk.CRITICAL -> "crítico"
    }

internal fun incidentStatusLabel(v: String) =
    when (v) {
        "OPEN" -> "Abierta"
        "ASSIGNED" -> "Asignada"
        "PENDING" -> "Pendiente de inicio"
        "CANCELLED" -> "Cancelada"
        "IN_PROGRESS" -> "En atención"
        "RESOLVED" -> "Pendiente de validación"
        "CLOSED",
        "VERIFIED" -> "Validada"
        "" -> "Reporte"
        else -> "Registrada"
    }

internal fun incidentFailureText(v: IncidentsFailure) =
    when (v) {
        IncidentsFailure.SESSION -> "La sesión terminó. Vuelve a ingresar."
        IncidentsFailure.DENIED -> "Ya no tienes acceso a esta sede o a esta operación."
        IncidentsFailure.CONFLICT ->
            "El registro cambió o la operación fue rechazada. Guarda y vuelve para consultar su estado; descarta este borrador si necesitas iniciar una acción nueva."
        IncidentsFailure.LOCAL ->
            "No se pudo guardar la copia local. Reintenta antes de salir o enviar."
        IncidentsFailure.CONNECTION ->
            "No pudimos conectar. Puedes reintentar sin duplicar el envío."
        IncidentsFailure.INVALID -> "Revisa los datos y los requisitos de la operación."
        IncidentsFailure.INVALID_PAGE ->
            "Esta página ya no está disponible. Reintenta desde la primera página."
        IncidentsFailure.NOT_FOUND -> "El registro ya no está disponible."
        IncidentsFailure.RATE_LIMITED -> "Hay muchas solicitudes. Espera un momento y reintenta."
        IncidentsFailure.INVALID_RESPONSE ->
            "No pudimos verificar la respuesta del servidor. Conservamos el envío pendiente."
        IncidentsFailure.UNAVAILABLE -> "El servicio no está disponible. Puedes reintentar."
        IncidentsFailure.QUEUED -> "Guardado en este dispositivo. Se enviará al recuperar la conexión."
    }
