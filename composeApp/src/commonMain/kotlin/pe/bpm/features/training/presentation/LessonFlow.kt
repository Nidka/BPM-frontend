package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.offline.LocalOnline
import pe.bpm.features.training.domain.*

/** Clases de ancho con espacio para un panel al lado del contenido. */
internal val sideBySideClass: Boolean
    @Composable get() = currentWindowClass == BpmWindowClass.Expanded || currentWindowClass == BpmWindowClass.Large

/**
 * Estado de cada lección en el temario lateral de una lección abierta: la abierta es la actual, las anteriores
 * y las que [lessonStepStates] da por hechas figuran como hechas y el resto como pendientes.
 */
internal fun outlineStates(course: TrainingCourse, openIndex: Int): List<BpmStepState> {
    val approx = lessonStepStates(course)
    return course.lessons.indices.map { i ->
        when {
            i == openIndex -> BpmStepState.Current
            i < openIndex || approx.getOrNull(i) == BpmStepState.Done -> BpmStepState.Done
            else -> BpmStepState.Pending
        }
    }
}

/**
 * Lección en modo enfoque: avance por segmentos, título, video (sin tocar los modificadores del reproductor),
 * texto, «Comprueba lo aprendido» (modo estudio, solo si la lección tiene preguntas) y enlace a la evaluación de
 * la lección. Anterior/Siguiente van en [BpmActionBar]; desde la clase expandida el temario queda a la derecha.
 */
@Composable
internal fun LessonViewer(state: TrainingState, course: TrainingCourse, lesson: TrainingLesson, viewModel: TrainingViewModel) {
    val index = course.lessons.indexOfFirst { it.id == lesson.id }
    val body: @Composable ColumnScope.() -> Unit = { LessonBody(state, course, lesson, index, viewModel) }
    if (sideBySideClass) BpmSupportPaneLayout(
        support = {
            BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                Text(course.course.title, style = MaterialTheme.typography.titleMedium)
                val steps = course.lessons.mapIndexed { i, l -> BpmStep(l.id, l.title, state = outlineStates(course, index)[i]) }
                BpmStepList(steps, dense = true, onStep = { viewModel.selectLesson(it.id) })
            }
        },
        main = body,
    ) else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section), content = body)
}

