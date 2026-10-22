package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.checklists.domain.*

/** Historial de revisiones reales. Un borrador propio se retoma; la supervisión solo consulta. */
@Composable fun ChecklistHistoryScreen(state: ChecklistHistoryState, canPerform: Boolean,
    onSearch: (String) -> Unit, onFilter: (String) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onRetry: () -> Unit, onOpen: (ChecklistRunSummary) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (canPerform) "Mis revisiones" else "Revisiones del equipo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (canPerform) "Continúa lo que dejaste en curso o consulta tus revisiones finalizadas."
            else "Consulta quién revisó cada sede, cuándo lo hizo y cuál fue el resultado.")
        BpmSearchField(state.query, onSearch, "Buscar revisión", placeholder = "Nombre del control, sede o responsable", loading = state.loading)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "Todas", "DRAFT" to "En curso", "SUBMITTED" to "Finalizadas").forEach { (value, label) ->
                FilterChip(selected = state.status == value, onClick = { onFilter(value) }, label = { Text(label) })
            }
        }
        when {
            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.failure != null -> BpmCard(Modifier.fillMaxWidth()) {
                Text(failureMessage(state.failure), color = MaterialTheme.colorScheme.error)
                BpmSecondaryButton(onClick = onRetry) { Text("Reintentar") }
            }
            state.items.isEmpty() -> EmptyState("No hay revisiones para mostrar", if (state.query.isNotBlank() || state.status.isNotBlank())
                "Prueba otra búsqueda o cambia el filtro." else if (canPerform)
                "Cuando inicies una revisión con una plantilla, aparecerá aquí con su sede y su estado."
                else "Las revisiones aparecerán cuando el chef comience un control en una sede autorizada.")
            else -> state.items.forEach { item -> BpmCard(Modifier.fillMaxWidth()) {
                Eyebrow(if (item.status == "DRAFT") "EN CURSO" else "FINALIZADA")
                Text(item.templateName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(item.locationName)
                Text("Responsable: ${item.performedByName}")
                Text("Inicio: ${displayDateTime(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
                if (item.status == "DRAFT") Text("${item.answerCount} de ${item.questionCount} respuestas guardadas")
                else {
                    item.submittedAt?.let { Text("Finalización: ${displayDateTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    Text("Cumplimiento: ${item.compliancePercent?.toString()?.removeSuffix(".0")?.replace('.', ',')} %")
                }
                BpmSecondaryButton(onClick = { onOpen(item) }) {
                    Text(if (canPerform && item.status == "DRAFT" && item.performedBy == state.userId) "Continuar revisión" else "Ver revisión")
                }
            } }
        }
        PageNavigation(state.previous.size + 1, state.previous.isNotEmpty(), state.nextToken.isNotEmpty(), state.loading || state.failure != null, onPrevious, onNext)
    }
}

@Composable fun ChecklistHistoryDetailScreen(state: ChecklistHistoryState, onRetry: () -> Unit) {
    if (state.detailLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    if (state.detailFailure != null) {
        Text("No pudimos consultar esta revisión. Puede que tu acceso haya cambiado.")
        BpmSecondaryButton(onClick = onRetry) { Text("Reintentar") }; return
    }
    val run = state.detail ?: return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
            Text(run.templateName.ifBlank { "Revisión BPM" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (run.locationName.isNotBlank()) Text(run.locationName)
            if (run.performedByName.isNotBlank()) Text("Responsable: ${run.performedByName}")
            Text(if (run.status == "SUBMITTED") "Revisión finalizada. Las respuestas están cerradas." else "Revisión en curso. Esta vista permite consultar las respuestas guardadas.")
            if (run.createdAt.isNotBlank()) Text("Inicio: ${displayDateTime(run.createdAt)}")
            run.submittedAt?.let { Text("Finalización: ${displayDateTime(it)}") }
            run.compliancePercent?.let { Text("Cumplimiento: ${it.toString().removeSuffix(".0").replace('.', ',')} %") }
        }
        run.items.forEachIndexed { index, item ->
            val answer = run.answers.find { it.itemId == item.id }
            ChecklistAnswerReview(item, index, answer?.let { RunAnswerInput(it.complianceResult.ifEmpty { it.numericValue?.let(pe.bpm.core.domain.DecimalInput::format) ?: it.textValue }, it.observation) } ?: RunAnswerInput())
        }
    }
}

/** Lectura compartida entre la revisión previa al envío y la consulta del historial. */
@Composable internal fun ChecklistAnswerReview(item: ChecklistItem, index: Int, input: RunAnswerInput) {
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow(item.sectionName)
        Text("${index + 1}. ${item.label}", fontWeight = FontWeight.Bold)
        Text(complianceLabels[input.value] ?: input.value.ifBlank { "Sin respuesta" })
        if (input.observation.isNotBlank()) Text("Observación: ${input.observation}")
    }
}
