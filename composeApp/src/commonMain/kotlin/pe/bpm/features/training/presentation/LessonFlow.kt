package pe.bpm.features.training.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.offline.LocalOnline
import pe.bpm.features.training.domain.*

/** Clases de ancho con espacio para un panel al lado del contenido. */
internal val sideBySideClass: Boolean
    @Composable get() = currentWindowClass == BpmWindowClass.Expanded || currentWindowClass == BpmWindowClass.Large

/**
 * Estado de cada lección en el temario lateral de una lección abierta: la abierta es la actual. Con el avance real del
 * servidor las hechas son las de `completedLessonIds` (o todas si el curso está completado) y el resto está pendiente;
 * sin él, las anteriores y las que [lessonStepStates] da por hechas figuran como hechas.
 */
internal fun outlineStates(course: TrainingCourse, openIndex: Int): List<BpmStepState> {
    if (hasLessonProgress(course)) {
        val done = course.progress.completedLessonIds.toSet()
        val all = course.progress.status == ProgressStatus.COMPLETED
        return course.lessons.mapIndexed { i, lesson ->
            when {
                i == openIndex -> BpmStepState.Current
                all || lesson.id in done -> BpmStepState.Done
                else -> BpmStepState.Pending
            }
        }
    }
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
        // Regla G1: sin panel lateral la posición es «Lección 3 de 5» con su barra; con panel, el temario lateral la indica.
        if (!sideBySideClass) PositionBar("Lección ${index + 1} de $total", total, index)
        Text(lesson.title, style = MaterialTheme.typography.headlineMedium)
    }
    if (state.progressNotSaved) Text("No pudimos guardar tu avance. Puedes seguir leyendo; se guardará al abrir otra lección.", color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    LessonVideoSection(state, lesson, viewModel)
    if (lesson.keyPoints.isNotEmpty()) KeyPointsCard(lesson.keyPoints)
    // El texto de la lección hace de transcripción accesible del video y de lectura sin video.
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow(if (lesson.video != null || lesson.externalVideo != null) "TRANSCRIPCIÓN Y TEXTO" else "CONTENIDO")
        BpmListenButton(lesson.title + ". " + lesson.content)
        Text(lesson.content.ifBlank { "Esta lección no tiene texto." })
    }
    val questionCount = course.questions.count { it.lessonId == lesson.id }
    if (questionCount > 0) BpmCard(Modifier.fillMaxWidth()) {
        Text("Comprueba lo aprendido", style = MaterialTheme.typography.titleMedium)
        Text(if (questionCount == 1) "1 pregunta de esta lección" else "$questionCount preguntas de esta lección", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val practice: @Composable () -> Unit = { BpmChunkyButton("Practicar · no califica", { viewModel.startStudy(lesson.id) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White) }
        val evaluate: @Composable () -> Unit = { BpmTextButton(onClick = { viewModel.startModuleEvaluation(lesson.id) }, modifier = Modifier.fillMaxWidth()) { Text("Evaluar lección · suma puntos") } }
        // Con banco oficial la práctica de la unidad reemplaza a la evaluación por lección.
        val canEvaluate = state.canPersist && course.bank == null
        if (compact) { practice(); if (canEvaluate) evaluate() }
        else BpmInlineActions(secondary = if (canEvaluate) evaluate else null, primary = practice)
    }
    BpmActionBar {
        val last = index >= course.lessons.lastIndex
        BpmInlineActions(compactSecondaryFixed = true,
            secondary = { BackButton("Anterior", "Lección anterior", viewModel::previousLesson, enabled = index > 0) },
            primary = { BpmChunkyButton(if (last) "Terminar lecciones" else "Siguiente lección", { if (last) { viewModel.finishLessons() } else viewModel.nextLesson() }, Modifier.fillMaxWidth()) })
    }
}

/**
 * Video de la lección (privado o de YouTube) con su estado de carga, error y transcripción. Lo usan la lección
 * clásica y el paso de video de la lección del personal.
 */
@Composable
internal fun ColumnScope.LessonVideoSection(state: TrainingState, lesson: TrainingLesson, viewModel: TrainingViewModel) {
    lesson.externalVideo?.let { external ->
        // Video de YouTube: se incrusta sin cookies de seguimiento y solo funciona con conexión.
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        Text("Requiere conexión", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExternalLessonVideo(external.embedUrl, external.watchUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        BpmTextButton(onClick = { runCatching { uriHandler.openUri(external.watchUrl) } }) { Text("Abrir video en el navegador") }
    }
    // «Solo audio y texto»: sin el video, con la transcripción abierta y un botón para escucharla (baja visión, poco internet).
    var audioOnly by androidx.compose.runtime.saveable.rememberSaveable(lesson.id) { mutableStateOf(false) }
    val settings = pe.bpm.core.designsystem.LocalBpmFeedbackSettings.current
    if (lesson.video != null) {
        BpmTextButton(onClick = { audioOnly = !audioOnly }, modifier = Modifier.heightIn(min = BpmSpacing.touch)
            .semantics { stateDescription = if (audioOnly) "Activado" else "Desactivado" }) {
            Text(if (audioOnly) "Ver el video" else "Solo audio y texto")
        }
    }
    if (lesson.video != null && !audioOnly) {
        when (val video = state.video) {
            VideoState.Idle -> {
                val saved = state.downloads.downloaded(lesson.video.mediaId)
                val online = LocalOnline.current
                VideoPlayCard(if (saved) "Guardado en este dispositivo" else null, enabled = online || saved, onClick = viewModel::loadVideo)
                if (!online && !saved) Text("Este video necesita conexión. Descárgalo desde el curso para verlo sin conexión; mientras tanto puedes leer el texto de la lección.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VideoState.Loading -> BpmSkeletonVideo()
            is VideoState.Ready -> {
                LessonVideoPlayer(video.ticket.url, video.ticket.mimeType, viewModel::onVideoError, Modifier.fillMaxWidth().aspectRatio(16f / 9f), if (settings.captions) lesson.captionsVtt else "")
                BpmTextButton(onClick = viewModel::closeVideo) { Text("Ocultar video") }
            }
            is VideoState.Failed -> BpmCard(Modifier.fillMaxWidth()) {
                Text(trainingFailureMessage(video.reason), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmChunkyButton("Reintentar video", viewModel::loadVideo, tone = BpmChunkyTone.White)
            }
        }
    }
    if (lesson.video != null) VideoTranscript(lesson.id, lesson.captionsVtt, lesson.content, audioOnly || settings.transcript)
}

/** Posición en una secuencia: rótulo («Lección 3 de 5») y barra segmentada fina; juntos son un solo indicador (regla G1). */
@Composable
internal fun PositionBar(label: String, total: Int, index: Int, finished: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
        Text(label, Modifier.clearAndSetSemantics { }, style = BpmType.bodySmallStrong, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BpmSegmentedProgress(total, if (finished) total else index, label, current = if (finished) null else index)
    }
}

/** Tarjeta 16:9 con el icono de reproducir que carga el video de la lección; toda la tarjeta es el botón. */
@Composable
private fun VideoPlayCard(caption: String?, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(scheme.inverseSurface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Ver video de la lección" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(if (enabled) scheme.inverseOnSurface else scheme.outline), contentAlignment = Alignment.Center) {
            BpmIcon(BpmSymbol.Play, tint = scheme.primary, modifier = Modifier.size(28.dp))
        }
        Text(caption ?: "Video de la lección", Modifier.align(Alignment.BottomStart).padding(BpmSpacing.md).clearAndSetSemantics { },
            style = BpmType.bodySmallStrong, color = scheme.inverseOnSurface)
    }
}

/**
 * Modo estudio: avance por segmentos, una pregunta con opciones y la retroalimentación al instante, sin puntaje. Las
 * acciones tras responder («Reintentar», «Siguiente pregunta») van en [BpmActionBar].
 */
@Composable
internal fun StudyPractice(study: StudyState, viewModel: TrainingViewModel) {
    val total = study.questions.size
    val position = study.index.coerceAtMost(total)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        Eyebrow("MODO ESTUDIO · SIN CALIFICACIÓN")
        // Regla G1: un solo indicador, «Pregunta 2 de 5» con su barra.
        PositionBar(if (study.finished) "Práctica terminada · $total preguntas" else "Pregunta ${position + 1} de $total", total, position, finished = study.finished)
    }
    if (study.finished) {
        BpmCard(Modifier.fillMaxWidth()) {
            Text("Terminaste la práctica", style = MaterialTheme.typography.titleLarge)
            Text("Repasar no genera puntaje ni constancia. Cuando quieras que cuente, usa el modo evaluación.")
        }
        BpmActionBar {
            BpmInlineActions(compactSecondaryFixed = false,
                secondary = { BpmChunkyButton("Volver al curso", { viewModel.back() }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White) },
                primary = { BpmChunkyButton("Practicar de nuevo", viewModel::restartStudy, Modifier.fillMaxWidth()) })
        }
        return
    }
    val question = study.current ?: return
    Text(question.questionText, style = MaterialTheme.typography.titleLarge)
    val answered = study.feedback != null
    val shown = study.shownOptions(question)
    Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
        shown.forEachIndexed { option, text ->
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
        if (failure == TrainingFailure.CONNECTION) BpmInlineActions(primary = {
            BpmChunkyButton(if (study.index == study.questions.lastIndex) "Terminar práctica sin revisar" else "Seguir sin revisar", viewModel::skipStudyQuestion, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        })
    }
    study.feedback?.let { feedback ->
        BpmCard(Modifier.fillMaxWidth(), tint = if (feedback.correct) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (feedback.correct) "¡Correcto!" else "Todavía no", style = MaterialTheme.typography.titleMedium)
                if (!feedback.correct) Text("La respuesta correcta es: ${shown.getOrNull(feedback.correctOption).orEmpty()}")
                if (feedback.explanation.isNotBlank()) Text(feedback.explanation)
                sourceLine(feedback.sources).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (feedback.xpEarned > 0) BpmStatusChip("+${feedback.xpEarned} puntos", BpmTone.Info, symbol = BpmSymbol.Award)
                if (feedback.itemCode != null) BpmListenButton(listOf(feedback.explanation, sourceLine(feedback.sources)).filter { it.isNotBlank() }.joinToString(". "), label = "Escuchar la explicación")
            }
        }
        BpmActionBar {
            val nextLabel = if (study.index == study.questions.lastIndex) "Terminar práctica" else "Siguiente pregunta"
            // En compacta los dos rótulos largos no caben lado a lado: el principal arriba y «Reintentar» debajo.
            if (currentWindowClass == BpmWindowClass.Compact) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                BpmChunkyButton(nextLabel, viewModel::nextStudyQuestion, Modifier.fillMaxWidth())
                BpmChunkyButton("Reintentar esta pregunta", viewModel::retryStudyQuestion, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            } else BpmInlineActions(
                secondary = { BpmChunkyButton("Reintentar esta pregunta", viewModel::retryStudyQuestion, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White) },
                primary = { BpmChunkyButton(nextLabel, viewModel::nextStudyQuestion, Modifier.fillMaxWidth()) })
        }
    }
}

/**
 * «Lo esencial»: las ideas clave de la lección en una tarjeta de superficie suave, antes del texto completo.
 * Cada idea es una viñeta; el lector de pantalla las anuncia como una lista corta.
 */
@Composable
internal fun KeyPointsCard(points: List<String>, modifier: Modifier = Modifier) {
    BpmCard(modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("LO ESENCIAL")
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            points.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
                    Text("•", style = BpmType.bodyStrong, color = MaterialTheme.colorScheme.primary)
                    Text(point, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Transcripción del video a partir de sus subtítulos (RF-45): plegada para no alargar la lección; se abre con un
 * botón de texto accesible por teclado y lector de pantalla.
 */
@Composable
private fun VideoTranscript(lessonId: String, captionsVtt: String, lessonText: String, startOpen: Boolean) {
    // La transcripción sale de los subtítulos; sin ellos, el texto de la lección hace de transcripción.
    val transcript = remember(captionsVtt, lessonText) { WebVtt.transcript(WebVtt.parse(captionsVtt)).ifBlank { lessonText } }
    if (transcript.isBlank()) return
    var open by androidx.compose.runtime.saveable.rememberSaveable(lessonId, startOpen) { mutableStateOf(startOpen) }
    BpmCard(Modifier.fillMaxWidth()) {
        BpmTextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = BpmSpacing.touch)
            .semantics { stateDescription = if (open) "Abierta" else "Cerrada" }) {
            Text(if (open) "Ocultar transcripción del video" else "Ver transcripción del video")
        }
        if (open) {
            BpmListenButton(transcript, label = "Escuchar resumen")
            Text(transcript, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
