package pe.bpm.features.training.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_achievement
import pe.bpm.resources.mascot_learning

/**
 * Evaluación del personal (modo enfoque): la pregunta con la barra dorada y «3/8» como único indicador, la revisión
 * antes de enviar y el resultado. Reutiliza el estado y las acciones de [TrainingViewModel] sin cambios: no hay
 * respuesta inmediata, se puede volver atrás y el envío, el reintento y la cola sin conexión son los de siempre.
 */
@Composable
internal fun PlayEvaluation(state: TrainingState, course: TrainingCourse, evaluation: EvaluationState, viewModel: TrainingViewModel,
    worker: WorkerCourseContext, onOpenCourse: ((String) -> Unit)? = null) {
    val questions = state.evaluationQuestions
    val outcome = evaluation.outcome
    when {
        outcome != null -> PlayResult(course, outcome, viewModel, worker, onOpenCourse)
        evaluation.queued -> BpmPlayStage(Modifier.bpmPlayKeys(0, {}, worker.onClose, focusKey = "cola"), centered = true,
            footer = { BpmChunkyButton("Volver al camino", worker.onClose, Modifier.fillMaxWidth()) }) {
            Image(painterResource(Res.drawable.mascot_learning), contentDescription = null, modifier = Modifier.width(140.dp))
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Evaluación enviada", style = BpmPlayType.h1, color = BpmPlayColors.ink, textAlign = TextAlign.Center)
                Text("Tus respuestas están guardadas en este dispositivo. Se calificarán cuando recuperes la conexión y verás el resultado en el curso.",
                    style = BpmPlayType.text, color = BpmPlayColors.muted, textAlign = TextAlign.Center)
            }
        }
        // Curso con banco oficial: el servidor sortea las preguntas; sin sorteo no hay evaluación (necesita conexión).
        evaluation.starting -> BpmPlayStage(Modifier.bpmPlayKeys(0, {}, {}, focusKey = "sorteo"), onClose = worker.onClose, closeDescription = "Salir de la evaluación", centered = true) {
            Image(painterResource(Res.drawable.mascot_learning), contentDescription = null, modifier = Modifier.width(140.dp))
            Text("Preparando tu evaluación…", style = BpmPlayType.h1, color = BpmPlayColors.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        evaluation.bank && evaluation.drawn == null -> BpmPlayStage(Modifier.bpmPlayKeys(0, {}, viewModel::retryDraw, focusKey = "sorteo-fallo"), onClose = worker.onClose, centered = true,
            footer = {
                BpmChunkyButton("Reintentar", viewModel::retryDraw, Modifier.fillMaxWidth())
                BpmChunkyButton("Salir", worker.onClose, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }) {
            Text(drawFailureMessage(evaluation.failure), style = BpmPlayType.text, color = BpmPlayColors.error.shadow, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        }
        questions.isEmpty() -> BpmPlayStage(Modifier.bpmPlayKeys(0, {}, worker.onClose, focusKey = "vacia"), onClose = worker.onClose, centered = true,
            footer = { BpmChunkyButton("Salir", worker.onClose, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White) }) {
            Text("Esta evaluación todavía no tiene preguntas.", style = BpmPlayType.text, color = BpmPlayColors.muted, textAlign = TextAlign.Center)
        }
        evaluation.reviewing -> PlayReview(course, evaluation, questions, viewModel, worker)
        else -> PlayEvaluationQuestion(course, evaluation, questions, viewModel, worker)
    }
}

/** Etiqueta dorada de la evaluación (`.chip.y`): trofeo y tipo de evaluación con su mínimo. */
@Composable
private fun EvaluationChip(text: String) {
    Row(Modifier.background(BpmPlayColors.gold.soft, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BpmIcon(BpmSymbol.Trophy, tint = BpmPlayColors.gold.shadow, modifier = Modifier.size(16.dp))
        Text(text.uppercase(), style = BpmPlayType.statLabel, color = BpmPlayColors.gold.shadow)
    }
}

@Composable
private fun PlayEvaluationQuestion(course: TrainingCourse, evaluation: EvaluationState, questions: List<TrainingQuestion>, viewModel: TrainingViewModel, worker: WorkerCourseContext) {
    val index = evaluation.index.coerceIn(0, questions.lastIndex)
    val question = questions[index]
    val picked = evaluation.answers[question.id]
    val locked = evaluation.pending != null || evaluation.submitting
    val last = index >= questions.lastIndex
    val next: () -> Unit = { if (picked != null) { if (last) viewModel.openReview() else viewModel.nextQuestion() } }
    BpmPlayStage(
        Modifier.bpmPlayKeys(question.options.size, { if (!locked) viewModel.answerEvaluation(question.id, it) }, next, focusKey = index),
        progress = sequenceProgress(index + 1, questions.size), progressTone = BpmPlayTone.Gold, counter = "${index + 1}/${questions.size}",
        progressDescription = "Pregunta ${index + 1} de ${questions.size}", onClose = worker.onClose, closeDescription = "Salir de la evaluación", scrollKey = index,
        footer = {
            BpmChunkyButton(if (last) "Revisar respuestas" else "Siguiente", next, Modifier.fillMaxWidth(), enabled = picked != null && !locked)
            if (index > 0) BpmChunkyButton("Anterior", viewModel::previousQuestion, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !locked)
        },
    ) {
        Box(Modifier.fillMaxWidth()) { EvaluationChip(evaluationChipLabel(evaluation.type, course.version.passingScore, if (evaluation.bank) passingFraction(course) else null)) }
        Text(question.questionText, style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink, modifier = Modifier.fillMaxWidth())
        BpmListenButton(question.questionText + ". " + question.options.mapIndexed { i, o -> "Opción ${i + 1}: $o." }.joinToString(" "), label = "Escuchar la pregunta")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            question.options.forEachIndexed { option, text ->
                BpmOptionTile(text, (option + 1).toString(), if (picked == option) BpmOptionState.Selected else BpmOptionState.Idle,
                    { viewModel.answerEvaluation(question.id, option) }, Modifier.fillMaxWidth(), enabled = !locked)
            }
        }
        EvaluationNotices(evaluation)
    }
}

/**
 * Revisión antes de enviar: una fila por pregunta (las sin responder primero y resaltadas) con «Cambiar» para volver a
 * ella; «Enviar evaluación» solo con todas respondidas, o «Reintentar envío» si ya hay un intento pendiente.
 */
@Composable
private fun PlayReview(course: TrainingCourse, evaluation: EvaluationState, questions: List<TrainingQuestion>, viewModel: TrainingViewModel, worker: WorkerCourseContext) {
    val locked = evaluation.pending != null || evaluation.submitting
    val answered = questions.map { evaluation.answers.containsKey(it.id) }
    val missing = answered.count { !it }
    val retrying = evaluation.pending != null
    val submit: () -> Unit = { if (viewModel.canSubmitEvaluation()) viewModel.submitEvaluation() }
    BpmPlayStage(
        Modifier.bpmPlayKeys(0, {}, submit, focusKey = "revision"),
        progress = 1f, progressTone = BpmPlayTone.Gold, counter = "${questions.size}/${questions.size}", progressDescription = "Revisión final",
        onClose = worker.onClose, closeDescription = "Salir de la evaluación", scrollKey = "revision",
        footer = {
            BpmChunkyButton(when { evaluation.submitting -> "Enviando…"; retrying -> "Reintentar envío"; else -> "Enviar evaluación" }, submit, Modifier.fillMaxWidth(),
                enabled = viewModel.canSubmitEvaluation())
            BpmChunkyButton("Volver a las preguntas", viewModel::closeReview, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !locked)
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EvaluationChip(evaluationChipLabel(evaluation.type, course.version.passingScore, if (evaluation.bank) passingFraction(course) else null))
            Text("Revisa tus respuestas", style = BpmPlayType.h1, color = BpmPlayColors.ink, modifier = Modifier.padding(top = 8.dp))
            Text(if (missing == 0) "Todas tienen respuesta. Envíala cuando estés listo." else if (missing == 1) "Te falta 1 pregunta por responder." else "Te faltan $missing preguntas por responder.",
                style = BpmPlayType.sub, color = if (missing == 0) BpmPlayColors.muted else BpmPlayColors.gold.shadow)
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            reviewOrder(answered).forEach { number ->
                val question = questions[number]
                ReviewTile(number + 1, question.questionText, evaluation.answers[question.id]?.let { question.options.getOrNull(it).orEmpty() }, !locked) { viewModel.goToQuestion(number) }
            }
        }
        EvaluationNotices(evaluation)
    }
}

/** Fila de la revisión (`.opt`): número, pregunta, respuesta elegida o «Sin responder» (dorado) y «Cambiar». Toda la fila es el botón. */
@Composable
private fun ReviewTile(number: Int, text: String, choice: String?, enabled: Boolean, onChange: () -> Unit) {
    val pending = choice == null
    Row(
        Modifier.fillMaxWidth().bpmPlayFrame(if (pending) BpmPlayColors.gold.face else BpmPlayColors.line, if (pending) BpmPlayColors.gold.soft else androidx.compose.ui.graphics.Color.White, 16.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onChange)
            .semantics { contentDescription = "Pregunta $number: $text. " + (choice ?: "Sin responder") + ". Cambiar la respuesta" }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).border(BpmPlayBorder, if (pending) BpmPlayColors.gold.face else BpmPlayColors.line, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = BpmPlayType.key, color = if (pending) BpmPlayColors.gold.shadow else BpmPlayColors.muted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
            Text(choice ?: "Sin responder", style = BpmPlayType.rowDetail, color = if (pending) BpmPlayColors.gold.shadow else BpmPlayColors.muted)
        }
        Text("Cambiar", style = BpmPlayType.label, color = BpmPlayColors.action.face)
    }
}

/**
 * Resultado: la celebración con «¡Aprobaste!» (o «Casi lo logras»), el puntaje grande, fichas de XP, módulo e insignia,
 * la constancia con su código en grupos de 4 y, abajo, la acción principal («Siguiente módulo», «Intentar otra vez» o
 * «Volver al camino») y «Ver mis respuestas», que despliega cada pregunta con la correcta y su explicación.
 */
@Composable
private fun PlayResult(course: TrainingCourse, outcome: AttemptOutcome, viewModel: TrainingViewModel, worker: WorkerCourseContext, onOpenCourse: ((String) -> Unit)?) {
    val attempt = outcome.attempt
    val passed = attempt.passed
    val next = outcome.nextCourse?.takeIf { passed && attempt.attemptType == AttemptType.FINAL && onOpenCourse != null }
    val retry = !passed || attempt.attemptType == AttemptType.DIAGNOSTIC
    var showAnswers by rememberSaveable { mutableStateOf(false) }
    val primary: () -> Unit = when {
        next != null -> ({ onOpenCourse?.invoke(next.courseId) })
        retry -> ({
            val lesson = viewModel.state.value.evaluation?.lessonId
            if (attempt.attemptType == AttemptType.MODULE && lesson != null) viewModel.startModuleEvaluation(lesson) else viewModel.startEvaluation(attempt.attemptType)
        })
        else -> worker.onClose
    }
    val stats = buildList {
        val xp = attempt.xpEarned?.takeIf { it > 0 }
        if (xp != null) add(BpmStatTileSpec("XP", "+$xp", BpmPlayTone.Gold, BpmSymbol.Bolt))
        else add(BpmStatTileSpec("Mínimo", course.version.passingScore.toString(), BpmPlayTone.Gold, BpmSymbol.Target))
        if (passed && worker.moduleNumber != null && worker.moduleTotal != null) add(BpmStatTileSpec("Módulo", "${worker.moduleNumber}/${worker.moduleTotal}", BpmPlayTone.Correct, BpmSymbol.Route))
        if (outcome.achievements.isNotEmpty()) add(BpmStatTileSpec("Insignia", outcome.achievements.size.toString(), BpmPlayTone.Streak, BpmSymbol.Award))
    }
    BpmPlayStage(
        Modifier.bpmPlayKeys(0, {}, primary, focusKey = "resultado"), onClose = worker.onClose, closeDescription = "Volver al camino", scrollKey = "resultado",
        footer = {
            BpmChunkyButton(when { next != null -> "Siguiente módulo"; retry -> "Intentar otra vez"; else -> "Volver al camino" }, primary, Modifier.fillMaxWidth())
            BpmChunkyButton(if (showAnswers) "Ocultar mis respuestas" else "Ver mis respuestas", { showAnswers = !showAnswers }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        },
    ) {
        BpmCelebration(Res.drawable.mascot_achievement, if (passed) "¡Aprobaste!" else "Casi lo logras", null, stats,
            Modifier.semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite },
            titleColor = if (passed) BpmPlayColors.correct.shadow else BpmPlayColors.streak.shadow, imageWidth = 140.dp, celebrate = passed,
            middle = {
                Text(buildAnnotatedString {
                    append(attempt.score.toString())
                    withStyle(SpanStyle(fontSize = 24.sp, color = BpmPlayColors.muted)) { append(" /100") }
                }, style = BpmPlayType.h1.copy(fontSize = 54.sp, lineHeight = 54.sp), color = BpmPlayColors.ink,
                    modifier = Modifier.semantics { contentDescription = "Puntaje: ${attempt.score} de 100" })
                if (!passed) Text(if (course.bank != null) "Para aprobar necesitas ${passingFraction(course)} respuestas correctas. Repasa las unidades e inténtalo otra vez: te daremos otras preguntas."
                    else "Para aprobar necesitas ${course.version.passingScore}. Repasa las lecciones e inténtalo otra vez.", style = BpmPlayType.sub, color = BpmPlayColors.muted, textAlign = TextAlign.Center)
            })
        xpRepeatedNotice(attempt)?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (attempt.attemptType == AttemptType.MODULE) Text("La evaluación de lección no completa el curso ni emite constancia.", style = BpmPlayType.sub, color = BpmPlayColors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        outcome.achievements.forEach { achievement ->
            BpmPlayCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(BpmPlayColors.gold.soft), contentAlignment = Alignment.Center) {
                        BpmIcon(BpmSymbol.Award, tint = BpmPlayColors.gold.shadow, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Nueva insignia · ${achievement.name}", style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                        if (achievement.description.isNotBlank()) Text(achievement.description, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                    }
                }
            }
        }
        outcome.certificate?.let { certificate ->
            BpmPlayCard(dashed = true) {
                Text("CONSTANCIA", Modifier.background(BpmPlayColors.correct.soft, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp), style = BpmPlayType.statLabel, color = BpmPlayColors.correct.shadow)
                Text(course.course.title, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                Text(groupVerificationCode(certificate.verificationCode), style = BpmPlayType.sub.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp), color = BpmPlayColors.muted,
                    modifier = Modifier.semantics { contentDescription = "Código de verificación: ${certificate.verificationCode.toList().joinToString(" ")}" })
            }
        }
        next?.let { Text("Sigue con: ${it.courseTitle}", style = BpmPlayType.text, color = BpmPlayColors.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (showAnswers) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            outcome.results.forEachIndexed { number, result ->
                val review = resultReview(course, result)
                val shade = if (result.correct) BpmPlayColors.correct else BpmPlayColors.error
                val source = sourceLine(result.sources)
                Column(Modifier.fillMaxWidth().bpmPlayFrame(shade.face, shade.soft, 16.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((if (result.correct) "Correcta" else "Casi").uppercase(), style = BpmPlayType.label, color = shade.shadow)
                    Text("${number + 1}. ${review.text}", style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                    Text("Tu respuesta: ${review.chosen}", style = BpmPlayType.rowDetail, color = BpmPlayColors.ink)
                    if (!result.correct) Text("Correcta: ${review.correct}", style = BpmPlayType.rowDetail, color = BpmPlayColors.correct.shadow)
                    if (result.explanation.isNotBlank()) Text(result.explanation, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                    if (source.isNotEmpty()) Text(source, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                    if (result.itemCode != null) BpmListenButton(listOf(review.text, "Respuesta correcta: ${review.correct}", result.explanation, source).filter { it.isNotBlank() }.joinToString(". "), label = "Escuchar la explicación")
                }
            }
        }
    }
}

/** Mensaje cuando el servidor no pudo sortear la evaluación (sin conexión o fallo del servicio). */
internal fun drawFailureMessage(failure: TrainingFailure?): String = when (failure) {
    TrainingFailure.CONNECTION -> "Necesitas conexión para iniciar la evaluación."
    null -> "No pudimos preparar la evaluación. Inténtalo de nuevo."
    else -> trainingFailureMessage(failure)
}
