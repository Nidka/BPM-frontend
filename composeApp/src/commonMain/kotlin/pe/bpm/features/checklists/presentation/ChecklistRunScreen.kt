package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.checklists.domain.*
import pe.bpm.core.domain.DecimalInput
import pe.bpm.core.domain.InputRule

data class ChecklistRunLocation(val id: String, val name: String)

/** Vista de ejecución: emite acciones, no accede a red ni determina la autorización. */
@Composable fun ChecklistRunScreen(state: ChecklistRunState, template: ChecklistTemplate?, loadingTemplate: Boolean,
    templateError: String?, locations: List<ChecklistRunLocation>, locationQuery: String, locationPage: Int,
    locationsLoading: Boolean, locationsFailed: Boolean, previousLocations: Boolean, nextLocations: Boolean,
    onLocationSearch: (String) -> Unit, onLocationPrevious: () -> Unit, onLocationNext: () -> Unit, onLocationRetry: () -> Unit,
    onBegin: (String, String) -> Unit, onEdit: (String, RunAnswerInput) -> Unit,
    onSave: () -> Unit, onReview: () -> Unit, onSubmit: () -> Unit, onBackToEdit: () -> Unit,
    onRetry: () -> Unit, onProtectDraft: () -> Unit, onReload: () -> Unit, onBack: () -> Unit) {
    val navigation = rememberBpmFormNavigation(state.run?.items.orEmpty().flatMap { item ->
        (if (item.responseType == ChecklistResponseType.COMPLIANCE) emptyList() else listOf("${item.id}.value")) + "${item.id}.observation"
    })
    var location by remember(state.organizationId, template?.id) { mutableStateOf<String?>(null) }
    var locationName by remember(state.organizationId, template?.id) { mutableStateOf("") }
    var confirmReload by remember(state.run?.id, state.failure) { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 860.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.dirty || state.pending || state.localStatus == pe.bpm.core.presentation.DraftSaveStatus.FAILED)
            DraftSaveNotice(state.localStatus, onProtectDraft)
        if (state.pending && state.failure == null) BpmSecondaryButton(onClick = onRetry, enabled = !state.busy) { Text("Comprobar operación pendiente") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure -> BpmCard(Modifier.fillMaxWidth()) {
            Text(runFailureMessage(failure), color = MaterialTheme.colorScheme.error)
            if (state.pending) {
                Text("No pudimos confirmar esta operación. Reintenta para comprobar el resultado antes de continuar.")
                BpmSecondaryButton(onClick = onRetry, enabled = !state.busy) { Text("Reintentar operación") }
            } else if (failure == RunFailure.CONFLICT) {
                Text("Tus cambios no se sobrescriben automáticamente. Cargar la versión guardada descartará las respuestas locales sin guardar.")
                if (confirmReload) {
                    BpmSecondaryButton(onClick = { confirmReload = false; onReload() }) { Text("Confirmar carga de versión guardada") }
                    BpmTextButton(onClick = { confirmReload = false }) { Text("Conservar mis respuestas") }
                } else BpmSecondaryButton(onClick = { confirmReload = true }) { Text("Revisar versión guardada") }
            } else if (state.run == null && failure !in setOf(RunFailure.DENIED, RunFailure.SESSION, RunFailure.NOT_FOUND))
                BpmSecondaryButton(onClick = onReload, enabled = !state.busy) { Text("Reintentar consulta") }
        } }
        val run = state.run
        if (run == null) {
            if (state.failure in setOf(RunFailure.DENIED, RunFailure.SESSION, RunFailure.NOT_FOUND)) return@Column
            if (loadingTemplate) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (templateError != null) { Text(templateError); return@Column }
            if (template == null) return@Column
            BpmCard(Modifier.fillMaxWidth()) {
                Text(template.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Versión ${template.versionNumber} · ${template.items.size} preguntas")
                Text("El chef registra aquí lo que observa en el local. Elige la sede que vas a revisar; cada inicio crea una nueva revisión con fecha y responsable.")
                BpmTextField(locationQuery, onLocationSearch, "Buscar sede", enabled = !state.busy && !state.pending)
                if (locationsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (locationsFailed) BpmSecondaryButton(onClick = onLocationRetry) { Text("Reintentar sedes") }
                else {
                    if (locations.isEmpty()) Text("No hay sedes disponibles para esta búsqueda.")
                    if (locations.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).selectableGroup()) {
                        items(locations, key = { it.id }) { site ->
                            BpmChoiceRow(site.name, location == site.id, enabled = !state.busy && !state.pending) { location = site.id; locationName = site.name }
                        }
                    }
                    if (location != null) Text("Sede elegida: $locationName", style = MaterialTheme.typography.bodySmall)
                    PageNavigation(locationPage, previousLocations, nextLocations, locationsLoading || state.pending, onLocationPrevious, onLocationNext)
                }
                RaisedButton("Comenzar revisión", { location?.let { onBegin(it, template.versionId) } },
                    enabled = location != null && !state.busy && !state.pending && !locationsLoading && !locationsFailed && state.localStatus != pe.bpm.core.presentation.DraftSaveStatus.FAILED)
            }
            return@Column
        }
        BpmCard(Modifier.fillMaxWidth()) {
            Text(run.templateName.ifBlank { template?.name ?: "Revisión BPM" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(run.locationName.ifBlank { locations.firstOrNull { it.id == run.locationId }?.name.orEmpty() })
            if (run.createdAt.isNotBlank()) Text("Inicio: ${pe.bpm.core.presentation.displayDateTime(run.createdAt)}", style = MaterialTheme.typography.bodySmall)
        }
        if (run.status == "SUBMITTED" && !state.dirty) {
            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Mascot(Modifier.size(96.dp))
                Text("Control finalizado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Cumplimiento: ${run.compliancePercent?.toString()?.removeSuffix(".0")?.replace('.', ',')} %")
                Text("Las respuestas enviadas ya no se pueden editar.")
                BpmSecondaryButton(onClick = onBack) { Text("Ver mis revisiones") }
            }
        } else {
            Text(if (state.reviewing) "Revisa antes de finalizar" else "Responde el control", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${run.answers.size} respuestas guardadas")
            if (run.performedBy != state.userId) Text("Solo la persona que inició este control puede editarlo.")
            if (state.dirty) Text("Guarda tus respuestas antes de revisar y finalizar.")
        }
        run.items.forEachIndexed { index, item ->
            val input = state.inputs[item.id] ?: RunAnswerInput()
            if (state.reviewing || run.status != "DRAFT") ChecklistAnswerReview(item, index, input)
            else BpmCard(Modifier.fillMaxWidth()) {
                Eyebrow(item.sectionName)
                Text("${index + 1}. ${item.label}${if (item.required) " *" else ""}", fontWeight = FontWeight.Bold)
                if (item.responseType in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE))
                    Text("Criterio publicado: ${item.minimumValue} a ${item.maximumValue} ${item.unit}")
                if (item.evidenceRequiredOnFailure) Text("Si no cumple, se requiere evidencia. La carga de archivos aún no está disponible.")
                    if (item.responseType == ChecklistResponseType.COMPLIANCE) Column(Modifier.selectableGroup()) {
                        complianceLabels.forEach { (value, label) -> BpmChoiceRow(label, input.value == value, state.editable) { onEdit(item.id, input.copy(value = value)) } }
                    } else BpmTextField(input.value, { onEdit(item.id, input.copy(value = it)) }, if (item.responseType == ChecklistResponseType.TEXT) "Respuesta" else "Valor ${item.unit}",
                        enabled = state.editable, navigation = navigation["${item.id}.value"], inputKind = if (item.responseType == ChecklistResponseType.TEXT) BpmInputKind.Text else BpmInputKind.Decimal,
                        rule = if (item.responseType == ChecklistResponseType.TEXT) ChecklistInputRules.answer else InputRule { if (it.isBlank()) null else DecimalInput.error(it) })
                    BpmTextField(input.observation, { onEdit(item.id, input.copy(observation = it)) }, "Observación (opcional)", enabled = state.editable, navigation = navigation["${item.id}.observation"], rule = ChecklistInputRules.answer)
            }
        }
        if (run.status == "DRAFT" && run.performedBy == state.userId) {
            if (state.reviewing) {
                Text("Al finalizar, las respuestas quedarán cerradas y se mostrará tu resultado.")
                RaisedButton("Confirmar y finalizar control", onSubmit, enabled = state.editable)
                BpmSecondaryButton(onClick = onBackToEdit, enabled = state.editable) { Text("Volver a las respuestas") }
            } else {
                RaisedButton("Guardar borrador", onSave, enabled = state.editable && state.dirty)
                BpmSecondaryButton(onClick = onReview, enabled = state.editable && !state.dirty && validRunAnswers(run.items, run.answers, complete = true)) { Text("Revisar y finalizar") }
            }
        }
    }
}
internal val complianceLabels = linkedMapOf("COMPLIANT" to "Cumple", "NON_COMPLIANT" to "No cumple", "NOT_APPLICABLE" to "No aplica")
private fun runFailureMessage(reason: RunFailure) = when (reason) {
    RunFailure.SESSION -> "Tu sesión terminó. Inicia sesión de nuevo."
    RunFailure.DENIED -> "Tu cuenta no tiene permiso para consultar o modificar este control."
    RunFailure.NOT_FOUND -> "Este control o su sede ya no están disponibles."
    RunFailure.INVALID -> "Revisa las respuestas: usa el tipo indicado, hasta dos decimales y completa los campos obligatorios."
    RunFailure.CONFLICT -> "La versión del control cambió."
    RunFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentarlo."
    else -> "No pudimos confirmar los datos del control. Comprueba tu conexión y vuelve a intentarlo."
}
