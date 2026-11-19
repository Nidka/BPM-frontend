package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*

/** Academia para ADMIN/SUPER_ADMIN: cursos, borrador, videos y publicación. */
@Composable
fun TrainingAdminScreen(viewModel: TrainingAuthoringViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.loading || state.loadingDraft) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmSecondaryButton(onClick = viewModel::refresh) { Text("Volver a intentar") }
        }
        state.notice?.let { notice ->
            Text(notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        when {
            !state.allowed -> EmptyState("Academia restringida", "Solo el administrador de la organización puede preparar cursos.")
            state.editor != null -> DraftEditor(state.editor!!, viewModel)
            state.creating != null -> CreateCourseForm(state, viewModel)
            else -> CourseList(state, viewModel)
        }
    }
}

private fun statusLabel(status: CourseStatus) = when (status) {
    CourseStatus.DRAFT -> "Borrador"
    CourseStatus.PUBLISHED -> "Publicado"
    CourseStatus.ARCHIVED -> "Archivado"
}

@Composable
private fun CourseList(state: AuthoringState, viewModel: TrainingAuthoringViewModel) {
    Text("Cursos de Academia", style = MaterialTheme.typography.headlineMedium)
    Text("Prepara lecciones con texto y video, escribe preguntas con su explicación y publica cuando todo esté listo. Quienes ya aprenden siguen viendo la versión publicada hasta que publiques otra.")
    RaisedButton("Crear curso", viewModel::startCreate, Modifier.widthIn(min = 160.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BpmFilterChip(state.statusFilter == null, { viewModel.filter(null) }, { Text("Todos") })
        CourseStatus.entries.forEach { status ->
            BpmFilterChip(state.statusFilter == status, { viewModel.filter(status) }, { Text(statusLabel(status)) })
        }
    }
    if (state.courses.isEmpty() && !state.loading) EmptyState("Sin cursos", "Todavía no hay cursos con este filtro. Crea el primero.")
    BpmGridLayout(state.courses) { course, cardModifier ->
        BpmCard(cardModifier) {
            Eyebrow(course.code)
            Text(course.title, style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(course.status) + (course.publishedVersionNumber?.let { " · versión $it publicada" } ?: ""))
            if (course.hasDraft) Text("Tiene un borrador en preparación", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BpmSecondaryButton(onClick = { viewModel.openDraft(course.id) }, modifier = Modifier.fillMaxWidth()) { Text(if (course.hasDraft) "Editar borrador" else "Preparar nueva versión") }
        }
    }
}

@Composable
private fun CreateCourseForm(state: AuthoringState, viewModel: TrainingAuthoringViewModel) {
    val form = state.creating ?: return
    fun issue(kind: DraftIssueKind) = if (form.showIssues) form.issues.firstOrNull { it.kind == kind }?.message else null
    Text("Nuevo curso", style = MaterialTheme.typography.headlineMedium)
    BpmTextField(form.code, { v -> viewModel.editCreate { it.copy(code = v) } }, "Código del curso (por ejemplo M-01)", error = issue(DraftIssueKind.COURSE_CODE), enabled = !state.creatingBusy)
    BpmTextField(form.title, { v -> viewModel.editCreate { it.copy(title = v) } }, "Título", error = issue(DraftIssueKind.COURSE_TITLE), enabled = !state.creatingBusy)
    BpmTextField(form.description, { v -> viewModel.editCreate { it.copy(description = v) } }, "Descripción", error = issue(DraftIssueKind.COURSE_DESCRIPTION),
        singleLine = false, minLines = 3, enabled = !state.creatingBusy)
    BpmTextField(form.minutes, { v -> viewModel.editCreate { it.copy(minutes = v.filter(Char::isDigit).take(5)) } }, "Duración estimada (minutos)",
        error = issue(DraftIssueKind.COURSE_DURATION), inputKind = BpmInputKind.Integer, enabled = !state.creatingBusy)
    state.createFailure?.let { Text(trainingFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (state.creatingBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    RaisedButton("Crear y preparar", viewModel::create, Modifier.widthIn(min = 160.dp), enabled = !state.creatingBusy)
    BpmTextButton(onClick = viewModel::cancelCreate, enabled = !state.creatingBusy) { Text("Cancelar") }
}

@Composable
private fun DraftEditor(editor: DraftEditorState, viewModel: TrainingAuthoringViewModel) {
    val draft = editor.draft
    val issues = if (editor.showIssues) editor.issues else emptyList()
    fun issue(kind: DraftIssueKind, id: String = "") = issues.firstOrNull { it.kind == kind && it.id == id }?.message
    val enabled = !editor.saving && !editor.publishing
    Eyebrow("BORRADOR · VERSIÓN ${draft.versionNumber}")
    Text(draft.course.title, style = MaterialTheme.typography.headlineMedium)
    Text(if (editor.dirty) "Tienes cambios sin guardar." else "Todo guardado.", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    BpmTextField(draft.passingScore.toString(), { v -> viewModel.edit { it.copy(passingScore = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 0) } },
        "Puntaje de aprobación (0 a 100)", error = issue(DraftIssueKind.PASSING_SCORE), inputKind = BpmInputKind.Integer, enabled = enabled)

    Text("Lecciones (${draft.lessons.size} de ${TrainingLimits.MAX_LESSONS})", style = MaterialTheme.typography.titleLarge)
    draft.lessons.forEachIndexed { number, lesson ->
        key(lesson.id) {
            BpmCard(Modifier.fillMaxWidth()) {
                Eyebrow("LECCIÓN ${number + 1}")
                BpmTextField(lesson.title, { v -> viewModel.edit { TrainingDraftEdits.updateLesson(it, lesson.id, title = v) } }, "Título de la lección",
                    error = issue(DraftIssueKind.LESSON_TITLE, lesson.id), enabled = enabled)
                BpmTextField(lesson.content, { v -> viewModel.edit { TrainingDraftEdits.updateLesson(it, lesson.id, content = v) } }, "Texto de la lección (también es la transcripción del video)",
                    error = issue(DraftIssueKind.LESSON_CONTENT, lesson.id), singleLine = false, minLines = 5, enabled = enabled,
                    supportingText = "${lesson.content.length} de ${TrainingLimits.LESSON_CONTENT_MAX} caracteres")
                LessonVideoEditor(editor, lesson, viewModel)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BpmSecondaryButton(onClick = { viewModel.edit { TrainingDraftEdits.moveLesson(it, lesson.id, -1) } }, enabled = enabled && number > 0) { Text("Subir") }
                    BpmSecondaryButton(onClick = { viewModel.edit { TrainingDraftEdits.moveLesson(it, lesson.id, 1) } }, enabled = enabled && number < draft.lessons.lastIndex) { Text("Bajar") }
                    BpmSecondaryButton(onClick = { viewModel.newQuestion(lesson.id) }, enabled = enabled && draft.questions.size < TrainingLimits.MAX_QUESTIONS) { Text("Agregar pregunta") }
                    BpmTextButton(onClick = { viewModel.edit { TrainingDraftEdits.removeLesson(it, lesson.id) } }, enabled = enabled && !editor.uploading) { Text("Eliminar lección") }
                }
            }
        }
    }
    BpmSecondaryButton(onClick = viewModel::newLesson, enabled = enabled && draft.lessons.size < TrainingLimits.MAX_LESSONS) { Text("+ Agregar lección") }

    Text("Preguntas (${draft.questions.size} de ${TrainingLimits.MAX_QUESTIONS})", style = MaterialTheme.typography.titleLarge)
    draft.questions.forEachIndexed { number, question ->
        key(question.id) { QuestionEditor(number, question, draft, enabled, ::issue, viewModel) }
    }
    BpmSecondaryButton(onClick = { viewModel.newQuestion(null) }, enabled = enabled && draft.questions.size < TrainingLimits.MAX_QUESTIONS) { Text("+ Agregar pregunta general") }

    if (issues.isNotEmpty()) BpmCard(Modifier.fillMaxWidth()) {
        Text("Corrige antes de continuar", style = BpmType.bodyStrong, color = MaterialTheme.colorScheme.error)
        issues.distinctBy { it.message }.take(8).forEach { Text("• ${it.message}", color = MaterialTheme.colorScheme.error) }
    }
    editor.failure?.let { failure ->
        Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (failure == TrainingFailure.DRAFT_CONFLICT) BpmSecondaryButton(onClick = viewModel::reloadDraft) { Text("Recargar borrador (descarta mis cambios locales)") }
    }
    if (editor.saving || editor.publishing) LinearProgressIndicator(Modifier.fillMaxWidth())
    RaisedButton("Guardar borrador", { viewModel.save() }, Modifier.widthIn(min = 160.dp), enabled = enabled && !editor.uploading && editor.dirty)
    BpmSecondaryButton(onClick = viewModel::publish, enabled = enabled && !editor.uploading, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        Text(if (editor.dirty) "Guardar y publicar" else "Publicar curso")
    }
    BpmTextButton(onClick = viewModel::closeDraft, enabled = !editor.busy) { Text("Volver a la lista de cursos") }
}

@Composable
private fun LessonVideoEditor(editor: DraftEditorState, lesson: DraftLesson, viewModel: TrainingAuthoringViewModel) {
    val pickVideo = rememberLessonVideoPicker(TrainingLimits.CLIENT_VIDEO_MAX_BYTES) { viewModel.attachVideo(lesson.id, it) }
    val uploadingHere = editor.uploadLessonId == lesson.id
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (lesson.videoMediaId != null) "Video adjunto" else if (lesson.externalVideoId != null) "Video de YouTube" else "Sin video", style = BpmType.bodyStrong)
        if (uploadingHere && editor.uploading) {
            LinearProgressIndicator(progress = { editor.uploadProgress }, modifier = Modifier.fillMaxWidth())
            Text("Subiendo video… ${(editor.uploadProgress * 100).toInt()} %", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmTextButton(onClick = viewModel::cancelUpload) { Text("Cancelar subida") }
        } else {
            if (uploadingHere) editor.uploadFailure?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (editor.canRetryUpload) BpmSecondaryButton(onClick = viewModel::retryUpload) { Text("Reintentar subida") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmSecondaryButton(onClick = pickVideo, enabled = !editor.busy && !editor.canRetryUpload) { Text(if (lesson.videoMediaId != null) "Reemplazar video" else "Subir video MP4 o WebM") }
                if (lesson.videoMediaId != null) BpmTextButton(onClick = { viewModel.removeVideo(lesson.id) }, enabled = !editor.busy) { Text("Quitar video") }
            }
            if (editor.uploadLessonId == null) editor.uploadFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ExternalVideoField(lesson, !editor.busy, viewModel)
            Text("Hasta ${TrainingLimits.CLIENT_VIDEO_MAX_BYTES / (1024 * 1024)} MiB desde este dispositivo. El video se envía directo al almacenamiento privado y se valida antes de poder publicarse.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuestionEditor(
    number: Int,
    question: DraftQuestion,
    draft: TrainingDraft,
    enabled: Boolean,
    issue: (DraftIssueKind, String) -> String?,
    viewModel: TrainingAuthoringViewModel,
) {
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("PREGUNTA ${number + 1}" + (draft.lessons.indexOfFirst { it.id == question.lessonId }.takeIf { it >= 0 }?.let { " · LECCIÓN ${it + 1}" } ?: ""))
        BpmTextField(question.questionText, { v -> viewModel.edit { TrainingDraftEdits.updateQuestion(it, question.id, text = v) } }, "Enunciado",
            error = issue(DraftIssueKind.QUESTION_TEXT, question.id), enabled = enabled)
        Text("Opciones: marca la correcta", style = MaterialTheme.typography.labelLarge)
        question.options.forEachIndexed { index, option ->
            key(index) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RadioButton(selected = question.correctOption == index, onClick = { viewModel.edit { TrainingDraftEdits.setCorrect(it, question.id, index) } }, enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Opción ${index + 1} correcta" })
                    BpmTextField(option, { v -> viewModel.edit { TrainingDraftEdits.setOption(it, question.id, index, v) } }, "Opción ${index + 1}", modifier = Modifier.weight(1f), enabled = enabled)
                    if (question.options.size > TrainingLimits.MIN_OPTIONS) BpmTextButton(onClick = { viewModel.edit { TrainingDraftEdits.removeOption(it, question.id, index) } }, enabled = enabled) { Text("Quitar") }
                }
            }
        }
        (issue(DraftIssueKind.OPTION_COUNT, question.id) ?: issue(DraftIssueKind.OPTION_TEXT, question.id) ?: issue(DraftIssueKind.CORRECT_OPTION, question.id))?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        BpmSecondaryButton(onClick = { viewModel.edit { TrainingDraftEdits.addOption(it, question.id) } }, enabled = enabled && question.options.size < TrainingLimits.MAX_OPTIONS) { Text("+ Agregar opción") }
        BpmTextField(question.explanation, { v -> viewModel.edit { TrainingDraftEdits.updateQuestion(it, question.id, explanation = v) } }, "Explicación que verá quien responda",
            error = issue(DraftIssueKind.EXPLANATION, question.id), singleLine = false, minLines = 2, enabled = enabled)
        BpmTextButton(onClick = { viewModel.edit { TrainingDraftEdits.removeQuestion(it, question.id) } }, enabled = enabled) { Text("Eliminar pregunta") }
    }
}

/**
 * Alternativa al video subido: pegar un enlace de YouTube. Son excluyentes: escribir un enlace
 * retira el video subido y subir un video retira el enlace. Solo se guarda el identificador.
 */
@Composable
private fun ExternalVideoField(lesson: DraftLesson, enabled: Boolean, viewModel: TrainingAuthoringViewModel) {
    var text by androidx.compose.runtime.remember(lesson.id, lesson.videoMediaId) { androidx.compose.runtime.mutableStateOf(lesson.externalVideoId?.let { YouTubeLinks.watchUrl(it) }.orEmpty()) }
    val invalid = text.isNotBlank() && YouTubeLinks.idFromUrl(text) == null
    BpmTextField(text, { value ->
        text = value
        viewModel.edit { TrainingDraftEdits.setExternalVideo(it, lesson.id, if (value.isBlank()) null else YouTubeLinks.idFromUrl(value)) }
    }, "Enlace de YouTube (en lugar de subir un video)", enabled = enabled,
        error = if (invalid) "Pega un enlace válido de YouTube (youtube.com/watch, youtu.be, shorts o embed)." else null,
        supportingText = "Se reproduce en línea: quien aprende necesita conexión. Quita el video subido si pegas un enlace.")
}
