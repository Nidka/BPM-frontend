package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.checklists.domain.*

/** Historial de versiones y editor de preguntas; sin scroll propio: lo gestiona el contenedor. */
@Composable
fun ChecklistVersionsScreen(
    state: ChecklistVersionsState,
    onStartEdit: () -> Unit,
    onView: (String) -> Unit,
    onCloseView: () -> Unit,
    onNote: (String) -> Unit,
    onQuestion: (String, (TemplateQuestionInput) -> TemplateQuestionInput) -> Unit,
    onType: (String, ChecklistResponseType) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onReview: () -> Unit,
    onBackToEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onPublish: () -> Unit,
    onReload: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    canEdit: Boolean,
) {
    val navigation = rememberBpmFormNavigation(
        listOf("changeNote") + state.questions.flatMap { input ->
            listOf("${input.id}.label", "${input.id}.section") +
                if (input.type in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE))
                    listOf("${input.id}.minimum", "${input.id}.maximum", "${input.id}.unit") else emptyList()
        },
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val current = state.current
        Text(current?.name ?: "Versiones de la plantilla", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let {
            Text(versionFailureMessage(it), color = MaterialTheme.colorScheme.error)
            if (state.conflict || state.mode == VersionMode.History) BpmSecondaryButton(onClick = onReload) { Text("Recargar plantilla") }
        }
        if (state.conflict) {
            BpmCard(Modifier.fillMaxWidth()) {
                Text("Hay una versión más reciente", style = MaterialTheme.typography.titleMedium)
                Text("Tus cambios no se publicaron. Recarga la plantilla, revisa la versión nueva y vuelve a aplicar lo que necesites.")
                RaisedButton("Recargar y empezar de nuevo", onReload, Modifier.fillMaxWidth())
            }
        }
        state.published?.let {
            Text("Se publicó la versión ${it.versionNumber}. Las revisiones ya iniciadas conservan la versión con la que empezaron.", color = MaterialTheme.colorScheme.primary)
        }
        when {
            state.viewing != null || state.viewingLoading || state.viewingFailure != null -> {
                if (state.viewingLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.viewingFailure?.let { Text(versionFailureMessage(it), color = MaterialTheme.colorScheme.error) }
                state.viewing?.let {
                    Text("Versión ${it.versionNumber} · ${it.items.size} preguntas", style = MaterialTheme.typography.titleMedium)
                    ChecklistQuestionPreview(it)
                }
                BpmTextButton(onClick = onCloseView) { Text("Volver al historial") }
            }
            state.mode == VersionMode.History && current != null -> {
                BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Versión vigente: ${current.versionNumber}", style = MaterialTheme.typography.titleMedium)
                    Text("${current.items.size} preguntas · ${current.area}")
                    Text("Editar crea una versión nueva. Las programaciones y el catálogo usan siempre la vigente; las revisiones en curso siguen con su versión.", style = MaterialTheme.typography.bodySmall)
                    if (canEdit) RaisedButton("Editar preguntas (crear versión ${current.versionNumber + 1})", onStartEdit, Modifier.fillMaxWidth(), enabled = !state.loading)
                    else Text("Solo un administrador puede editar las preguntas.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Historial", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                BpmCardGrid(state.history, minCardWidth = 340.dp, maxColumns = 3) { version, cardModifier ->
                    BpmCard(cardModifier) {
                        Text("Versión ${version.versionNumber} · ${if (version.status == VersionStatus.PUBLISHED) "Vigente" else "Archivada"}", style = MaterialTheme.typography.titleMedium)
                        Text("${version.itemCount} preguntas · ${displayDateTime(version.publishedAt)}")
                        if (version.changeNote.isNotBlank()) Text("Cambio: ${version.changeNote}")
                        BpmSecondaryButton(onClick = { onView(version.id) }) { Text("Ver preguntas") }
                    }
                }
                PageNavigation(state.previous.size + 1, state.previous.isNotEmpty(), state.nextToken.isNotEmpty(), state.loading, onPrevious, onNext)
            }
            state.mode == VersionMode.Edit -> {
                Text("Nueva versión ${(current?.versionNumber ?: 0) + 1}", style = MaterialTheme.typography.titleLarge)
                BpmTextField(state.changeNote, onNote, "Nota del cambio (opcional)", error = state.fields["changeNote"], enabled = state.editable,
                    placeholder = "Ej.: Se agregó la revisión de temperatura", navigation = navigation["changeNote"])
                state.fields["items"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.fields["baseVersionNumber"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.fields["versionId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(if (state.questions.size == 1) "1 pregunta" else "${state.questions.size} preguntas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                state.questions.forEachIndexed { index, input ->
                    key(input.id) {
                        QuestionEditor(input, index, state.questions.size, state.fields, navigation,
                            onChange = { change -> onQuestion(input.id, change) }, onType = { onType(input.id, it) },
                            onMove = { onMove(input.id, it) }, onRemove = { onRemove(input.id) })
                    }
                }
                BpmSecondaryButton(onClick = onAdd, enabled = state.questions.size < 100 && state.editable) { Text("Agregar otra pregunta") }
                RaisedButton("Revisar cambios", onReview, Modifier.fillMaxWidth(), enabled = state.editable)
                BpmTextButton(onClick = onCancelEdit, enabled = state.editable) { Text("Descartar cambios") }
            }
            state.mode == VersionMode.Review -> {
                BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Versión ${(current?.versionNumber ?: 0) + 1} de ${current?.name.orEmpty()}", style = MaterialTheme.typography.titleLarge)
                    Text("${state.questions.size} preguntas. La versión ${current?.versionNumber ?: 0} pasará a archivada.")
                    if (state.changeNote.isNotBlank()) Text("Nota: ${state.changeNote.trim()}")
                    Text("Las revisiones ya iniciadas con la versión anterior podrán terminarse y consultarse.", style = MaterialTheme.typography.bodySmall)
                }
                state.questions.mapIndexed { i, q -> q.domain(i) }.forEach { question -> // lista-apilada: preguntas en orden de aplicación
                    BpmCard(Modifier.fillMaxWidth()) {
                        Text("${question.displayOrder}. ${question.label}", fontWeight = FontWeight.Bold)
                        Text("${question.sectionName} · ${question.responseType.label()}")
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                RaisedButton(if (state.busy) "Comprobando publicación…" else if (state.uncertain) "Comprobar y reintentar" else "Publicar versión", onPublish, Modifier.fillMaxWidth(), enabled = !state.busy && !state.conflict)
                BpmSecondaryButton(onClick = onBackToEdit, enabled = !state.busy && !state.uncertain) { Text("Volver a editar") }
            }
        }
    }
}
