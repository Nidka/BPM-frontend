package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.checklists.domain.*

data class ChecklistRunLocation(val id: String, val name: String)

/**
 * Controles programados y vigentes de la persona, para avisar ANTES de «Comenzar revisión» que esta plantilla ya está programada en la sede
 * elegida (el servidor rechaza una revisión libre en ese caso): en vez del error, se ofrece abrir el control de hoy. [onOpen] lo abre.
 */
class ScheduledControls(val items: List<ChecklistAssignment>, val onOpen: (ChecklistAssignment) -> Unit)

val LocalScheduledControls = compositionLocalOf<ScheduledControls?> { null }

/** El control programado y activo de [templateId] en [locationId] que aún no tiene revisión, si existe. */
fun scheduledFor(items: List<ChecklistAssignment>, templateId: String, locationId: String): ChecklistAssignment? =
    items.firstOrNull { it.active && it.templateId == templateId && it.locationId == locationId && it.run?.status != "SUBMITTED" }

/** Vista de ejecución: emite acciones, no accede a red ni determina la autorización. */
@Composable fun ChecklistRunScreen(state: ChecklistRunState, template: ChecklistTemplate?, loadingTemplate: Boolean,
    templateError: String?, locations: List<ChecklistRunLocation>, locationQuery: String, locationPage: Int,
    locationsLoading: Boolean, locationsFailed: Boolean, previousLocations: Boolean, nextLocations: Boolean,
    onLocationSearch: (String) -> Unit, onLocationPrevious: () -> Unit, onLocationNext: () -> Unit, onLocationRetry: () -> Unit,
    onBegin: (String, String) -> Unit, onEdit: (String, RunAnswerInput) -> Unit,
    onSave: () -> Unit, onReview: () -> Unit, onSubmit: () -> Unit, onBackToEdit: () -> Unit,
    onRetry: () -> Unit, onProtectDraft: () -> Unit, onReload: () -> Unit, onBack: () -> Unit, evidence: @Composable (ChecklistItem) -> Unit = {}) {
    var location by remember(state.organizationId, template?.id) { mutableStateOf<String?>(null) }
    var locationName by remember(state.organizationId, template?.id) { mutableStateOf("") }
    var confirmReload by remember(state.run?.id, state.failure) { mutableStateOf(false) }
    Column(Modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.dirty || state.pending || state.localStatus == pe.bpm.core.presentation.DraftSaveStatus.FAILED)
            DraftSaveNotice(state.localStatus, onProtectDraft)
        if (state.pending && state.failure == null) BpmChunkyButton("Comprobar operación pendiente", onRetry, tone = BpmChunkyTone.White, enabled = !state.busy)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure -> BpmCard(Modifier.fillMaxWidth()) {
            Text(runFailureMessage(failure), color = MaterialTheme.colorScheme.error)
            if(failure in setOf(RunFailure.ASSIGNMENT_REQUIRED,RunFailure.ASSIGNMENT_STARTED)) BpmChunkyButton("Ver controles programados", onBack, tone = BpmChunkyTone.White)
            if (state.pending) {
                Text("No pudimos confirmar esta operación. Reintenta para comprobar el resultado antes de continuar.")
                BpmChunkyButton("Reintentar operación", onRetry, tone = BpmChunkyTone.White, enabled = !state.busy)
            } else if (failure == RunFailure.CONFLICT) {
                Text("Tus cambios no se sobrescriben automáticamente. Cargar la versión guardada descartará las respuestas locales sin guardar.")
                if (confirmReload) {
                    BpmChunkyButton("Confirmar carga de versión guardada", { confirmReload = false; onReload() }, tone = BpmChunkyTone.White)
                    BpmTextButton(onClick = { confirmReload = false }) { Text("Conservar mis respuestas") }
                } else BpmChunkyButton("Revisar versión guardada", { confirmReload = true }, tone = BpmChunkyTone.White)
            } else if (state.run == null && failure !in setOf(RunFailure.DENIED, RunFailure.SESSION, RunFailure.NOT_FOUND))
                BpmChunkyButton("Reintentar consulta", onReload, tone = BpmChunkyTone.White, enabled = !state.busy)
        } }
        val run = state.run
        if (run == null) {
            if (state.failure in setOf(RunFailure.DENIED, RunFailure.SESSION, RunFailure.NOT_FOUND)) return@Column
            if (loadingTemplate) BpmSkeletonDetail()
            if (templateError != null) { Text(templateError); return@Column }
            if (template == null) return@Column
            BpmCard(Modifier.fillMaxWidth()) {
                Text(template.name, style = MaterialTheme.typography.titleLarge)
                Text("Versión ${template.versionNumber} · ${template.items.size} preguntas")
                Text("Aquí registras lo que observas en tu local. Elige la sede que vas a revisar; cada inicio crea una nueva revisión con tu nombre y la fecha.")
                ChecklistLocationSelector(locations,location,locationName,locationQuery,locationPage,locationsLoading,locationsFailed,
                    previousLocations,nextLocations,!state.busy&&!state.pending,onLocationSearch,onLocationPrevious,onLocationNext,onLocationRetry){location=it.id;locationName=it.name}
                val scheduledControls = LocalScheduledControls.current
                val scheduled = location?.let { scheduledControls?.let { s -> scheduledFor(s.items, template.id, it) } }
                if (scheduled != null) {
                    Text("Este control ya está programado para esa sede. Ábrelo desde aquí para hacerlo: así queda ligado a lo que tu administrador pidió.", color = MaterialTheme.colorScheme.primary)
                    BpmChunkyButton("Abrir el control programado", { scheduledControls?.onOpen?.invoke(scheduled) }, Modifier.bpmPrimaryButtonWidth())
                }
                BpmChunkyButton("Comenzar revisión", { location?.let { onBegin(it, template.versionId) } }, Modifier.bpmPrimaryButtonWidth(), tone = if (scheduled != null) BpmChunkyTone.White else BpmChunkyTone.Action, enabled = scheduled == null && location != null && !state.busy && !state.pending && !locationsLoading && !locationsFailed && state.localStatus != pe.bpm.core.presentation.DraftSaveStatus.FAILED)
            }
            return@Column
        }
        BpmCard(Modifier.fillMaxWidth()) {
            Text(run.templateName.ifBlank { template?.name ?: "Revisión BPM" }, style = MaterialTheme.typography.titleLarge)
            Text(run.locationName.ifBlank { locations.firstOrNull { it.id == run.locationId }?.name.orEmpty() })
            if (run.createdAt.isNotBlank()) Text("Inicio: ${pe.bpm.core.presentation.displayDateTime(run.createdAt)}", style = MaterialTheme.typography.bodySmall)
        }
        if (run.status == "SUBMITTED" && !state.dirty) {
            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Mascot(Modifier.size(96.dp))
                Text("Control finalizado", style = MaterialTheme.typography.titleLarge)
                Text("Cumplimiento: ${run.compliancePercent?.toString()?.removeSuffix(".0")?.replace('.', ',')} %")
                Text("Las respuestas enviadas ya no se pueden editar.")
                BpmChunkyButton("Ver mis revisiones", onBack, tone = BpmChunkyTone.White)
            }
        } else {
            Text(if (state.reviewing) "Revisa antes de finalizar" else "Responde el control", style = MaterialTheme.typography.titleLarge)
            Text("${run.answers.size} respuestas guardadas")
            if (run.performedBy != state.userId) Text("Solo la persona que inició este control puede editarlo.")
            if (state.dirty) Text("Guarda tus respuestas antes de revisar y finalizar.")
        }
        ChecklistAnswerEditor(run.items, state.inputs, state.editable, state.reviewing || run.status != "DRAFT", onEdit, evidence)
        if (run.status == "DRAFT" && run.performedBy == state.userId) {
            if (state.reviewing) {
                Text("Al finalizar, las respuestas quedarán cerradas y se mostrará tu resultado.")
                // Flujo por pasos: en pantalla compacta estas acciones quedan fijas al pie.
                BpmActionBar { Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                BpmChunkyButton("Confirmar y finalizar control", onSubmit, Modifier.fillMaxWidth(), enabled = state.editable)
                BpmChunkyButton("Volver a las respuestas", onBackToEdit, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = state.editable)
                } }
            } else {
                BpmActionBar { Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                BpmChunkyButton("Guardar borrador", onSave, Modifier.fillMaxWidth(), enabled = state.editable && state.dirty)
                BpmChunkyButton("Revisar y finalizar", onReview, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = state.editable && !state.dirty && validRunAnswers(run.items, run.answers, complete = true, evidenceCounts = run.evidenceCounts))
                } }
            }
        }
    }
}
internal val complianceLabels = linkedMapOf("COMPLIANT" to "Cumple", "NON_COMPLIANT" to "No cumple", "NOT_APPLICABLE" to "No aplica")
internal fun runFailureMessage(reason: RunFailure) = when (reason) {
    RunFailure.QUEUED -> "Guardado en este dispositivo. Se enviará al recuperar la conexión; no necesitas repetirlo."
    RunFailure.SESSION -> "Tu sesión terminó. Inicia sesión de nuevo."
    RunFailure.DENIED -> "Tu cuenta no tiene permiso para consultar o modificar este control."
    RunFailure.NOT_FOUND -> "Este control o su sede ya no están disponibles."
    RunFailure.INVALID -> "Revisa las respuestas: usa el tipo indicado, hasta dos decimales y completa los campos obligatorios."
    RunFailure.CONFLICT -> "La versión del control cambió."
    RunFailure.ASSIGNMENT_REQUIRED -> "Este control ya está programado para tu sede. Ábrelo desde «Controles de hoy» para hacerlo: así queda ligado a lo que tu administrador pidió."
    RunFailure.ASSIGNMENT_STARTED -> "Ya se inició el control de este período. Consulta Control para retomarlo o ver su resultado."
    RunFailure.SHIFT_OUT_OF_HOURS -> "El turno de este control no está abierto ahora. Inicia la revisión dentro del horario del turno."
    RunFailure.TEMPLATE_OUTDATED -> "La plantilla tiene una versión nueva. Actualiza el catálogo o la programación y vuelve a iniciar."
    RunFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentarlo."
    else -> "No pudimos confirmar los datos del control. Comprueba tu conexión y vuelve a intentarlo."
}
