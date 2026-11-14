package pe.bpm.features.research.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.FileSaveResult
import pe.bpm.core.presentation.rememberTextFileSaver
import pe.bpm.features.research.domain.*

private fun tabLabel(tab: ResearchTab) = when (tab) {
    ResearchTab.CONSENT -> "Consentimiento"
    ResearchTab.INSTRUMENTS -> "Cuestionarios"
    ResearchTab.RESULTS -> "Resultados"
    ResearchTab.NEEDS -> "Necesidades"
    ResearchTab.ACCESS -> "Accesos"
}

/** Sección de investigación: consentimiento, cuestionarios, resultados, necesidades y concesiones. */
@Composable
fun ResearchScreen(viewModel: ResearchViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().widthIn(max = 960.dp).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.accessLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            Text(researchFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmSecondaryButton(onClick = viewModel::retry) { Text("Volver a intentar") }
        }
        if (state.access != null && !state.hasAccess) {
            EmptyState("Sin acceso", "Tu cuenta no tiene una concesión de investigación. Solicítala a un administrador general de BPM.")
            return@Column
        }
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.tabs.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.tabs.forEach { tab -> FilterChip(state.tab == tab, { viewModel.selectTab(tab) }, { Text(tabLabel(tab)) }) }
        }
        when (state.tab) {
            ResearchTab.CONSENT -> ConsentTab(state, viewModel)
            ResearchTab.INSTRUMENTS -> InstrumentsTab(viewModel.instruments)
            ResearchTab.RESULTS -> ResultsTab(viewModel.analysis)
            ResearchTab.NEEDS -> NeedsTab(viewModel.analysis)
            ResearchTab.ACCESS -> AccessTab(state, viewModel)
            null -> Unit
        }
    }
}