@Composable
private fun ColumnScope.LessonBody(state: TrainingState, course: TrainingCourse, lesson: TrainingLesson, index: Int, viewModel: TrainingViewModel) {
    val total = course.lessons.size
    val compact = currentWindowClass == BpmWindowClass.Compact
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        BpmSegmentedProgress(total, index, "Lección ${index + 1} de $total", current = index)
        Eyebrow("LECCIÓN ${index + 1} DE $total")
        Text(lesson.title, style = MaterialTheme.typography.headlineMedium)
    }
    if (state.progressNotSaved) Text("No pudimos guardar tu avance. Puedes seguir leyendo; se guardará al abrir otra lección.", color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    lesson.externalVideo?.let { external ->
        // Video de YouTube: se incrusta sin cookies de seguimiento y solo funciona con conexión.
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        Text("Requiere conexión", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExternalLessonVideo(external.embedUrl, external.watchUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        BpmTextButton(onClick = { runCatching { uriHandler.openUri(external.watchUrl) } }) { Text("Abrir video en el navegador") }
    }
    if (lesson.video != null) {
        when (val video = state.video) {
            VideoState.Idle -> {
                val saved = state.downloads.downloaded(lesson.video.mediaId)
                val online = LocalOnline.current
                BpmSecondaryButton(onClick = viewModel::loadVideo, enabled = online || saved, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                    Text(if (saved) "Ver video (guardado en este dispositivo)" else "Ver video de la lección")
                }
                if (!online && !saved) Text("Este video necesita conexión. Descárgalo desde el curso para verlo sin conexión; mientras tanto puedes leer el texto de la lección.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VideoState.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparando el video…") }
            is VideoState.Ready -> {
                LessonVideoPlayer(video.ticket.url, video.ticket.mimeType, viewModel::onVideoError, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                BpmTextButton(onClick = viewModel::closeVideo) { Text("Ocultar video") }
            }
            is VideoState.Failed -> BpmCard(Modifier.fillMaxWidth()) {
                Text(trainingFailureMessage(video.reason), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmSecondaryButton(onClick = viewModel::loadVideo) { Text("Reintentar video") }
            }
        }
    }
    // El texto de la lección hace de transcripción accesible del video y de lectura sin video.
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow(if (lesson.video != null || lesson.externalVideo != null) "TRANSCRIPCIÓN Y TEXTO" else "CONTENIDO")
        Text(lesson.content.ifBlank { "Esta lección no tiene texto." })
    }
    val questionCount = course.questions.count { it.lessonId == lesson.id }
    if (questionCount > 0) {
        BpmCard(Modifier.fillMaxWidth()) {
            Eyebrow("COMPRUEBA LO APRENDIDO · NO SE CALIFICA")
            Text(if (questionCount == 1) "1 pregunta de esta lección" else "$questionCount preguntas de esta lección", style = BpmType.bodyStrong)
            BpmSecondaryButton(onClick = { viewModel.startStudy(lesson.id) }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Practicar esta lección") }
        }
        if (state.canPersist) BpmTextButton(onClick = { viewModel.startModuleEvaluation(lesson.id) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Evaluar esta lección (suma XP)")
        }
    }
    BpmActionBar {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid), verticalAlignment = Alignment.CenterVertically) {
            BpmSecondaryButton(onClick = viewModel::previousLesson, enabled = index > 0,
                modifier = Modifier.semantics { contentDescription = "Lección anterior" }.then(if (compact) Modifier else Modifier.widthIn(min = 120.dp))) {
                BpmIcon(BpmSymbol.Back, modifier = Modifier.size(20.dp))
                if (!compact) { Spacer(Modifier.width(BpmSpacing.sm)); Text("Anterior") }
            }
            val last = index >= course.lessons.lastIndex
            RaisedButton(if (last) "Terminar lecciones" else "Siguiente lección", { if (last) { viewModel.back() } else viewModel.nextLesson() },
                if (compact) Modifier.weight(1f) else Modifier.widthIn(min = 200.dp))
        }
    }
}

/**
 * Modo estudio: avance por segmentos, una pregunta con opciones y la retroalimentación al instante, sin puntaje. Las
 * acciones tras responder («Reintentar», «Siguiente pregunta») van en [BpmActionBar].
 */
@Composable
internal fun StudyPractice(study: StudyState, viewModel: TrainingViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        val total = study.questions.size
        val position = study.index.coerceAtMost(total)
        BpmSegmentedProgress(total, position, if (study.finished) "Práctica terminada: $total preguntas" else "Pregunta ${position + 1} de $total",
            current = if (study.finished) null else position)
        Eyebrow("MODO ESTUDIO · SIN CALIFICACIÓN")
    }
    if (study.finished) {
        BpmCard(Modifier.fillMaxWidth()) {
            Text("Terminaste la práctica", style = MaterialTheme.typography.titleLarge)
            Text("Repasar no genera puntaje ni constancia. Cuando quieras que cuente, usa el modo evaluación.")
            RaisedButton("Practicar de nuevo", viewModel::restartStudy, Modifier.bpmPrimaryButtonWidth())
            BpmSecondaryButton(onClick = { viewModel.back() }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Volver al curso") }
        }
        return
    }
    val question = study.current ?: return
    Text("Pregunta ${study.index + 1} de ${study.questions.size}", style = MaterialTheme.typography.labelLarge)
    Text(question.questionText, style = MaterialTheme.typography.titleLarge)
    val answered = study.feedback != null
    Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
        question.options.forEachIndexed { option, text ->
            val feedback = study.feedback
            BpmChoiceRow(text, study.selected == option, enabled = !answered && !study.checking,
                supportingText = when {
                    feedback == null -> null
                    option == feedback.correctOption -> "Respuesta correcta"
                    option == study.selected -> "Tu respuesta"
                    else -> null
                }) { viewModel.answerStudy(option) }
        }
    }
    if (study.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
    study.failure?.let { failure ->
        Text(if (failure == TrainingFailure.CONNECTION) "Necesita conexión para revisar la respuesta. Puedes seguir leyendo las lecciones o pasar a la siguiente pregunta."
        else trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (failure == TrainingFailure.CONNECTION) BpmSecondaryButton(onClick = viewModel::skipStudyQuestion, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
            Text(if (study.index == study.questions.lastIndex) "Terminar práctica sin revisar" else "Seguir sin revisar")
        }
    }
    study.feedback?.let { feedback ->
        BpmCard(Modifier.fillMaxWidth(), tint = if (feedback.correct) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (feedback.correct) "¡Correcto!" else "Todavía no", style = MaterialTheme.typography.titleMedium)
                if (!feedback.correct) Text("La respuesta correcta es: ${question.options.getOrNull(feedback.correctOption).orEmpty()}")
                if (feedback.explanation.isNotBlank()) Text(feedback.explanation)
            }
        }
        BpmActionBar {
            val nextLabel = if (study.index == study.questions.lastIndex) "Terminar práctica" else "Siguiente pregunta"
            if (currentWindowClass == BpmWindowClass.Compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                RaisedButton(nextLabel, viewModel::nextStudyQuestion, Modifier.fillMaxWidth())
                BpmSecondaryButton(onClick = viewModel::retryStudyQuestion, modifier = Modifier.fillMaxWidth()) { Text("Reintentar esta pregunta") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid), verticalAlignment = Alignment.CenterVertically) {
                BpmSecondaryButton(onClick = viewModel::retryStudyQuestion) { Text("Reintentar esta pregunta") }
                RaisedButton(nextLabel, viewModel::nextStudyQuestion, Modifier.widthIn(min = 200.dp))
            }
        }
    }
    BpmTextButton(onClick = { viewModel.back() }) { Text("Salir de la práctica") }
}
