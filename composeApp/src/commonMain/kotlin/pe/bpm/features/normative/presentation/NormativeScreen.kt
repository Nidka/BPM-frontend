package pe.bpm.features.normative.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.normative.domain.*

/**
 * Consulta de normativa (RF-46/47) y, para el administrador general, la normativa cargada.
 * patrón: Formulario (consulta en la columna de 720 dp) y Rejilla (documentos), alojada por el shell.
 */
@Composable
fun NormativeScreen(viewModel: NormativeViewModel, userId: String, platformAdmin: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("ask") }
    LaunchedEffect(userId) { viewModel.open(userId) }
    LaunchedEffect(tab) { if (tab == "documents" && platformAdmin) viewModel.loadDocuments() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
        if (platformAdmin) BpmSegmentedTabs(listOf(BpmTabOption("ask", "Consultar"), BpmTabOption("documents", "Normativa cargada", state.status?.documents)), tab, { tab = it })
        if (tab == "documents" && platformAdmin) DocumentsPane(state, viewModel) else AskPane(state, viewModel)
    }
}

@Composable
private fun AskPane(state: NormativeState, viewModel: NormativeViewModel) {
    val status = state.status
    if (state.loading && status == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    when {
        status == null -> state.failure?.let { Failure(it, onRetry = viewModel::refresh) }
        !status.enabled -> EmptyState("Asistente no activado", "El administrador general debe activar el asistente de normativa en el servidor.")
        status.documents == 0 -> EmptyState("Sin normativa cargada", "Cuando el administrador general cargue la normativa, podrás consultarla aquí.")
        else -> {
            BpmFormLayout(actions = {
                BpmFormActions("Consultar", viewModel::ask, enabled = state.question.isNotBlank(), busy = state.asking, busyLabel = "Buscando en la normativa…")
            }) {
                BpmSection("¿Qué quieres saber?",
                    description = "Respondo solo con la normativa cargada (${status.documents} ${if (status.documents == 1) "documento" else "documentos"}) y te muestro de dónde sale cada dato.") {
                    BpmTextField(state.question, viewModel::editQuestion, "Tu pregunta", Modifier.fillMaxWidth(), singleLine = false, minLines = 3,
                        enabled = !state.asking, error = state.fields["question"],
                        placeholder = "Ej.: ¿A qué temperatura se deben conservar los productos refrigerados?",
                        supportingText = "Hasta ${status.questionsPerHour} consultas por hora.")
                }
                state.failure?.let { Failure(it, status.questionsPerHour) }
            }
        }
    }
    state.answers.forEach { AnswerCard(it) } // lista-apilada: conversación en orden
}

@Composable
private fun AnswerCard(answer: NormativeAnswer) {
    BpmCard(Modifier.fillMaxWidth().widthIn(max = BpmPatternRules.readingWidth)) {
        Text(answer.question, style = MaterialTheme.typography.titleMedium)
        if (!answer.supported) BpmStatusChip("Sin respaldo en la normativa cargada", BpmTone.Warning)
        Text(answer.answer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (answer.citations.isNotEmpty()) {
            Text("Fuentes", style = BpmType.bodyStrong)
            answer.citations.forEach { citation -> // lista-apilada: citas en el orden de la respuesta
                Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                    Text(citation.documentTitle, style = BpmType.labelStrong, color = MaterialTheme.colorScheme.primary)
                    Text("«${citation.citedText}»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Respuesta generada con IA a partir de la normativa cargada. Ante dudas, consulta a la autoridad sanitaria.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocumentsPane(state: NormativeState, viewModel: NormativeViewModel) {
    state.notice?.let { BpmInlineNotice(it) }
    state.adminFailure?.let { Failure(it) }
    state.editor?.let { DocumentForm(it, state.saving, viewModel); return }
    state.archiving?.let { document ->
        val archive = document.status == "ACTIVE"
        BpmConfirmPanel(if (archive) "¿Archivar «${document.title}»?" else "¿Reactivar «${document.title}»?",
            if (archive) "Sí, archivar" else "Sí, reactivar", viewModel::confirmArchive, viewModel::cancelArchive, busy = state.saving) {
            Text(if (archive) "Dejará de usarse en las respuestas. No se borra y puedes reactivarlo cuando quieras." else "Volverá a usarse en las respuestas.")
        }
    }
    BpmPageHeader("Normativa cargada", description = "Textos oficiales que el asistente usa como única fuente.",
        actions = { RaisedButton("Cargar normativa", viewModel::newDocument, Modifier.bpmPrimaryButtonWidth()) })
    BpmListToolbar(filters = { BpmFilterChips(listOf(false to "Activa", true to "Archivada"), state.archived, !state.documentsLoading) { viewModel.loadDocuments(it) } })
    BpmPagedList(loading = state.documentsLoading, failure = null, isEmpty = state.documents.isEmpty(), pageNumber = 1, canPrevious = false, canNext = false,
        onRetry = { viewModel.loadDocuments() }, onPrevious = {}, onNext = {},
        empty = { EmptyState(if (state.archived) "Sin normativa archivada" else "Sin normativa activa", if (state.archived) "Los documentos archivados aparecen aquí." else "Carga la primera norma con «Cargar normativa».") }) {
        BpmGridLayout(state.documents) { document, m ->
            BpmRecordCard(document.title, m,
                status = if (document.status == "ACTIVE") BpmRecordStatus("En uso", BpmTone.Success) else BpmRecordStatus("Archivada", BpmTone.Neutral),
                subtitle = document.source.ifBlank { null },
                details = listOf("${document.characters} caracteres"),
                onOpen = { viewModel.editDocument(document) }, actionLabel = "Editar",
                textActions = listOf((if (document.status == "ACTIVE") "Archivar" else "Reactivar") to { viewModel.askArchive(document) }),
                enabled = !state.saving)
        }
    }
}

@Composable
private fun DocumentForm(editor: DocumentEditor, saving: Boolean, viewModel: NormativeViewModel) {
    if (editor.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    BpmFormLayout(actions = { BpmFormActions(if (editor.id == null) "Cargar normativa" else "Guardar cambios", viewModel::saveEditor, busy = saving, onCancel = viewModel::cancelEditor) }) {
        BpmSection(if (editor.id == null) "Nueva normativa" else "Editar normativa", description = "Pega el texto oficial completo. El asistente solo responde con lo que está aquí.") {
            BpmShortFields(
                { m -> BpmTextField(editor.input.title, { v -> viewModel.editInput { it.copy(title = v) } }, "Título *", m, enabled = !saving, error = editor.fields["title"], placeholder = "NTS 142-MINSA/2018/DIGESA") },
                { m -> BpmTextField(editor.input.source, { v -> viewModel.editInput { it.copy(source = v) } }, "Referencia (opcional)", m, enabled = !saving, error = editor.fields["source"], placeholder = "RM 822-2018/MINSA") })
            BpmTextField(editor.input.content, { v -> viewModel.editInput { it.copy(content = v) } }, "Texto de la norma *", Modifier.fillMaxWidth(),
                singleLine = false, minLines = 10, enabled = !saving, error = editor.fields["content"],
                supportingText = "${editor.input.content.length} de ${NormativeRules.CONTENT_MAX} caracteres")
        }
    }
}

@Composable
private fun Failure(reason: NormativeFailure, questionsPerHour: Int = 20, onRetry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        Text(NormativeRules.failureMessage(reason, questionsPerHour), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (onRetry != null) BpmSecondaryButton(onClick = onRetry) { Text("Reintentar") }
    }
}
