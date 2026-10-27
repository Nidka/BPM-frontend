package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*
import pe.bpm.features.checklists.domain.AssignedChecklistRun
import pe.bpm.features.checklists.presentation.*
import pe.bpm.features.workspace.domain.WorkspaceResource
import pe.bpm.features.workspace.presentation.WorkspaceState
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

/** Adapta sedes autorizadas al formulario sin acoplar los módulos entre sí. */
@Composable
internal fun ChecklistAssignmentFormContent(
    userId: String,
    templateId: String?,
    workspace: WorkspaceState,
    state: AssignmentFormState,
    actions: ChecklistAssignmentFormViewModel,
    catalog: ChecklistCatalogState,
    catalogActions: ChecklistCatalogViewModel,
    workspaceActions: WorkspaceViewModel,
    onSaved: () -> Unit,
    onDiscard: () -> Unit,
) {
    val org = workspace.workspace?.organization?.id ?: return
    if (templateId == null) return
    LaunchedEffect(userId, org, templateId) { actions.open(userId, org, templateId) }
    LaunchedEffect(org, templateId, catalog.organizationId) {
        if (catalog.organizationId == org) catalogActions.showTemplate(templateId)
    }
    DisposableEffect(userId, org, templateId) { onDispose { actions.reset() } }
    LaunchedEffect(state.saved?.id) { if (state.saved != null) onSaved() }
    val scoped =
        state.takeIf {
            it.userId == userId && it.organizationId == org && it.input?.templateId == templateId
        } ?: AssignmentFormState(loading = state.loading, localStatus = state.localStatus)
    val page = workspace.pages[WorkspaceResource.Locations]
    if (catalog.detailFailure != null) {
        Text(failureMessage(catalog.detailFailure), color = MaterialTheme.colorScheme.error)
        BpmSecondaryButton(onClick = { catalogActions.showTemplate(templateId) }) {
            Text("Reintentar plantilla")
        }
    }
    ChecklistAssignmentFormScreen(
        scoped,
        catalog.detail?.takeIf { catalog.organizationId == org && it.id == templateId },
        workspace.workspace.locations.map { ChecklistRunLocation(it.id, it.name) },
        page?.request?.query.orEmpty(),
        (page?.previous?.size ?: 0) + 1,
        page?.loading == true,
        page?.errors?.isNotEmpty() == true,
        page?.previous?.isNotEmpty() == true,
        page?.nextToken?.isNotEmpty() == true,
        onSearch = { workspaceActions.pageSearch(WorkspaceResource.Locations, it) },
        onPrevious = { workspaceActions.pagePrevious(WorkspaceResource.Locations) },
        onNext = { workspaceActions.pageNext(WorkspaceResource.Locations) },
        onRetryLocations = { workspaceActions.pageRetry(WorkspaceResource.Locations) },
        onLocation = actions::location,
        onFrequency = actions::frequency,
        onTime = actions::dueTime,
        onSave = actions::save,
        onProtect = actions::protect,
        onRetryOpen = { actions.open(userId, org, templateId) },
        onDiscard = { actions.discard(onDiscard) },
    )
}

/** El inicio es explícito y conserva la identidad de la programación en cualquier reintento. */
@Composable
internal fun ChecklistAssignmentDetailContent(
    userId: String,
    organizationId: String,
    assignmentId: String?,
    management: Boolean,
    state: ChecklistAssignmentsState,
    actions: ChecklistAssignmentsViewModel,
    run: ChecklistRunState,
    runs: ChecklistRunViewModel,
    catalog: ChecklistCatalogState,
    catalogActions: ChecklistCatalogViewModel,
    onOpenRun: (AssignedChecklistRun) -> Unit,
    onStarted: (String) -> Unit,
) {
    var starting by remember(organizationId, assignmentId) { mutableStateOf(false) }
    LaunchedEffect(assignmentId, state.organizationId) {
        if (state.organizationId == organizationId) actions.select(assignmentId)
    }
    val value =
        state.detail?.takeIf { state.organizationId == organizationId && it.id == assignmentId }
    LaunchedEffect(value?.templateId, catalog.organizationId) {
        if (value != null && catalog.organizationId == organizationId)
            catalogActions.showTemplate(value.templateId)
    }
    LaunchedEffect(starting, run.run?.id, run.run?.assignmentId) {
        if (starting && run.run?.assignmentId == assignmentId) run.run?.id?.let(onStarted)
    }
    when {
        state.failure in
            setOf(
                pe.bpm.features.checklists.domain.AssignmentFailure.SESSION,
                pe.bpm.features.checklists.domain.AssignmentFailure.DENIED,
            ) -> {
            Text(assignmentFailureMessage(state.failure!!), color = MaterialTheme.colorScheme.error)
        }
        state.detailLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
        state.detailFailure != null -> {
            Text(
                assignmentFailureMessage(state.detailFailure),
                color = MaterialTheme.colorScheme.error,
            )
            BpmSecondaryButton(onClick = { actions.select(assignmentId) }) { Text("Reintentar") }
        }
        value != null -> {
            val scopedRun =
                run.takeIf { it.userId == userId && it.organizationId == organizationId }
            if (!management && scopedRun != null) {
                if (scopedRun.pending) {
                    Text("Hay una operación de revisión pendiente de confirmar.")
                    BpmSecondaryButton(
                        onClick = {
                            starting = true
                            runs.retry()
                        },
                        enabled = !scopedRun.busy,
                    ) {
                        Text("Comprobar revisión pendiente")
                    }
                }
                scopedRun.failure?.let {
                    Text(runFailureMessage(it), color = MaterialTheme.colorScheme.error)
                }
                if (scopedRun.dirty)
                    Text("Guarda las respuestas de la revisión abierta antes de comenzar otra.")
                scopedRun.resumeId?.let { id ->
                    if (scopedRun.dirty || scopedRun.pending)
                        BpmSecondaryButton(onClick = { onStarted(id) }) {
                            Text("Retomar revisión abierta")
                        }
                }
            }
            val template =
                catalog.detail?.takeIf {
                    catalog.organizationId == organizationId &&
                        it.id == value.templateId &&
                        it.versionId == value.templateVersionId
                }
            ChecklistAssignmentDetailScreen(
                value,
                userId,
                management,
                busy =
                    !management &&
                        (scopedRun == null ||
                            scopedRun.busy ||
                            scopedRun.pending ||
                            scopedRun.dirty ||
                            scopedRun.failure != null ||
                            template == null),
                onStart = {
                    starting = true
                    runs.begin(value.locationId, value.templateVersionId, value.id)
                },
                onOpenRun = onOpenRun,
            )
            if (catalog.detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (catalog.detailFailure != null) {
                Text(failureMessage(catalog.detailFailure), color = MaterialTheme.colorScheme.error)
                BpmSecondaryButton(onClick = { catalogActions.showTemplate(value.templateId) }) {
                    Text("Reintentar preguntas")
                }
            } else if (template != null) ChecklistQuestionPreview(template)
            if (
                scopedRun?.failure in
                    setOf(
                        pe.bpm.features.checklists.domain.RunFailure.ASSIGNMENT_STARTED,
                        pe.bpm.features.checklists.domain.RunFailure.ASSIGNMENT_REQUIRED,
                    )
            )
                BpmSecondaryButton(onClick = { actions.select(assignmentId) }) {
                    Text("Consultar revisión del período")
                }
        }
        else -> Text("Consultando programación…")
    }
}