@Composable
private fun ErrorLine(failure: ResearchFailure?) {
    failure?.let { Text(researchFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

// ---- Consentimiento ---------------------------------------------------------------------------

@Composable
private fun ConsentTab(state: ResearchState, viewModel: ResearchViewModel) {
    Text("Consentimiento informado", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    val form = state.documentForm
    if (form != null) {
        Text("Al publicar una versión nueva, la vigente se archiva y todas las personas participantes deberán aceptarla de nuevo.")
        BpmTextField(form.version, { v -> viewModel.editDocument { it.copy(version = v.take(ResearchLimits.VERSION_MAX)) } }, "Versión (por ejemplo v2)", Modifier.fillMaxWidth(),
            error = if (form.showIssues && !ResearchLimits.versionPattern.matches(form.version.trim())) "Usa letras, números, punto, guion o guion bajo." else null, enabled = !state.documentBusy)
        BpmTextField(form.title, { v -> viewModel.editDocument { it.copy(title = v.take(ResearchLimits.TITLE_MAX)) } }, "Título", Modifier.fillMaxWidth(),
            error = if (form.showIssues && form.title.isBlank()) "Escribe un título." else null, enabled = !state.documentBusy)
        BpmTextField(form.investigator, { v -> viewModel.editDocument { it.copy(investigator = v.take(ResearchLimits.INVESTIGATOR_MAX)) } }, "Investigador responsable", Modifier.fillMaxWidth(),
            error = if (form.showIssues && form.investigator.isBlank()) "Indica quién es la persona responsable." else null, enabled = !state.documentBusy)
        BpmTextField(form.text, { v -> viewModel.editDocument { it.copy(text = v.take(ResearchLimits.CONSENT_TEXT_MAX)) } }, "Texto completo del consentimiento", Modifier.fillMaxWidth(),
            singleLine = false, minLines = 8, error = if (form.showIssues && form.text.isBlank()) "Escribe el texto del consentimiento." else null, enabled = !state.documentBusy,
            supportingText = "${form.text.length} de ${ResearchLimits.CONSENT_TEXT_MAX} caracteres")
        ErrorLine(state.documentFailure)
        if (state.documentBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaisedButton("Publicar versión", viewModel::publishDocument, Modifier.widthIn(min = 220.dp), enabled = !state.documentBusy)
            BpmSecondaryButton(onClick = viewModel::cancelDocument, enabled = !state.documentBusy) { Text("Cancelar") }
        }
        return
    }
    RaisedButton("Publicar nueva versión", viewModel::startDocument, Modifier.widthIn(max = 360.dp).fillMaxWidth())
    if (state.documentsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorLine(state.documentsFailure)
    if (state.documentsFailure != null) BpmSecondaryButton(onClick = { viewModel.loadDocuments(force = true) }) { Text("Volver a intentar") }
    if (state.documentsLoaded && state.documents.isEmpty()) EmptyState("Sin documentos", "Todavía no se publicó ningún consentimiento; nadie puede participar hasta que exista uno.")
    state.documents.forEach { document ->
        var expanded by remember(document.version) { mutableStateOf(false) }
        BpmCard(Modifier.fillMaxWidth(), tint = if (document.status == ConsentStatus.ACTIVE) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
            Eyebrow(if (document.status == ConsentStatus.ACTIVE) "VIGENTE" else "ARCHIVADO")
            Text("${document.title} · ${document.version}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Investigador responsable: ${document.responsibleInvestigator} · publicado el ${document.publishedAt.take(10)}", style = MaterialTheme.typography.bodySmall)
            if (expanded) Text(document.text)
            BpmTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar texto" else "Ver texto completo") }
        }
    }
}

// ---- Cuestionarios ----------------------------------------------------------------------------

private fun typeLabel(type: QuestionType) = when (type) {
    QuestionType.MULTIPLE_CHOICE -> "Opción múltiple (conocimientos)"
    QuestionType.LIKERT -> "Escala de acuerdo (actitudes)"
    QuestionType.FREQUENCY -> "Escala de frecuencia (prácticas)"
}

@Composable
private fun InstrumentsTab(controller: InstrumentsController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val editor = state.editor
    if (editor != null) { DraftEditor(editor, controller); return }
    Text("Cuestionarios", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    val form = state.import
    if (form != null) {
        Text("Pega el contenido completo del archivo JSON del cuestionario. Se creará un borrador: después elegirás las respuestas correctas de las preguntas de opción múltiple y lo publicarás.")
        BpmTextField(form.text, controller::editImport, "Contenido del archivo JSON", Modifier.fillMaxWidth(), singleLine = false, minLines = 8,
            error = form.error, enabled = !form.busy)
        ErrorLine(form.failure)
        if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaisedButton("Importar como borrador", controller::confirmImport, Modifier.widthIn(min = 240.dp), enabled = !form.busy && form.text.isNotBlank())
            BpmSecondaryButton(onClick = controller::cancelImport, enabled = !form.busy) { Text("Cancelar") }
        }
        return
    }
    RaisedButton("Importar cuestionario (JSON)", controller::startImport, Modifier.widthIn(max = 360.dp).fillMaxWidth())
    if (state.loading || state.openingId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorLine(state.failure)
    if (state.failure != null && state.items.isEmpty()) BpmSecondaryButton(onClick = { controller.load(force = true) }) { Text("Volver a intentar") }
    if (state.loaded && state.items.isEmpty()) EmptyState("Sin cuestionarios", "Importa el cuestionario CAP para comenzar.")
    BpmCardGrid(state.items, minCardWidth = 380.dp, maxColumns = 2) { item, cardModifier ->
        BpmCard(cardModifier) {
            Eyebrow(item.code + if (item.isCap) " · CAP" else "")
            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(item.publishedVersionNumber?.let { "Versión publicada: $it" } ?: "Sin versión publicada", style = MaterialTheme.typography.bodySmall)
            if (item.hasDraft) BpmSecondaryButton(onClick = { controller.openDraft(item.id) }, enabled = state.openingId == null, modifier = Modifier.fillMaxWidth()) { Text("Editar borrador") }
            else if (item.publishedVersionNumber != null) BpmSecondaryButton(onClick = { controller.newVersion(item.id) }, enabled = state.openingId == null, modifier = Modifier.fillMaxWidth()) { Text("Crear nueva versión") }
        }
    }
}

@Composable
private fun DraftEditor(editor: DraftEditorState, controller: InstrumentsController) {
    Text("${editor.draft.name} · borrador de la versión ${editor.draft.versionNumber}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Elige la respuesta correcta de cada pregunta de opción múltiple. Las respuestas correctas solo las ves tú: las personas participantes nunca las reciben." +
        if (editor.draft.isCap) " El cuestionario CAP necesita exactamente ${ResearchLimits.CAP_KNOWLEDGE} preguntas de opción múltiple, ${ResearchLimits.CAP_ATTITUDE} de actitud y ${ResearchLimits.CAP_PRACTICE} de frecuencia." else "")
    if (editor.discarding) {
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.errorContainer) {
            Text("Tienes cambios sin guardar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            BpmSecondaryButton(onClick = controller::discardEditor) { Text("Descartar cambios y salir") }
            BpmTextButton(onClick = controller::keepEditing) { Text("Seguir editando") }
        }
    }
    editor.questions.forEachIndexed { index, question -> QuestionEditor(index, question, editor.questions.size, !editor.busy, controller) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuestionType.entries.forEach { type ->
            BpmSecondaryButton(onClick = { controller.addQuestion(type) }, enabled = !editor.busy && editor.questions.size < ResearchLimits.MAX_QUESTIONS) { Text("Agregar: " + typeLabel(type)) }
        }
    }
    if (editor.showIssues && editor.problems.isNotEmpty()) BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.errorContainer) {
        Text("Antes de publicar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        editor.problems.forEach { Text("• $it") }
    }
    ErrorLine(editor.failure)
    if (editor.failure == ResearchFailure.DRAFT_CONFLICT) BpmSecondaryButton(onClick = controller::reloadDraft) { Text("Recargar borrador del servidor") }
    if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmSecondaryButton(onClick = controller::save, enabled = editor.dirty && !editor.busy) { Text(if (editor.dirty) "Guardar borrador" else "Borrador guardado") }
        RaisedButton("Publicar versión", controller::publish, Modifier.widthIn(min = 220.dp), enabled = !editor.busy)
        BpmTextButton(onClick = { controller.closeEditor() }, enabled = !editor.busy) { Text("Volver a la lista") }
    }
}

@Composable
private fun QuestionEditor(index: Int, question: DraftQuestion, total: Int, enabled: Boolean, controller: InstrumentsController) {
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("PREGUNTA ${index + 1} · ${typeLabel(question.type).uppercase()}")
        BpmTextField(question.text, { controller.setText(index, it) }, "Texto de la pregunta", Modifier.fillMaxWidth(), singleLine = false, minLines = 2, enabled = enabled)
        if (question.type == QuestionType.MULTIPLE_CHOICE) {
            question.options.forEachIndexed { option, text ->
                BpmTextField(text, { controller.setOption(index, option, it) }, "Opción ${option + 1}", Modifier.fillMaxWidth(), enabled = enabled)
                BpmChoiceRow("Es la respuesta correcta", question.correctOption == option, enabled = enabled) { controller.setCorrect(index, option) }
                if (question.options.size > 2) BpmTextButton(onClick = { controller.removeOption(index, option) }, enabled = enabled) { Text("Quitar la opción ${option + 1}") }
            }
            if (question.options.size < 6) BpmSecondaryButton(onClick = { controller.addOption(index) }, enabled = enabled) { Text("Agregar opción") }
        } else {
            Text("Escala de 5 niveles con las etiquetas estándar del estudio.", style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BpmTextButton(onClick = { controller.move(index, -1) }, enabled = enabled && index > 0) { Text("Subir") }
            BpmTextButton(onClick = { controller.move(index, 1) }, enabled = enabled && index < total - 1) { Text("Bajar") }
            BpmTextButton(onClick = { controller.removeQuestion(index) }, enabled = enabled) { Text("Quitar pregunta") }
        }
    }
}

// ---- Resultados -------------------------------------------------------------------------------

@Composable
private fun ResultsTab(controller: AnalysisController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val saver = rememberTextFileSaver()
    // La plataforma recibe el archivo una sola vez; después los bytes se descartan.
    LaunchedEffect(state.pendingExport) { state.pendingExport?.let { export -> saver.save(export.fileName, "text/csv", export.bytes.decodeToString()) { result -> controller.exportDelivered(saved = result == FileSaveResult.SAVED, failed = result == FileSaveResult.FAILED) } } }
    LaunchedEffect(Unit) { controller.loadOrganizations() }
    Text("Resultados por empresa", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Comparación entre la encuesta inicial y la final. Los conocimientos van de 0 a 100; actitudes y prácticas, de 1 a 5. Las empresas con pocas personas participantes pueden dar promedios poco estables.")
    OrganizationFilter(state, controller, label = "Exportar respuestas de")
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    ErrorLine(state.exportFailure)
    RaisedButton(if (state.exporting) "Preparando archivo…" else "Descargar CSV anonimizado", controller::export, Modifier.widthIn(max = 360.dp).fillMaxWidth(), enabled = !state.exporting && state.pendingExport == null)
    Text("El archivo no incluye correos, nombres ni identificadores de usuario; las empresas se identifican con un código opaco.", style = MaterialTheme.typography.bodySmall)
    if (state.resultsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorLine(state.resultsFailure)
    if (state.resultsFailure != null) BpmSecondaryButton(onClick = controller::refreshResults) { Text("Volver a intentar") }
    val results = state.results ?: return
    Text("Versión ${results.version.versionNumber} del cuestionario", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (results.page.items.isEmpty()) EmptyState("Sin resultados", "Todavía no hay empresas con personas participantes.")
    results.page.items.forEach { ResultCard(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmSecondaryButton(onClick = controller::previousResults, enabled = state.hasPreviousResults && !state.resultsLoading) { Text("Página anterior") }
        BpmSecondaryButton(onClick = controller::nextResults, enabled = results.page.nextToken.isNotEmpty() && !state.resultsLoading) { Text("Página siguiente") }
    }
}

private fun mean(value: Double?) = value?.let { formatNumber(it) } ?: "—"

/** Dos decimales sin depender de formato regional. */
internal fun formatNumber(value: Double): String {
    val scaled = kotlin.math.round(value * 100).toLong()
    val sign = if (scaled < 0) "-" else ""
    val absolute = kotlin.math.abs(scaled)
    return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
}

@Composable
private fun ResultCard(result: OrganizationResult) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text(result.organizationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("${result.participants} participantes · ${result.pretest.n} encuestas iniciales · ${result.posttest.n} finales · ${result.paired.n} personas con ambas", style = MaterialTheme.typography.bodySmall)
        ComparisonBars("Conocimientos (0 a 100)", result.pretest.knowledgeMean, result.posttest.knowledgeMean, 100.0)
        ComparisonBars("Actitudes (1 a 5)", result.pretest.attitudeMean, result.posttest.attitudeMean, 5.0)
        ComparisonBars("Prácticas (1 a 5)", result.pretest.practiceMean, result.posttest.practiceMean, 5.0)
        if (result.paired.n > 0) Text("Cambio promedio (final menos inicial) en quienes respondieron ambas: conocimientos ${signed(result.paired.knowledgeDelta)}, actitudes ${signed(result.paired.attitudeDelta)}, prácticas ${signed(result.paired.practiceDelta)}.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun signed(value: Double?) = value?.let { (if (it > 0) "+" else "") + formatNumber(it) } ?: "—"

/** Barras horizontales de inicial vs final; el valor numérico siempre se muestra, no depende del color. */
@Composable
private fun ComparisonBars(label: String, pre: Double?, post: Double?, max: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Bar("Inicial", pre, max, MaterialTheme.colorScheme.secondary)
        Bar("Final", post, max, MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Bar(name: String, value: Double?, max: Double, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
            val fraction = ((value ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color, MaterialTheme.shapes.small))
        }
        Text(mean(value), Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OrganizationFilter(state: AnalysisState, controller: AnalysisController, label: String) {
    var open by remember { mutableStateOf(false) }
    val selected = state.organizations.firstOrNull { it.id == state.organizationId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            BpmSecondaryButton(onClick = { open = true }) { Text(selected?.name ?: "Todas las empresas") }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("Todas las empresas") }, onClick = { controller.selectOrganization(null); open = false })
                state.organizations.forEach { org -> DropdownMenuItem(text = { Text(org.name) }, onClick = { controller.selectOrganization(org.id); open = false }) }
            }
        }
        state.organizationsFailure?.let { Text(researchFailureMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

// ---- Necesidades ------------------------------------------------------------------------------

private fun categoryLabel(category: NeedCategory) = when (category) {
    NeedCategory.TRAINING -> "Capacitación"
    NeedCategory.PROCESS -> "Procesos"
    NeedCategory.INFRASTRUCTURE -> "Infraestructura"
    NeedCategory.DOCUMENTATION -> "Documentación"
    NeedCategory.TECHNOLOGY -> "Tecnología"
    NeedCategory.SUPPLIERS -> "Proveedores"
    NeedCategory.OTHER -> "Otra"
}

@Composable
private fun NeedsTab(controller: AnalysisController) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { controller.loadOrganizations() }
    Text("Necesidades detectadas", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    val form = state.needForm
    if (form != null) {
        Text("Registra una necesidad observada en una empresa durante el estudio.")
        Text("Empresa", style = MaterialTheme.typography.labelLarge)
        var open by remember { mutableStateOf(false) }
        Box {
            BpmSecondaryButton(onClick = { open = true }, enabled = !form.busy) { Text(state.organizations.firstOrNull { it.id == form.organizationId }?.name ?: "Elegir empresa") }
            DropdownMenu(open, { open = false }) {
                state.organizations.forEach { org -> DropdownMenuItem(text = { Text(org.name) }, onClick = { controller.editNeed { it.copy(organizationId = org.id) }; open = false }) }
            }
        }
        if (form.showIssues && form.organizationId == null) Text("Elige una empresa.", color = MaterialTheme.colorScheme.error)
        Text("Categoría", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NeedCategory.entries.forEach { category -> FilterChip(form.category == category, { controller.editNeed { it.copy(category = category) } }, { Text(categoryLabel(category)) }, enabled = !form.busy) }
        }
        BpmTextField(form.description, { v -> controller.editNeed { it.copy(description = v.take(ResearchLimits.NEED_DESCRIPTION_MAX)) } }, "Descripción", Modifier.fillMaxWidth(),
            singleLine = false, minLines = 4, enabled = !form.busy, error = if (form.showIssues && form.description.isBlank()) "Describe la necesidad." else null,
            supportingText = "${form.description.length} de ${ResearchLimits.NEED_DESCRIPTION_MAX} caracteres")
        ErrorLine(form.failure)
        if (form.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaisedButton("Registrar necesidad", controller::saveNeed, Modifier.widthIn(min = 220.dp), enabled = !form.busy)
            BpmSecondaryButton(onClick = controller::cancelNeed, enabled = !form.busy) { Text("Cancelar") }
        }
        return
    }
    OrganizationFilter(state, controller, label = "Filtrar por empresa")
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    RaisedButton("Registrar necesidad", controller::startNeed, Modifier.widthIn(max = 360.dp).fillMaxWidth())
    if (state.needsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorLine(state.needsFailure)
    if (state.needsFailure != null) BpmSecondaryButton(onClick = controller::refreshNeeds) { Text("Volver a intentar") }
    val page = state.needs ?: return
    if (page.items.isEmpty()) EmptyState("Sin necesidades", "No hay necesidades registradas para este filtro.")
    page.items.forEach { need ->
        BpmCard(Modifier.fillMaxWidth()) {
            Eyebrow(categoryLabel(need.category).uppercase())
            Text(need.organizationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(need.description)
            Text("Detectada el ${need.detectedAt.take(10)}" + if (need.investigatorName.isNotBlank()) " · ${need.investigatorName}" else "", style = MaterialTheme.typography.bodySmall)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmSecondaryButton(onClick = controller::previousNeeds, enabled = state.hasPreviousNeeds && !state.needsLoading) { Text("Página anterior") }
        BpmSecondaryButton(onClick = controller::nextNeeds, enabled = page.nextToken.isNotEmpty() && !state.needsLoading) { Text("Página siguiente") }
    }
}

// ---- Accesos (SUPER_ADMIN) --------------------------------------------------------------------

@Composable
private fun AccessTab(state: ResearchState, viewModel: ResearchViewModel) {
    Text("Accesos de investigación", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Solo las personas con una concesión pueden ver resultados y exportaciones. Ni los administradores de empresa ni los administradores generales ven estos datos sin ella.")
    BpmTextField(state.grantEmail, viewModel::editGrantEmail, "Correo de la persona", Modifier.fillMaxWidth(), enabled = !state.grantBusy,
        error = if (state.grantEmailInvalid) "Escribe un correo válido." else null, inputKind = BpmInputKind.Email)
    RaisedButton("Conceder acceso", viewModel::grant, Modifier.widthIn(max = 360.dp).fillMaxWidth(), enabled = !state.grantBusy && state.grantEmail.isNotBlank())
    if (state.grantBusy || state.grantsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
    ErrorLine(state.grantsFailure)
    if (state.grantsLoaded && state.grants.isEmpty()) EmptyState("Sin concesiones", "Todavía no se concedió acceso a nadie.")
    state.grants.forEach { grant ->
        BpmCard(Modifier.fillMaxWidth()) {
            Eyebrow(if (grant.active) "ACTIVO" else "RETIRADO")
            Text(grant.displayName.ifBlank { grant.email }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (grant.displayName.isNotBlank()) Text(grant.email)
            Text("Concedido el ${grant.grantedAt.take(10)}" + (grant.revokedAt?.let { " · retirado el ${it.take(10)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
            if (grant.active) {
                if (state.revokingUserId == grant.userId) {
                    Text("¿Retirar el acceso de ${grant.email}?")
                    BpmSecondaryButton(onClick = viewModel::confirmRevoke, enabled = !state.grantBusy) { Text("Sí, retirar acceso") }
                    BpmTextButton(onClick = viewModel::cancelRevoke) { Text("Cancelar") }
                } else BpmSecondaryButton(onClick = { viewModel.askRevoke(grant.userId) }, enabled = !state.grantBusy) { Text("Retirar acceso") }
            }
        }
    }
}
