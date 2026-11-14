package pe.bpm.app

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.features.checklists.presentation.ChecklistCatalogViewModel
import pe.bpm.features.checklists.presentation.ChecklistVersionsScreen
import pe.bpm.features.checklists.presentation.ChecklistVersionsViewModel

/** Conecta la ruta de versiones con el usuario y la organización activos. */
@Composable
internal fun ChecklistVersionsContent(
    userId: String,
    organizationId: String,
    templateId: String?,
    canEdit: Boolean,
    viewModel: ChecklistVersionsViewModel,
    catalog: ChecklistCatalogViewModel,
) {
    if (templateId == null) return
    LaunchedEffect(userId, organizationId, templateId) { viewModel.open(userId, organizationId, templateId) }
    DisposableEffect(viewModel, userId, organizationId, templateId) { onDispose { viewModel.reset() } }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Una versión nueva cambia el catálogo (versión vigente y preguntas).
    LaunchedEffect(state.published?.versionId) { if (state.published != null) catalog.refresh() }
    val scoped = state.takeIf { it.userId == userId && it.organizationId == organizationId && it.templateId == templateId }
        ?: pe.bpm.features.checklists.presentation.ChecklistVersionsState(loading = true)
    ChecklistVersionsScreen(
        scoped,
        onStartEdit = viewModel::startEdit,
        onView = viewModel::view,
        onCloseView = viewModel::closeViewing,
        onNote = viewModel::changeNote,
        onQuestion = viewModel::question,
        onType = viewModel::type,
        onAdd = viewModel::addQuestion,
        onRemove = viewModel::removeQuestion,
        onMove = viewModel::move,
        onReview = viewModel::review,
        onBackToEdit = viewModel::backToEdit,
        onCancelEdit = viewModel::cancelEdit,
        onPublish = viewModel::confirmPublication,
        onReload = viewModel::reload,
        onNext = viewModel::next,
        onPrevious = viewModel::previousPage,
        canEdit = canEdit,
    )
}
