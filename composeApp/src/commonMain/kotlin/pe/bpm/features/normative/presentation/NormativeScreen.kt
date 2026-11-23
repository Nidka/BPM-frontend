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
 * Buscador de normativa (RF-46/47) y, para el administrador general, la normativa cargada.
 * patrón: Formulario (búsqueda y resultados en la columna de 720 dp) y Rejilla (documentos), alojada por el shell.
 */
@Composable
fun NormativeScreen(viewModel: NormativeViewModel, userId: String, platformAdmin: Boolean, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("search") }
    LaunchedEffect(userId) { viewModel.open(userId) }
    LaunchedEffect(tab) { if (tab == "documents" && platformAdmin) viewModel.loadDocuments() }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
        if (platformAdmin) BpmSegmentedTabs(listOf(BpmTabOption("search", "Buscar"), BpmTabOption("documents", "Normativa cargada", state.status?.documents)), tab, { tab = it })
        if (tab == "documents" && platformAdmin) DocumentsPane(state, viewModel)
        else NormativeSearchContent(state.status, state.loading, state.query, state.searching, state.searchedQuery, state.results, state.failure,
            state.fields["q"], viewModel::editQuery, viewModel::search, viewModel::refresh)
    }
}

/**
 * Búsqueda en la normativa, sin estado propio: campo de búsqueda compartido ([BpmSearchField], 560 dp como máximo
 * en pantallas anchas y todo el ancho en compacta) y párrafos encontrados en la columna de lectura.
 */
@Composable
fun NormativeSearchContent(
    status: NormativeStatus?, loading: Boolean, query: String, searching: Boolean, searchedQuery: String?,
    results: List<NormativeMatch>, failure: NormativeFailure?, fieldError: String?,
    onQuery: (String) -> Unit, onSearch: () -> Unit, onRetry: () -> Unit,
) {
    if (loading && status == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    when {
        status == null -> failure?.let { Failure(it, onRetry = onRetry) }
        !status.available -> EmptyState("Buscador aún no disponible", "El buscador de normativa estará disponible cuando se actualice el servidor.")
        status.documents == 0 -> EmptyState("Sin normativa cargada", "Cuando el administrador general cargue la normativa, podrás buscar en ella aquí.")
        else -> Column(Modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
            Text("Busca en ${status.documents} ${if (status.documents == 1) "norma cargada" else "normas cargadas"}: escribe palabras como «temperatura pollo» o «lavado de manos».",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BpmSearchField(query, onQuery, "Buscar en la normativa", loading = searching, error = fieldError,
                placeholder = "Ej.: temperatura de refrigeración", supportingText = "Los resultados aparecen mientras escribes.")
            failure?.let { Failure(it, onRetry = onSearch) }
            if (searchedQuery != null && !searching) {
                Text(if (results.isEmpty()) "Sin resultados para «$searchedQuery»." else "${results.size} ${if (results.size == 1) "resultado" else "resultados"} para «$searchedQuery»",
                    style = BpmType.bodyStrong, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (results.isEmpty()) Text("Prueba con otras palabras o con menos palabras.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val terms = NormativeRules.terms(searchedQuery)
                results.forEach { MatchCard(it, terms) } // lista-apilada: resultados por pertinencia
            }
        }
    }
}

@Composable
private fun MatchCard(match: NormativeMatch, terms: List<String>) {
    val strong = BpmType.bodyMediumStrong.toSpanStyle().copy(background = MaterialTheme.colorScheme.primaryContainer)
    val text = remember(match.text, terms) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(match.text)
            NormativeRules.highlights(match.text, terms).forEach { addStyle(strong, it.first, it.last + 1) }
        }
    }
    BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
            Text(match.documentTitle, style = BpmType.labelStrong, color = MaterialTheme.colorScheme.primary)
            if (match.documentSource.isNotBlank()) Text(match.documentSource, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
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
            Text(if (archive) "Dejará de aparecer en las búsquedas. No se borra y puedes reactivarlo cuando quieras." else "Volverá a aparecer en las búsquedas.")
        }
    }
    BpmPageHeader("Normativa cargada", description = "Textos oficiales en los que busca el equipo.",
        actions = { BpmChunkyButton("Cargar normativa", viewModel::newDocument, Modifier.bpmPrimaryButtonWidth()) })
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
        BpmSection(if (editor.id == null) "Nueva normativa" else "Editar normativa", description = "Pega el texto oficial completo. Se divide en párrafos para buscar en ellos.") {
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
private fun Failure(reason: NormativeFailure, onRetry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        Text(NormativeRules.failureMessage(reason), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (onRetry != null) BpmChunkyButton("Reintentar", onRetry, tone = BpmChunkyTone.White)
    }
}
