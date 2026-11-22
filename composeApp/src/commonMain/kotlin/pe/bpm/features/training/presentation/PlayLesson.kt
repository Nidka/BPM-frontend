package pe.bpm.features.training.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_achievement

/**
 * Lección del personal en pasos (modo enfoque): arriba la X y la barra de avance (pasos de la lección más las
 * preguntas de su práctica), en el centro una idea a la vez y, abajo, «Continuar». Tras el último paso abre la práctica
 * ([PlayPractice]) si la lección tiene preguntas y, si no, la celebración de fin de lección.
 */
@Composable
internal fun PlayLesson(state: TrainingState, course: TrainingCourse, lesson: TrainingLesson, viewModel: TrainingViewModel, worker: WorkerCourseContext) {
    val steps = remember(lesson) { lessonSteps(lesson) }
    val questions = course.questions.count { it.lessonId == lesson.id }
    var step by rememberSaveable(lesson.id) { mutableIntStateOf(0) }
    if (step >= steps.size) {
        PlayLessonDone(course, lesson, null, viewModel, worker, onContinue = worker.onClose)
        return
    }
    val advance: () -> Unit = {
        if (step == steps.lastIndex && questions > 0) viewModel.startStudy(lesson.id, repeatMissed = true) else step += 1
    }
    val current = steps[step]
    BpmPlayStage(
        Modifier.bpmPlayKeys(0, {}, advance, focusKey = step),
        progress = sequenceProgress(step + 1, steps.size + questions), onClose = worker.onClose, closeDescription = "Salir de la lección",
        progressDescription = "Paso ${step + 1} de ${steps.size + questions}", centered = current is LessonStep.Idea, scrollKey = step,
        footer = { BpmChunkyButton("Continuar", advance, Modifier.fillMaxWidth()) },
    ) {
        state.notice?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.progressNotSaved) Text("No pudimos guardar tu avance. Puedes seguir; se guardará al terminar.", style = BpmPlayType.sub, color = BpmPlayColors.error.shadow,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        when (current) {
            LessonStep.Video -> {
                Text("Video", style = BpmPlayType.label, color = BpmPlayColors.action.face)
                Text(lesson.title, style = BpmPlayType.h1, color = BpmPlayColors.ink)
                LessonVideoSection(state, lesson, viewModel)
            }
            is LessonStep.Idea -> IdeaStep(current)
            LessonStep.Reading -> {
                Text("Lección ${(course.lessons.indexOfFirst { it.id == lesson.id } + 1)} de ${course.lessons.size}", style = BpmPlayType.label, color = BpmPlayColors.action.face)
                Text(lesson.title, style = BpmPlayType.h1, color = BpmPlayColors.ink)
                Text(lesson.content.ifBlank { "Esta lección no tiene texto." }, style = BpmPlayType.text, color = BpmPlayColors.ink, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Una idea de «Lo esencial»: antetítulo, icono grande en cuadro dorado suave y la frase. */
@Composable
private fun ColumnScope.IdeaStep(idea: LessonStep.Idea) {
    Text("Lo esencial · ${idea.number} de ${idea.total}", style = BpmPlayType.label, color = BpmPlayColors.action.face)
    Box(Modifier.size(110.dp).background(BpmPlayColors.gold.soft, RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) {
        BpmIcon(ideaSymbol(idea.text, idea.number - 1), tint = BpmPlayColors.gold.shadow, modifier = Modifier.size(58.dp))
    }
    Text(idea.text, style = BpmPlayType.h1, color = BpmPlayColors.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

/** Opción elegida al abrir cada pregunta; solo las capturas de pantalla lo cambian para mostrar «Comprobar» activo. */
internal val LocalInitialPick = androidx.compose.runtime.compositionLocalOf<Int?> { null }

/**
 * Práctica de la lección del personal: una pregunta con opciones numeradas, «Comprobar» (deshabilitado hasta elegir) y
 * la franja de respuesta que sube con «¡Muy bien!» o «Casi». La respuesta la verifica el servidor (modo estudio); las
 * falladas se repiten una vez al final. Al terminar muestra la celebración. Atajos web: 1–9 eligen, Enter comprueba o continúa.
 */
@Composable
internal fun PlayPractice(state: TrainingState, course: TrainingCourse, study: StudyState, viewModel: TrainingViewModel, worker: WorkerCourseContext) {
    val lesson = study.lessonId?.let { id -> course.lessons.firstOrNull { it.id == id } }
    if (study.finished) {
        PlayLessonDone(course, lesson, study, viewModel, worker, onContinue = if (lesson != null) worker.onClose else ({ viewModel.back(); Unit }))
        return
    }
    val question = study.current ?: return
    val initialPick = LocalInitialPick.current
    var picked by remember(study.index, question.id) { mutableStateOf(initialPick) }
    val feedback = study.feedback
    val lead = lesson?.let { lessonSteps(it).size } ?: 0
    val check: () -> Unit = { picked?.let { if (!study.checking && feedback == null) viewModel.answerStudy(it) } }
    val advance: () -> Unit = { viewModel.nextStudyQuestion() }
    val confirm: () -> Unit = { if (feedback == null) check() else advance() }
    val repeating = study.index >= study.originalCount
    BpmPlayStage(
        Modifier.bpmPlayKeys(question.options.size, { if (feedback == null && !study.checking) picked = it }, confirm, focusKey = study.index to (feedback != null)),
        progress = sequenceProgress(lead + study.index + 1, lead + study.questions.size), onClose = worker.onClose, closeDescription = "Salir de la práctica",
        progressDescription = "Pregunta ${study.index + 1} de ${study.questions.size}", scrollKey = study.index,
        footerColor = when { feedback == null -> androidx.compose.ui.graphics.Color.White; feedback.correct -> BpmPlayColors.correct.soft; else -> BpmPlayColors.error.soft },
        footerPadded = feedback == null,
        footer = {
            if (feedback == null) {
                study.failure?.let { failure ->
                    Text(if (failure == TrainingFailure.CONNECTION) "Necesitas conexión para comprobar la respuesta." else trainingFailureMessage(failure),
                        style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (failure == TrainingFailure.CONNECTION) BpmChunkyButton(
                        if (study.index == study.questions.lastIndex) "Terminar sin comprobar" else "Seguir sin comprobar",
                        viewModel::skipStudyQuestion, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                }
                BpmChunkyButton(if (study.checking) "Comprobando…" else "Comprobar", check, Modifier.fillMaxWidth(), enabled = picked != null && !study.checking)
            } else BpmFeedbackSheet(
                correct = feedback.correct, title = if (feedback.correct) "¡Muy bien!" else "Casi",
                explanation = feedback.explanation.ifBlank { if (feedback.correct) "Respuesta correcta." else "La respuesta correcta es: ${question.options.getOrNull(feedback.correctOption).orEmpty()}." },
                actionLabel = if (feedback.correct) "Continuar" else "Entendido", onAction = advance)
        },
    ) {
        // Sin conexión la respuesta no se puede comprobar (la corrige el servidor): se avisa antes de que la persona lo intente.
        if (!pe.bpm.core.offline.LocalOnline.current && feedback == null) BpmPlayNotice("Sin conexión: no podemos comprobar tu respuesta ahora. Puedes seguir sin comprobar o volver más tarde.")
        Text(if (repeating) "Repasemos esta" else "Elige la respuesta", style = BpmPlayType.label, color = BpmPlayColors.action.face)
        Text(question.questionText, style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            question.options.forEachIndexed { option, text ->
                val tile = when {
                    feedback != null && option == feedback.correctOption -> BpmOptionState.Correct
                    feedback != null && option == study.selected -> BpmOptionState.Wrong
                    feedback == null && option == picked -> BpmOptionState.Selected
                    else -> BpmOptionState.Idle
                }
                BpmOptionTile(text, (option + 1).toString(), tile, { picked = option }, Modifier.fillMaxWidth(), enabled = feedback == null && !study.checking)
            }
        }
    }
}

/**
 * Fin de lección (o de la práctica del curso): la celebración con fichas de lecciones hechas, racha y aciertos de la
 * práctica, y «Continuar», que vuelve al camino. Al mostrarse marca la lección como completada (lógica existente) y
 * pide refrescar la racha. [study] es nulo cuando la lección no tiene preguntas; [lesson] es nulo en la práctica libre.
 */
@Composable
private fun PlayLessonDone(course: TrainingCourse, lesson: TrainingLesson?, study: StudyState?, viewModel: TrainingViewModel, worker: WorkerCourseContext, onContinue: () -> Unit) {
    LaunchedEffect(lesson?.id) {
        if (lesson != null) { viewModel.completeLesson(lesson.id); delay(800); worker.onLessonCompleted() }
    }
    val done = lesson?.let { (course.progress.completedLessonIds + it.id).distinct().size }
    val stats = buildList {
        if (done != null) add(BpmStatTileSpec("Lecciones", "$done/${course.lessons.size}", BpmPlayTone.Gold, BpmSymbol.Star))
        worker.streakDays?.let { add(BpmStatTileSpec("Racha", it.toString(), BpmPlayTone.Streak, BpmSymbol.Flame)) }
        study?.accuracyPercent?.let { add(BpmStatTileSpec("Aciertos", "$it%", BpmPlayTone.Correct, BpmSymbol.Target)) }
    }
    BpmPlayStage(
        Modifier.bpmPlayKeys(0, {}, onContinue, focusKey = lesson?.id), centered = true,
        footer = { BpmChunkyButton("Continuar", onContinue, Modifier.fillMaxWidth()) },
    ) {
        BpmCelebration(Res.drawable.mascot_achievement, if (lesson != null) "¡Lección completa!" else "¡Práctica terminada!",
            lesson?.title ?: "Practicar no califica ni suma puntos", stats, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
