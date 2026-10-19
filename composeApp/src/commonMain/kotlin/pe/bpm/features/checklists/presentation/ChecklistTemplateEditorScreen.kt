package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.checklists.domain.*

/** El editor emite acciones; el ViewModel conserva el borrador y coordina la publicación. */
@Composable fun ChecklistTemplateEditorScreen(
    state: ChecklistTemplateEditorState, onName: (String) -> Unit, onArea: (String) -> Unit,
    onDescription: (String) -> Unit, onQuestion: (String, (TemplateQuestionInput) -> TemplateQuestionInput) -> Unit,
    onType: (String, ChecklistResponseType) -> Unit, onAdd: () -> Unit, onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit, onUndoRemove: () -> Unit, onClearUndo: () -> Unit, onReview: () -> Unit, onPublish: () -> Unit, onEdit: () -> Unit,
    onDiscard: () -> Unit, onKeep: () -> Unit, onConfirmDiscard: () -> Unit, onPublished: (String) -> Unit,
    onClose: () -> Unit, noticeVisible: Boolean, notice: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.removed?.input?.id) {
        snackbars.currentSnackbarData?.dismiss()
        if (state.removed != null) {
            val result = snackbars.showSnackbar("Pregunta eliminada", "Deshacer", withDismissAction = true)
            if (result == SnackbarResult.ActionPerformed) onUndoRemove() else onClearUndo()
        }
    }
    LaunchedEffect(state.step, state.validationRevision) { scroll.scrollTo(0) }
    LaunchedEffect(state.failure, noticeVisible) { if (state.failure != null || noticeVisible) scroll.scrollTo(0) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.align(Alignment.TopCenter).widthIn(max = 900.dp).fillMaxWidth().verticalScroll(scroll).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(when (state.step) {
            TemplateEditorStep.Edit -> "Nueva plantilla de control"
            TemplateEditorStep.Review -> "Revisar y publicar"
            TemplateEditorStep.Discard -> "Descartar borrador"
            TemplateEditorStep.Published -> "Plantilla publicada"
        }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        notice()
        when {
            state.failure in setOf(PublicationFailure.ACCESS_DENIED, PublicationFailure.SESSION_REQUIRED) -> {
                BpmCard(Modifier.fillMaxWidth()) { Text(publicationMessage(state.failure!!), color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = onClose) { Text("Volver a la organización") }
            }
            state.step == TemplateEditorStep.Discard -> BpmCard(Modifier.fillMaxWidth()) {
                Text("Se eliminarán los cambios de esta plantilla que aún no publicaste.")
                RaisedButton("Conservar borrador", onKeep)
                TextButton(onClick = onConfirmDiscard) { Text("Descartar cambios") }
            }
            state.step == TemplateEditorStep.Published -> BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Text("${state.published?.name.orEmpty()} ya está disponible en el catálogo de esta organización.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("La versión 1 quedó publicada. No puede modificarse desde este editor.")
                RaisedButton("Ver plantilla publicada", { state.published?.id?.let(onPublished) })
                OutlinedButton(onClick = onClose) { Text("Volver a plantillas") }
            }
            else -> {
                state.failure?.let { failure -> BpmCard(Modifier.fillMaxWidth()) {
                    Text(publicationMessage(failure), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.uncertain) Text("Conservamos la plantilla tal como la enviaste. Al reintentar comprobaremos si ya está publicada.")
                } }
                state.fields["id"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.fields["items"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.step == TemplateEditorStep.Edit) {
                    BpmCard(Modifier.fillMaxWidth()) {
                        Text("Datos de la plantilla", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        EditorField(state.name, onName, "Nombre de la plantilla", state.fields["name"])
                        EditorField(state.area, onArea, "Área evaluada", state.fields["area"])
                        EditorField(state.description, onDescription, "Descripción (opcional)", state.fields["description"], singleLine = false)
                        Text("El borrador se conserva en memoria mientras esta sesión siga abierta. Todavía no está publicado ni guardado sin conexión.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Preguntas · ${state.questions.size} de 100", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    state.questions.forEachIndexed { index, input -> key(input.id) {
                        QuestionEditor(input, index, state.questions.size, state.fields,
                            onChange = { change -> onQuestion(input.id, change) }, onType = { onType(input.id, it) },
                            onMove = { onMove(input.id, it) }, onRemove = { onRemove(input.id) })
                    } }
                    OutlinedButton(onClick = onAdd, enabled = state.questions.size < 100) { Text("Agregar pregunta") }
                    RaisedButton("Revisar plantilla", onReview)
                    TextButton(onClick = onDiscard) { Text("Descartar borrador") }
                } else {
                    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                        Text(state.name.trim(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${state.area.trim()} · Primera versión")
                        if (state.description.isNotBlank()) Text(state.description.trim())
                        Text("Publicar hará que esta versión esté disponible para los miembros autorizados de tu organización. Revisa las preguntas y los criterios antes de continuar.")
                    }
                    state.draft().items.forEach { question -> BpmCard(Modifier.fillMaxWidth()) {
                        Text("${question.displayOrder}. ${question.label}", fontWeight = FontWeight.Bold)
                        Text("${question.sectionName} · ${question.responseType.label()}")
                        Text(if (question.required) "Respuesta obligatoria" else "Respuesta opcional")
                        if (question.minimumValue != null) Text("Criterio: ${question.minimumValue} a ${question.maximumValue} ${question.unit}")
                    } }
                    if (state.publishing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    RaisedButton(if (state.publishing) "Comprobando publicación…" else if (state.uncertain) "Comprobar y reintentar" else "Publicar plantilla",
                        onPublish, enabled = !state.publishing && state.failure != PublicationFailure.CONFLICT)
                    OutlinedButton(onClick = onEdit, enabled = state.editable) { Text("Volver a editar") }
                    if (state.uncertain) TextButton(onClick = onClose, enabled = !state.publishing) { Text("Volver sin descartar") }
                    else TextButton(onClick = onDiscard, enabled = !state.publishing) { Text("Descartar borrador") }
                }
            }
        }
    }
    SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).padding(16.dp))
    }
}

@Composable private fun QuestionEditor(input: TemplateQuestionInput, index: Int, count: Int, fields: Map<String, String>,
    onChange: ((TemplateQuestionInput) -> TemplateQuestionInput) -> Unit,
    onType: (ChecklistResponseType) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    val prefix = "items.${input.id}"
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Pregunta ${index + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        EditorField(input.section, { value -> onChange { it.copy(section = value) } }, "Sección · pregunta ${index + 1}", fields["$prefix.sectionName"])
        EditorField(input.label, { value -> onChange { it.copy(label = value) } }, "Enunciado · pregunta ${index + 1}", fields["$prefix.label"], singleLine = false)
        Text("Tipo de respuesta", fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChecklistResponseType.entries.forEach { type -> BpmFeedback(MaterialTheme.colorScheme.primary) {
                FilterChip(selected = input.type == type, onClick = { onType(type) }, label = { Text(type.label()) },
                    modifier = Modifier.semantics { contentDescription = "${type.label()} · pregunta ${index + 1}" })
            } }
        }
        BpmCheckboxRow("Respuesta obligatoria · pregunta ${index + 1}", input.required) { checked -> onChange { it.copy(required = checked) } }
        if (input.type in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE)) {
            Text("Usa el criterio aprobado para este control. La aplicación no establece un rango universal.", style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 420.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorField(input.minimum, { value -> onChange { it.copy(minimum = value) } }, "Mínimo · pregunta ${index + 1}", null, keyboard = KeyboardType.Decimal)
                    EditorField(input.maximum, { value -> onChange { it.copy(maximum = value) } }, "Máximo · pregunta ${index + 1}", null, keyboard = KeyboardType.Decimal)
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorField(input.minimum, { value -> onChange { it.copy(minimum = value) } }, "Mínimo · pregunta ${index + 1}", null, modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                    EditorField(input.maximum, { value -> onChange { it.copy(maximum = value) } }, "Máximo · pregunta ${index + 1}", null, modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                }
            }
            fields["$prefix.range"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            EditorField(input.unit, { value -> onChange { it.copy(unit = value) } }, "Unidad · pregunta ${index + 1}", fields["$prefix.unit"])
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onMove(-1) }, enabled = index > 0, modifier = Modifier.semantics { contentDescription = "Subir pregunta ${index + 1}" }) { Text("Subir") }
            TextButton(onClick = { onMove(1) }, enabled = index < count - 1, modifier = Modifier.semantics { contentDescription = "Bajar pregunta ${index + 1}" }) { Text("Bajar") }
            TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Eliminar pregunta ${index + 1}" }) { Text("Eliminar") }
        }
    }
}
@Composable private fun EditorField(value: String, onValue: (String) -> Unit, label: String, error: String?, singleLine: Boolean = true,
    modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text) {
    BpmTextField(value, onValue, label, modifier, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2, error = error, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
internal fun ChecklistResponseType.label() = when (this) {
    ChecklistResponseType.COMPLIANCE -> "Cumplimiento"
    ChecklistResponseType.TEXT -> "Texto"
    ChecklistResponseType.TEMPERATURE -> "Temperatura"
    ChecklistResponseType.NUMBER -> "Número"
}
internal fun publicationMessage(reason: PublicationFailure) = when (reason) {
    PublicationFailure.SESSION_REQUIRED -> "Tu sesión terminó. Inicia sesión para continuar."
    PublicationFailure.ACCESS_DENIED -> "Tu cuenta ya no tiene permiso para publicar plantillas en esta organización."
    PublicationFailure.VALIDATION -> "Revisa los campos indicados antes de publicar."
    PublicationFailure.CONFLICT -> "Hay una publicación diferente asociada a este borrador. Descarta el borrador para preparar una nueva plantilla."
    PublicationFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentar."
    else -> "No pudimos confirmar la publicación. Conservamos la plantilla para que puedas reintentar."
}
