package pe.bpm.app

import androidx.compose.runtime.*
import pe.bpm.features.checklists.presentation.*
import pe.bpm.features.workspace.domain.WorkspaceResource
import pe.bpm.features.workspace.presentation.WorkspaceState
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

/** La raíz adapta la sede activa al módulo; ninguna funcionalidad depende de la otra. */
@Composable internal fun ChecklistRunContent(userId: String, workspace: WorkspaceState,
    catalog: ChecklistCatalogState, run: ChecklistRunState, runs: ChecklistRunViewModel,
    workspaceActions: WorkspaceViewModel, catalogActions: ChecklistCatalogViewModel,
    starting: Boolean, templateId: String?, runId: String?, onStarted: (String) -> Unit, onBack: () -> Unit) {
    val org = workspace.workspace?.organization?.id ?: return
    LaunchedEffect(userId, org, runId) { runs.open(userId, org, runId) }
    LaunchedEffect(starting, templateId, org) { if (starting) catalogActions.showTemplate(templateId) }
    LaunchedEffect(starting, run.run?.id) { if (starting || run.run?.id != runId) run.run?.id?.let(onStarted) }
    val scoped = run.takeIf { it.userId == userId && it.organizationId == org && (runId == null || it.run == null || it.run.id == runId) } ?: ChecklistRunState(busy = true)
    val page = workspace.pages[WorkspaceResource.Locations]
    ChecklistRunScreen(scoped, catalog.detail.takeIf { catalog.organizationId == org }, catalog.detailLoading,
        catalog.detailFailure?.let { "No pudimos consultar esta plantilla. Regresa al catálogo y vuelve a intentarlo." },
        workspace.workspace.locations.map { ChecklistRunLocation(it.id, it.name) },
        page?.request?.query.orEmpty(), (page?.previous?.size ?: 0) + 1,
        page?.loading == true, page?.errors?.isNotEmpty() == true, page?.previous?.isNotEmpty() == true, page?.nextToken?.isNotEmpty() == true,
        onLocationSearch = { workspaceActions.pageSearch(WorkspaceResource.Locations, it) },
        onLocationPrevious = { workspaceActions.pagePrevious(WorkspaceResource.Locations) },
        onLocationNext = { workspaceActions.pageNext(WorkspaceResource.Locations) },
        onLocationRetry = { workspaceActions.pageRetry(WorkspaceResource.Locations) },
        onBegin = {site,version->runs.begin(site,version)}, onEdit = runs::edit, onSave = runs::save, onReview = runs::review,
        onProtectDraft = { if (scoped.pending) runs.retry() else if (scoped.run == null) runs.reload() else runs.protectDraft() },
        onSubmit = runs::confirmSubmit, onBackToEdit = runs::backToEdit, onRetry = runs::retry,
        onReload = { runs.reload() }, onBack = onBack)
}
