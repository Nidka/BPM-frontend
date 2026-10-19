package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
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
    onClose: () -> Unit, onProtectDraft: () -> Unit, onSuggestion: (String) -> Unit,
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
    LaunchedEffect(state.failure) { if (state.failure != null) scroll.scrollTo(0) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.align(Alignment.TopCenter).widthIn(max = 900.dp).fillMaxWidth().verticalScroll(scroll).padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(when (state.step) {
            TemplateEditorStep.Edit -> "Nueva plantilla de control"
            TemplateEditorStep.Review -> "Revisar y publicar"
            TemplateEditorStep.Discard -> "Descartar borrador"
            TemplateEditorStep.Published -> "Plantilla publicada"
        }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        if (state.recovered && state.published == null) Text("Recuperamos tu borrador. Revisa su contenido antes de publicar.")
        if (state.published == null) DraftSaveNotice(state.localStatus, onProtectDraft)
        when {
            state.restoring -> {
                if (state.localStatus != pe.bpm.core.presentation.DraftSaveStatus.FAILED) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.failure in setOf(PublicationFailure.ACCESS_DENIED, PublicationFailure.SESSION_REQUIRED) -> {
                BpmCard(Modifier.fillMaxWidth()) { Text(publicationMessage(state.failure!!), color = MaterialTheme.colorScheme.error) }
                BpmSecondaryButton(onClick = onClose) { Text("Volver a la organización") }
            }
            state.step == TemplateEditorStep.Discard -> DiscardDraftConfirmation(onKeep, onConfirmDiscard)
            state.step == TemplateEditorStep.Published -> BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Text("${state.published?.name.orEmpty()} ya está disponible en el catálogo de esta organización.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("La versión 1 quedó publicada. No puede modificarse desde este editor.")
                RaisedButton("Ver plantilla publicada", { state.published?.id?.let(onPublished) })
                BpmSecondaryButton(onClick = onClose) { Text("Volver a plantillas") }
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
                    if (state.canUseSuggestion) {
                        BpmCard(Modifier.fillMaxWidth()) {
                            Text("Puedes comenzar con un ejemplo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Adáptalo a los procedimientos de tu organización antes de publicarlo. También puedes escribir tu propio control abajo.")
                            checklistSuggestions.forEach { suggestion ->
                                BpmSecondaryButton(onClick = { onSuggestion(suggestion.id) }) { Text("Usar ejemplo: ${suggestion.name}") }
                            }
                        }
                    }
                    BpmCard(Modifier.fillMaxWidth()) {
                        Text("Datos del control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        EditorField(state.name, onName, "Nombre del control *", state.fields["name"], placeholder = "Ej.: Control de apertura")
                        EditorField(state.area, onArea, "Área que se revisará *", state.fields["area"], placeholder = "Ej.: Cocina")
                        EditorField(state.description, onDescription, "Descripción (opcional)", state.fields["description"], singleLine = false, placeholder = "Ej.: Revisión antes de comenzar el turno")
                        Text("Tu equipo verá este control después de que lo revises y publiques.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (state.questions.size == 1) "1 pregunta" else "${state.questions.size} preguntas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    state.questions.forEachIndexed { index, input -> key(input.id) {
                        QuestionEditor(input, index, state.questions.size, state.fields,
                            onChange = { change -> onQuestion(input.id, change) }, onType = { onType(input.id, it) },
                            onMove = { onMove(input.id, it) }, onRemove = { onRemove(input.id) })
                    } }
                    if (state.questions.size >= 100) Text("Llegaste al máximo de 100 preguntas por control.")
                    BpmSecondaryButton(onClick = onAdd, enabled = state.questions.size < 100) { Text("Agregar otra pregunta") }
                    RaisedButton("Revisar plantilla", onReview)
                    BpmTextButton(onClick = onDiscard) { Text("Descartar borrador") }
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
                    BpmSecondaryButton(onClick = onEdit, enabled = state.editable) { Text("Volver a editar") }
                    if (state.uncertain) BpmTextButton(onClick = onClose, enabled = !state.publishing) { Text("Volver sin descartar") }
                    else BpmTextButton(onClick = onDiscard, enabled = !state.publishing) { Text("Descartar borrador") }
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
        EditorField(input.label, { value -> onChange { it.copy(label = value) } }, "¿Qué quieres preguntar? *", fields["$prefix.label"],
            singleLine = false, placeholder = "Ej.: ¿La superficie de trabajo está limpia?")
        EditorField(input.section, { value -> onChange { it.copy(section = value) } }, "Grupo de preguntas *", fields["$prefix.sectionName"],
            placeholder = "Ej.: Limpieza de superficies")
        Text("Agrupa preguntas relacionadas: higiene del personal, limpieza o almacenamiento.", style = MaterialTheme.typography.bodySmall)
        QuestionResponsePicker(input.type, onType)
        BpmCheckboxRow("Es obligatorio responder esta pregunta", input.required) { checked -> onChange { it.copy(required = checked) } }
        if (input.type in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE)) {
            Text("¿Qué valores se consideran aceptables?", fontWeight = FontWeight.Medium)
            Text("Indica el mínimo y el máximo de tu procedimiento. Al responder, se comparará la medición con estos límites.", style = MaterialTheme.typography.bodySmall)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 420.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorField(input.minimum, { value -> onChange { it.copy(minimum = value) } }, "Valor mínimo aceptado *", null, keyboard = KeyboardType.Decimal)
                    EditorField(input.maximum, { value -> onChange { it.copy(maximum = value) } }, "Valor máximo aceptado *", null, keyboard = KeyboardType.Decimal)
                } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EditorField(input.minimum, { value -> onChange { it.copy(minimum = value) } }, "Valor mínimo aceptado *", null, modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                    EditorField(input.maximum, { value -> onChange { it.copy(maximum = value) } }, "Valor máximo aceptado *", null, modifier = Modifier.weight(1f), keyboard = KeyboardType.Decimal)
                }
            }
            fields["$prefix.range"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            EditorField(input.unit, { value -> onChange { it.copy(unit = value) } }, "Unidad de medida *", fields["$prefix.unit"], placeholder = if (input.type == ChecklistResponseType.TEMPERATURE) "Ej.: °C" else "Ej.: unidades")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (index > 0) BpmTextButton(onClick = { onMove(-1) }, modifier = Modifier.semantics { contentDescription = "Mover pregunta ${index + 1} antes de la anterior" }) { Text("Mover antes") }
            if (index < count - 1) BpmTextButton(onClick = { onMove(1) }, modifier = Modifier.semantics { contentDescription = "Mover pregunta ${index + 1} después de la siguiente" }) { Text("Mover después") }
            BpmTextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = "Eliminar pregunta ${index + 1}" }) { Text("Eliminar pregunta") }
        }
    }
}
@Composable private fun EditorField(value: String, onValue: (String) -> Unit, label: String, error: String?, singleLine: Boolean = true,
    modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, placeholder: String? = null) {
    BpmTextField(value, onValue, label, modifier, singleLine = singleLine,
        minLines = if (singleLine) 1 else 2, error = error, placeholder = placeholder, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
internal fun ChecklistResponseType.label() = when (this) {
    ChecklistResponseType.COMPLIANCE -> "Cumple / No cumple"
    ChecklistResponseType.TEXT -> "Respuesta escrita"
    ChecklistResponseType.TEMPERATURE -> "Temperatura"
    ChecklistResponseType.NUMBER -> "Valor numérico"
}
internal fun publicationMessage(reason: PublicationFailure) = when (reason) {
    PublicationFailure.SESSION_REQUIRED -> "Tu sesión terminó. Inicia sesión para continuar."
    PublicationFailure.ACCESS_DENIED -> "Tu cuenta ya no tiene permiso para publicar plantillas en esta organización."
    PublicationFailure.VALIDATION -> "Revisa los campos indicados antes de publicar."
    PublicationFailure.CONFLICT -> "Hay una publicación diferente asociada a este borrador. Descarta el borrador para preparar una nueva plantilla."
    PublicationFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentar."
    else -> "No pudimos confirmar la publicación. Conservamos la plantilla para que puedas reintentar."
}

@Composable private fun QuestionResponsePicker(selected: ChecklistResponseType, onSelect: (ChecklistResponseType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("¿Cómo responderá tu equipo?", fontWeight = FontWeight.Medium)
        if (expanded) Column(Modifier.selectableGroup()) {
            ChecklistResponseType.entries.forEach { type ->
                BpmChoiceRow(type.label(), selected == type, supportingText = type.responseHint()) { onSelect(type); expanded = false }
            }
        } else {
            Text(selected.label(), fontWeight = FontWeight.Bold)
            Text(selected.responseHint(), style = MaterialTheme.typography.bodySmall)
        }
        BpmTextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Cerrar opciones" else "Cambiar forma de responder") }
    }
}
private fun ChecklistResponseType.responseHint() = when (this) {
    ChecklistResponseType.COMPLIANCE -> "Elige Cumple, No cumple o No aplica. Ejemplo: comprobar si una superficie está limpia."
    ChecklistResponseType.TEMPERATURE -> "Registra una temperatura y compárala con los límites de tu procedimiento."
    ChecklistResponseType.TEXT -> "Escribe una respuesta. Ejemplo: describir el estado de un utensilio."
    ChecklistResponseType.NUMBER -> "Registra una cantidad o medición. Ejemplo: unidades revisadas. Se compara con los límites que indiques."
}
