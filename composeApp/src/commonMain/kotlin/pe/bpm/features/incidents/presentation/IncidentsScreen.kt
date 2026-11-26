package pe.bpm.features.incidents.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
        if (s.loading && (if (s.catalogMode) s.catalog.rows.isEmpty() else s.incidents.rows.isEmpty())) BpmSkeletonRecordCards(3, label = "Cargando incidencias")
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
                    BpmChipRow {
                        BpmFilterChip(
                            s.status == null,
                            { viewModel.filter(null) },
                            label = { Text("Todas") },
                            reserveCheck = true,
                        )
                        IncidentStatus.entries.filter { it != IncidentStatus.VERIFIED }.forEach { status ->
                            BpmFilterChip(
                                s.status == status,
                                { viewModel.filter(status) },
                                label = { Text(incidentStatusLabel(status.name)) },
                                reserveCheck = true,
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
                        EmptyState("Sin criterios", "Todavía no hay criterios para esta consulta. Cambia la búsqueda o crea el primero; mientras, todos pueden reportar con las categorías integradas.") { BpmChunkyButton("Crear criterio", viewModel::newCatalog, Modifier.bpmPrimaryButtonWidth()) }
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
                        EmptyState("Sin incidencias", if (s.status == null && s.incidents.query.isBlank()) "Todavía no hay incidencias en esta sede. Cuando alguien reporte un problema, aparecerá aquí."
                        else "No hay incidencias para esta consulta. Cambia la búsqueda o el estado para ver otras.")
                    if (s.role == "WORKER")
                        Text(
                            "Aquí ves las incidencias que reportaste o te asignaron.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    BpmGridLayout(s.incidents.rows) { value, cardModifier ->
                        IncidentCard(value, selected = s.detail?.summary?.id == value.id, onOpen = { viewModel.detail(value.id) }, modifier = cardModifier)
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
            // Solo «Elegir responsable» ocupa la pantalla: las categorías de «Reportar» se piden en segundo plano y se muestran como fichas en
            // el formulario (si el selector de criterios las tapara, con un catálogo vacío no habría forma de salir de la pantalla).
            s.picker == IncidentPicker.ASSIGNEE -> IncidentChoices(s, viewModel)
            e.form != null -> IncidentFormContent(s, e, viewModel)
            s.showHistory -> {
                Text("Historial de la incidencia", style = MaterialTheme.typography.titleLarge)
                if (s.history.loading) BpmSkeletonList(rows = 3, card = false)
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
    if (loading) BpmSkeletonList(rows = 3, card = false)
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

/** Tarjeta de la lista: icono por estado, nombre, ficha de estado con icono, descripción corta y quién la reportó. */
@Composable
private fun IncidentCard(value: IncidentSummary, selected: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    BpmCard(modifier, onClick = onOpen, onClickLabel = "Ver incidencia", selected = selected) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            BpmIconTile(incidentStatusSymbol(value.status), incidentStatusTone(value.status).playTone())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value.name, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (value.description.isNotBlank() && value.description != value.name)
                    Text(value.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            BpmIcon(BpmSymbol.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pe.bpm.core.offline.PendingBadge(value.id)
        BpmChipRow {
            BpmStatusChip(incidentStatusLabel(value.status.name), incidentStatusTone(value.status), symbol = incidentStatusSymbol(value.status))
            BpmStatusChip("Riesgo ${incidentRiskLabel(value.riskLevel)}", incidentRiskTone(value.riskLevel))
        }
        Text(
            "${value.area} · ${value.reportedByName} · ${displayDateTime(value.createdAt)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Qué hacer ahora, según el estado y lo que esta persona puede hacer: título, pasos y la acción principal. */
internal data class IncidentGuide(
    val symbol: BpmSymbol, val tone: BpmPlayTone, val eyebrow: String, val title: String, val message: String?, val steps: List<String>,
)

internal fun incidentGuide(status: IncidentStatus, canAct: Boolean): IncidentGuide = when (status) {
    IncidentStatus.OPEN ->
        if (canAct) IncidentGuide(BpmSymbol.Alert, BpmPlayTone.Error, "Qué sigue", "Asigna quién lo corrige", null,
            listOf("Elige a la persona responsable.", "Dile qué debe hacer.", "Ponle una fecha y hora límite."))
        else IncidentGuide(BpmSymbol.Clock, BpmPlayTone.Gold, "Qué sigue", "Tu reporte ya llegó", "Tu supervisor lo revisa y asigna a alguien para corregirlo. Te avisaremos.", emptyList())
    IncidentStatus.ASSIGNED ->
        if (canAct) IncidentGuide(BpmSymbol.Play, BpmPlayTone.Action, "Qué sigue", "Empieza la corrección", null,
            listOf("Lee qué se pidió abajo.", "Toca «Empezar a corregir» cuando estés en el lugar."))
        else IncidentGuide(BpmSymbol.Clock, BpmPlayTone.Gold, "Qué sigue", "Ya tiene responsable", "La persona asignada debe empezar la corrección dentro del plazo.", emptyList())
    IncidentStatus.IN_PROGRESS ->
        if (canAct) IncidentGuide(BpmSymbol.Wrench, BpmPlayTone.Streak, "Qué sigue", "Registra la corrección", null,
            listOf("Haz la corrección en el lugar.", "Toma una foto de cómo quedó (botón «Foto después»).", "Cuenta qué hiciste y envíalo."))
        else IncidentGuide(BpmSymbol.Wrench, BpmPlayTone.Streak, "Qué sigue", "Se está corrigiendo", "La persona responsable debe registrar la corrección con su foto.", emptyList())
    IncidentStatus.RESOLVED ->
        if (canAct) IncidentGuide(BpmSymbol.Eye, BpmPlayTone.Gold, "Qué sigue", "Revisa y valida la corrección", null,
            listOf("Mira las fotos del antes y del después.", "Si quedó bien, valida y cierra.", "Si no, devuélvela y explica qué falta."))
        else IncidentGuide(BpmSymbol.Eye, BpmPlayTone.Gold, "Qué sigue", "Esperando validación", "Otra persona supervisora debe revisar la corrección.", emptyList())
    IncidentStatus.CLOSED, IncidentStatus.VERIFIED ->
        IncidentGuide(BpmSymbol.Check, BpmPlayTone.Correct, "Listo", "Corregida y cerrada", "La corrección fue validada. No hay nada más que hacer.", emptyList())
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
    val supervisor = s.role in setOf("CHEF", "ADMIN", "SUPER_ADMIN")
    val beforeWritable =
        (supervisor || d.reportedBy == s.userId) &&
            i.status in setOf(IncidentStatus.OPEN, IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS)
    val guide = incidentGuide(i.status, canAct = workflow.isNotEmpty())
    // Detalle del registro (specs/LAYOUT.md §5): sin acciones sueltas arriba; lo que sigue va en la tarjeta «Qué sigue».
    BpmDetailPane(i.name, status = BpmRecordStatus(incidentStatusLabel(i.status.name), incidentStatusTone(i.status)),
        subtitle = i.description.takeIf { it.isNotBlank() && it != i.name }, onBack = vm::closeDetail, eyebrow = i.area,
        chips = listOf(BpmDetailChip("Riesgo ${incidentRiskLabel(i.riskLevel)}", incidentRiskTone(i.riskLevel).playTone())),
        facts = listOf(BpmDetailFact("Reportó", i.reportedByName), BpmDetailFact("Fecha del reporte", displayDateTime(i.createdAt)))) {
        pe.bpm.core.offline.PendingBadge(i.id)
        BpmGuideCard(guide.symbol, guide.tone, guide.title, eyebrow = guide.eyebrow, message = guide.message, steps = guide.steps) {
            workflow.forEachIndexed { index, type ->
                val self = type == IncidentActionType.VERIFY && IncidentsAccess.isSelfVerification(d.actions, s.userId)
                val label = if (self) "Validar sin otro validador" else incidentActionLabel(type)
                if (index == 0) BpmChunkyButton(label, { vm.action(type) }, Modifier.fillMaxWidth(), enabled = !s.loading)
                else BpmChunkyButton(label, { vm.action(type) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !s.loading)
            }
        }
        BpmListenButton(i.name + ". " + i.description, label = "Escuchar el reporte")
        PhotoSection("Fotos del problema", "Cómo estaba antes de corregir", d.beforeCount, beforeWritable) { onEvidence(i.id, null, "BEFORE", beforeWritable) }
        d.actions.forEach { action -> // lista-apilada: acciones del detalle en orden
            val writable =
                IncidentsAccess.canAttend(s.role) &&
                    action.responsibleUserId == s.userId &&
                    i.status == IncidentStatus.IN_PROGRESS &&
                    action.status == "IN_PROGRESS"
            BpmSection("Corrección a cargo de ${action.responsibleName}", description = "Plazo: ${displayDateTime(action.dueAt)}") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BpmStatusChip(incidentStatusLabel(action.status), incidentStatusTone(action.status), symbol = incidentStatusSymbol(action.status))
                    if (action.selfValidated) BpmStatusChip("Autovalidada", BpmTone.Warning)
                }
                Text(action.description)
                action.resolutionNote?.takeIf { it.isNotBlank() }?.let { Text("Qué se hizo: $it") }
                PhotoSection("Fotos de la corrección", "Cómo quedó después", d.afterCount, writable, bare = true) { onEvidence(i.id, action.id, "AFTER", writable) }
            }
        }
        if (d.evidenceRequired)
            BpmInlineNotice("Para cerrar hace falta al menos una foto del problema y una de cómo quedó la corrección.")
        BpmSection("Por qué es un problema") { Text(d.normativeCriterion) }
        BpmTextButton(vm::history, modifier = Modifier.heightIn(min = BpmSpacing.touch)) { Text("Ver qué pasó con este reporte") }
    }
}

/** Fotos de un reporte: cuántas hay y un botón claro para verlas o agregar la primera. */
@Composable
private fun PhotoSection(title: String, hint: String, count: Int, writable: Boolean, bare: Boolean = false, onOpen: () -> Unit) {
    val body: @Composable ColumnScope.() -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BpmIconTile(BpmSymbol.Camera, if (count > 0) BpmPlayTone.Correct else BpmPlayTone.Gold)
            Column(Modifier.weight(1f)) {
                Text(if (count == 0) "Sin fotos todavía" else if (count == 1) "1 foto guardada" else "$count fotos guardadas", style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val label = when { writable && count == 0 -> "Agregar foto"; writable -> "Ver o agregar fotos"; count > 0 -> "Ver fotos"; else -> null }
        if (label != null) BpmChunkyButton(label, onOpen, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, icon = BpmSymbol.Camera)
    }
    if (bare) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = body) else BpmSection(title, content = body)
}

internal fun incidentStatusSymbol(v: IncidentStatus): BpmSymbol = incidentStatusSymbol(v.name)

internal fun incidentStatusSymbol(v: String): BpmSymbol = when (v) {
    "OPEN" -> BpmSymbol.Alert
    "ASSIGNED", "PENDING" -> BpmSymbol.Clock
    "IN_PROGRESS" -> BpmSymbol.Wrench
    "RESOLVED" -> BpmSymbol.Eye
    "CLOSED", "VERIFIED" -> BpmSymbol.Check
    else -> BpmSymbol.Dots
}

internal fun incidentStatusTone(v: String): BpmTone = when (v) {
    "OPEN", "RESOLVED" -> BpmTone.Warning
    "ASSIGNED", "IN_PROGRESS", "PENDING" -> BpmTone.Info
    "CLOSED", "VERIFIED" -> BpmTone.Success
    else -> BpmTone.Neutral
}

internal fun incidentRiskTone(v: IncidentRisk): BpmTone = when (v) {
    IncidentRisk.LOW -> BpmTone.Success
    IncidentRisk.MEDIUM -> BpmTone.Warning
    IncidentRisk.HIGH, IncidentRisk.CRITICAL -> BpmTone.Danger
}

internal fun incidentStatusTone(v: IncidentStatus) =
    when (v) {
        IncidentStatus.OPEN, IncidentStatus.RESOLVED -> BpmTone.Warning
        IncidentStatus.ASSIGNED, IncidentStatus.IN_PROGRESS -> BpmTone.Info
        IncidentStatus.CLOSED, IncidentStatus.VERIFIED -> BpmTone.Success
    }

internal fun incidentActionLabel(v: IncidentActionType) =
    when (v) {
        IncidentActionType.ASSIGN -> "Asignar responsable"
        IncidentActionType.START -> "Empezar a corregir"
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
