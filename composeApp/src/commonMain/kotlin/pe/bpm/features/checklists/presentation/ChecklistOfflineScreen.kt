package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.checklists.domain.*

@Composable
fun ChecklistOfflineScreen(state: ChecklistOfflineState, detailId: String?, onOpen: (String) -> Unit,
    onEdit: (String, RunAnswerInput) -> Unit, onProtect: () -> Unit, onReview: () -> Unit,
    onBackToEdit: () -> Unit, onQueue: (Boolean) -> Unit, onSync: () -> Unit,
    onRetryOpen: () -> Unit, onRemove: (String) -> Unit, onUseServerVersion: () -> Unit, evidence: @Composable (ChecklistItem) -> Unit = {}) {
    var removing by remember(detailId) { mutableStateOf(false) }
    var resolving by remember(detailId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
        if (state.busy || state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.localConflict) BpmCard(Modifier.fillMaxWidth()) {
            Text("Otra pestaña modificó esta revisión. No reemplazaremos sus respuestas con las tuyas.")
            Text("Puedes revisar la copia guardada después de confirmar el descarte de tus cambios pendientes.")
            BpmChunkyButton("Revisar copia guardada", { resolving = true }, tone = BpmChunkyTone.White, enabled = !state.busy)
        }
        state.failure?.let { reason ->
            Text(runFailureMessage(reason), color = MaterialTheme.colorScheme.error)
            if (state.entries.isEmpty() && reason !in setOf(RunFailure.SESSION, RunFailure.DENIED))
                BpmChunkyButton("Recuperar mis controles", onRetryOpen, tone = BpmChunkyTone.White, enabled = !state.busy)
        }
        if (detailId == null) {
            // El título «Controles en este dispositivo» ya lo pinta WorkspaceScreen (secondaryTitle de la ruta sin conexión).
            Text("Puedes responder los controles que preparaste aunque pierdas la conexión. Los resultados se confirman cuando se envían.")
            if (state.pendingCount > 0) {
                Text("${state.pendingCount} ${if (state.pendingCount == 1) "control pendiente" else "controles pendientes"} de confirmar")
                BpmChunkyButton("Enviar pendientes", onSync, Modifier.bpmPrimaryButtonWidth(), enabled = !state.syncing && !state.busy)
            }
            if (state.entries.isEmpty() && !state.busy) BpmCard(Modifier.fillMaxWidth()) {
                Text("Todavía no preparaste controles", style = MaterialTheme.typography.titleMedium)
                Text("Con conexión, abre una revisión que hayas iniciado y elige «Trabajar sin conexión». Así sus preguntas estarán disponibles aquí.")
            }
            state.entries.forEach { entry -> BpmCard(Modifier.fillMaxWidth()) { // lista-apilada: cola en orden de envío
                Text(entry.run.templateName.ifBlank { "Revisión BPM" }, style = MaterialTheme.typography.titleLarge)
                Text(entry.run.locationName.ifBlank { "Sede de la revisión" })
                Text(offlineRunLabel(entry))
                BpmChunkyButton(if (entry.editable) "Continuar revisión" else "Ver revisión", { onOpen(entry.run.id) }, tone = BpmChunkyTone.White, enabled = !state.busy)
            } }
            return@Column
        }
        val entry = state.selected?.takeIf { it.run.id == detailId }
        if (entry == null) {
            if (!state.busy) Text("Esta revisión no está disponible en este dispositivo con tus permisos actuales.")
            return@Column
        }
        Text(entry.run.templateName.ifBlank { "Revisión BPM" }, style = MaterialTheme.typography.headlineSmall)
        Text(entry.run.locationName.ifBlank { "Sede de la revisión" })
        if (entry.status != OfflineRunStatus.LOCAL || state.localStatus != DraftSaveStatus.SAVING)
            Text(offlineRunLabel(entry), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (!state.localConflict && state.localStatus != DraftSaveStatus.SAVED) DraftSaveNotice(state.localStatus, onProtect)
        entry.failure?.let { Text(runFailureMessage(it), color = MaterialTheme.colorScheme.error) }
        if (entry.status == OfflineRunStatus.ATTENTION) BpmCard(Modifier.fillMaxWidth()) {
            Text("Conservamos tus respuestas. El envío necesita revisión; no reemplazaremos los datos guardados por otra sesión.")
            Text("Si prefieres la revisión del servidor, puedes cargarla después de confirmar que descartas tus respuestas locales.")
            BpmChunkyButton("Usar versión del servidor", { resolving = true }, tone = BpmChunkyTone.White, enabled = !state.busy)
        }
        if (state.reviewing) Text("Revisa tus respuestas antes de finalizar", style = MaterialTheme.typography.titleLarge)
        ChecklistAnswerEditor(entry.run.items, entry.inputs, entry.editable && !state.busy && state.localStatus != DraftSaveStatus.FAILED,
            state.reviewing || !entry.editable || state.localConflict, onEdit, evidence)
        if (entry.editable && !state.localConflict) {
            if (state.reviewing) {
                Text("Se enviará cuando haya conexión. Después de confirmar el envío ya no podrás editar las respuestas.")
                // Flujo por pasos: en pantalla compacta estas acciones quedan fijas al pie.
                BpmActionBar { Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                    BpmChunkyButton("Finalizar y enviar cuando haya conexión", { onQueue(true) }, Modifier.fillMaxWidth(), enabled = !state.busy && state.localStatus != DraftSaveStatus.FAILED)
                    BpmChunkyButton("Seguir editando", onBackToEdit, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !state.busy)
                } }
            } else {
                BpmActionBar { Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                    BpmChunkyButton("Revisar y finalizar", onReview, Modifier.fillMaxWidth(), enabled = !state.busy)
                    BpmChunkyButton("Enviar borrador sin finalizar", { onQueue(false) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !state.busy && state.localStatus != DraftSaveStatus.FAILED)
                } }
            }
        } else if (entry.upload != null && entry.status != OfflineRunStatus.ATTENTION) {
            Text("Tus respuestas están protegidas. Confirma el envío antes de seguir editando.")
            BpmChunkyButton("Reintentar envío", onSync, tone = BpmChunkyTone.White, enabled = !state.syncing && !state.busy)
        }
        if (entry.run.status == "SUBMITTED")
            Text("Cumplimiento confirmado: ${entry.run.compliancePercent?.toString()?.removeSuffix(".0")?.replace('.', ',')} %")
        if (entry.upload == null) BpmTextButton(onClick = { removing = true }, enabled = !state.busy) { Text("Quitar de este dispositivo") }
    }
    if (resolving) AlertDialog(
        onDismissRequest = { resolving = false }, title = { Text("¿Descartar tus respuestas locales?") },
        text = { Text(if (state.localConflict) "Se cargará la copia guardada en este dispositivo. Tus cambios que no pudieron guardarse se perderán."
            else "Solo se conservará la versión que ya está en el servidor. Tus observaciones y respuestas sin enviar se perderán. Si falla la consulta, conservaremos tu copia.") },
        confirmButton = { BpmTextButton(onClick = { resolving = false; onUseServerVersion() }) { Text(if (state.localConflict) "Cargar copia guardada" else "Cargar versión del servidor") } },
        dismissButton = { BpmTextButton(onClick = { resolving = false }) { Text("Conservar mis respuestas") } },
    )
    if (removing && detailId != null) AlertDialog(
        onDismissRequest = { removing = false }, title = { Text("¿Quitar la copia de este dispositivo?") },
        text = { Text("Se eliminarán las respuestas locales que no hayas enviado. La revisión del servidor se conserva.") },
        confirmButton = { BpmTextButton(onClick = { removing = false; onRemove(detailId) }) { Text("Quitar copia") } },
        dismissButton = { BpmTextButton(onClick = { removing = false }) { Text("Conservar") } },
    )
}

private fun offlineRunLabel(entry: OfflineChecklist) = when (entry.status) {
    OfflineRunStatus.LOCAL -> "Guardado en este dispositivo · Sin enviar"
    OfflineRunStatus.PENDING -> if (entry.attempts >= 3) "Pendiente de enviar · Reintenta cuando tengas conexión" else "Pendiente de enviar"
    OfflineRunStatus.SENDING -> "Confirmando envío…"
    OfflineRunStatus.CONFIRMED -> if (entry.run.status == "SUBMITTED") "Finalizado y confirmado" else "Borrador confirmado en el servidor"
    OfflineRunStatus.ATTENTION -> "El envío necesita revisión"
}
