package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*

/**
 * Evaluación una pregunta por pantalla. Responder no avanza solo. La última pregunta lleva a la revisión
 * ([EvaluationReview]), desde donde se envía; el envío, los errores, el reintento y la cola sin conexión no cambian.
 * Con resultado se muestra [EvaluationResult].
 */
@Composable
internal fun EvaluationPanel(state: TrainingState, course: TrainingCourse, evaluation: EvaluationState, viewModel: TrainingViewModel, onOpenCourse: ((String) -> Unit)? = null) {
    val label = when (evaluation.type) { AttemptType.FINAL -> "EVALUACIÓN FINAL"; AttemptType.MODULE -> "EVALUACIÓN DE LECCIÓN"; else -> "EVALUACIÓN DIAGNÓSTICA" }
    val questions = state.evaluationQuestions
    val outcome = evaluation.outcome
    if (outcome != null) { Eyebrow("$label · SE CALIFICA"); EvaluationResult(course, outcome, viewModel, onOpenCourse); return }
    if (evaluation.queued) {
        Eyebrow("$label · SE CALIFICA")
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Evaluación enviada: se calificará al conectarse", style = MaterialTheme.typography.titleLarge)
                Text("Tus respuestas están guardadas en este dispositivo. El servidor las calificará cuando recuperes la conexión y verás el resultado en el curso.")
            }
            RaisedButton("Volver al curso", viewModel::closeEvaluation, Modifier.bpmPrimaryButtonWidth())
        }
        return
    }
    if (questions.isEmpty()) {
        Eyebrow("$label · SE CALIFICA")
        Text("Esta evaluación todavía no tiene preguntas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        BpmTextButton(onClick = { viewModel.back() }) { Text("Salir") }
        return
    }
    val answered = questions.map { evaluation.answers.containsKey(it.id) }
    if (evaluation.reviewing) { EvaluationReview(label, state, course, evaluation, questions, answered, viewModel); return }
    val index = evaluation.index.coerceIn(0, questions.lastIndex)
    val question = questions[index]
    val locked = evaluation.pending != null || evaluation.submitting
    val compact = currentWindowClass == BpmWindowClass.Compact
    val inPane = sideBySideClass

    val main: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            Eyebrow("$label · SE CALIFICA")
            BpmSegmentedProgress(questions.size, index, "Pregunta ${index + 1} de ${questions.size}", current = index)
            Text("Pregunta ${index + 1} de ${questions.size} · aprueba con ${course.version.passingScore}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (evaluation.type == AttemptType.MODULE) Text("Esta evaluación solo mide esta lección: no completa el curso ni emite constancia, pero suma puntos de experiencia.", style = MaterialTheme.typography.bodySmall)
        Text(question.questionText, style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
            question.options.forEachIndexed { option, text ->
                BpmChoiceRow(text, evaluation.answers[question.id] == option, enabled = !locked) { viewModel.answerEvaluation(question.id, option) }
            }
        }
        EvaluationNotices(evaluation)
        // En clase compacta y media el navegador va sobre las acciones; desde la expandida vive en el panel lateral.
        if (!inPane) BpmQuestionNavigator(answered, index, viewModel::goToQuestion, wrap = false)
        BpmActionBar {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid), verticalAlignment = Alignment.CenterVertically) {
                BpmSecondaryButton(onClick = viewModel::previousQuestion, enabled = index > 0,
                    modifier = Modifier.semantics { contentDescription = "Pregunta anterior" }.then(if (compact) Modifier else Modifier.widthIn(min = 120.dp))) {
                    BpmIcon(BpmSymbol.Back, modifier = Modifier.size(20.dp))
                    if (!compact) { Spacer(Modifier.width(BpmSpacing.sm)); Text("Anterior") }
                }
                val last = index >= questions.lastIndex
                RaisedButton(if (last) "Revisar respuestas" else "Siguiente pregunta", if (last) viewModel::openReview else viewModel::nextQuestion,
                    if (compact) Modifier.weight(1f) else Modifier.widthIn(min = 200.dp))
            }
        }
        BpmTextButton(onClick = { viewModel.back() }, enabled = !evaluation.submitting) { Text("Salir sin enviar") }
    }
    if (inPane) BpmSupportPaneLayout(
        support = {
            BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                Text("Progreso de la evaluación", style = MaterialTheme.typography.titleMedium)
                BpmQuestionNavigator(answered, index, viewModel::goToQuestion, wrap = true)
            }
        },
        main = main,
    ) else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section), content = main)
}

/** Envío en curso, error y nota de reintento: iguales en la pregunta y en la revisión. */
@Composable
private fun EvaluationNotices(evaluation: EvaluationState) {
    if (evaluation.submitting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Enviando tu evaluación…") }
    evaluation.failure?.let { failure ->
        Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (evaluation.pending != null && failure.retryable) Text("Tus respuestas están guardadas. Reintentar envía exactamente el mismo intento, sin duplicarlo.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Revisión previa al envío: cada pregunta con la respuesta elegida o «Sin responder», «Cambiar» para volver a ella y
 * «Enviar evaluación» (solo con todas respondidas, o «Reintentar envío» si ya hay un intento pendiente).
 */
@Composable
private fun EvaluationReview(label: String, state: TrainingState, course: TrainingCourse, evaluation: EvaluationState,
    questions: List<TrainingQuestion>, answered: List<Boolean>, viewModel: TrainingViewModel) {
    val locked = evaluation.pending != null || evaluation.submitting
    val missing = answered.count { !it }
    val compact = currentWindowClass == BpmWindowClass.Compact
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            Eyebrow("$label · SE CALIFICA")
            BpmSegmentedProgress(questions.size, questions.size, "Revisión: ${questions.size - missing} de ${questions.size} respondidas")
            Text("Revisa tus respuestas", style = MaterialTheme.typography.headlineSmall)
            Text("Aprobar requiere ${course.version.passingScore} puntos. Respondidas: ${questions.size - missing} de ${questions.size}.")
        }
        if (missing > 0) BpmInlineNotice(if (missing == 1) "Te falta 1 pregunta por responder. Pulsa «Cambiar» para contestarla." else "Te faltan $missing preguntas por responder. Pulsa «Cambiar» para contestarlas.", warning = true)
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            questions.forEachIndexed { number, question ->
                val choice = evaluation.answers[question.id]
                BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                            Text("${number + 1}. ${question.questionText}", style = BpmType.bodyStrong)
                            if (choice == null) BpmStatusChip("Sin responder", BpmTone.Warning)
                            else Text(question.options.getOrNull(choice).orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BpmTextButton(onClick = { viewModel.goToQuestion(number) }, enabled = !locked,
                            modifier = Modifier.semantics { contentDescription = "Cambiar la respuesta de la pregunta ${number + 1}" }) { Text("Cambiar") }
                    }
                }
            }
        }
        EvaluationNotices(evaluation)
        val retrying = evaluation.pending != null
        BpmActionBar {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid), verticalAlignment = Alignment.CenterVertically) {
                BpmSecondaryButton(onClick = viewModel::closeReview, enabled = !locked,
                    modifier = Modifier.semantics { contentDescription = "Volver a las preguntas" }.then(if (compact) Modifier else Modifier.widthIn(min = 120.dp))) {
                    BpmIcon(BpmSymbol.Back, modifier = Modifier.size(20.dp))
                    if (!compact) { Spacer(Modifier.width(BpmSpacing.sm)); Text("Volver") }
                }
                RaisedButton(if (retrying) "Reintentar envío" else "Enviar evaluación", viewModel::submitEvaluation,
                    if (compact) Modifier.weight(1f) else Modifier.widthIn(min = 200.dp), enabled = viewModel.canSubmitEvaluation())
            }
        }
        BpmTextButton(onClick = { viewModel.back() }, enabled = !evaluation.submitting) { Text("Salir sin enviar") }
    }
}

/**
 * Resultado: mascota si aprobó, estado, puntaje grande, mínimo y XP; insignias nuevas, constancia destacada y el
 * detalle por pregunta plegado en «Revisar respuestas» (cerrado por defecto). Las acciones de antes se conservan.
 */
@Composable
internal fun EvaluationResult(course: TrainingCourse, outcome: AttemptOutcome, viewModel: TrainingViewModel, onOpenCourse: ((String) -> Unit)? = null) {
    val attempt = outcome.attempt
    BpmCard(Modifier.fillMaxWidth(), tint = if (attempt.passed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            if (attempt.passed) Mascot(Modifier.size(112.dp))
            if (attempt.passed) BpmStatusChip("Aprobado", BpmTone.Success, symbol = BpmSymbol.Check) else BpmStatusChip("No aprobado", BpmTone.Warning)
            val scoreStyle = BpmType.bodyStrong.toSpanStyle().copy(fontSize = 44.sp)
            val denominatorStyle = BpmType.bodyStrong.toSpanStyle().copy(fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(buildAnnotatedString {
                withStyle(scoreStyle) { append(attempt.score.toString()) }
                withStyle(denominatorStyle) { append(" / 100") }
            }, modifier = Modifier.semantics { contentDescription = "Puntaje: ${attempt.score} de 100" })
            Text("Mínimo para aprobar: ${course.version.passingScore}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            xpEarnedChip(attempt)?.let { BpmStatusChip(it, BpmTone.Info, symbol = BpmSymbol.Award) }
            xpRepeatedNotice(attempt)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (attempt.attemptType == AttemptType.FINAL && outcome.progress.experiencePoints > 0) Text("Puntos de experiencia: ${outcome.progress.experiencePoints} XP", style = MaterialTheme.typography.titleSmall)
        }
    }
    if (outcome.achievements.isNotEmpty()) AchievementCelebration(outcome.achievements)
    if (attempt.attemptType == AttemptType.MODULE) Text("La evaluación de lección no completa el curso. Tu XP total está en «Mis logros».", style = MaterialTheme.typography.bodySmall)
    outcome.certificate?.let { CertificateCard(it, prominent = true) }
    val next = outcome.nextCourse
    if (next != null && attempt.passed && attempt.attemptType == AttemptType.FINAL && onOpenCourse != null) {
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            next.pathTitle?.takeIf { it.isNotBlank() }?.let { Text("Ruta: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            RaisedButton("Siguiente curso: ${next.courseTitle}", { onOpenCourse(next.courseId) }, Modifier.bpmPrimaryButtonWidth())
        }
    }

    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        BpmTextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (open) "Desplegado" else "Plegado" }) {
            Text(if (open) "Ocultar respuestas" else "Revisar respuestas", modifier = Modifier.weight(1f))
            BpmIcon(BpmSymbol.ChevronDown, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
        }
        if (open) outcome.results.forEachIndexed { number, result ->
            val question = course.questions.firstOrNull { it.id == result.questionId }
            BpmCard(Modifier.fillMaxWidth()) {
                Eyebrow(if (result.correct) "CORRECTA" else "INCORRECTA")
                Text("${number + 1}. ${question?.questionText.orEmpty()}", style = BpmType.bodyStrong)
                Text("Tu respuesta: ${question?.options?.getOrNull(result.selectedOption).orEmpty()}")
                if (!result.correct) Text("Respuesta correcta: ${question?.options?.getOrNull(result.correctOption).orEmpty()}")
                if (result.explanation.isNotBlank()) Text(result.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!attempt.passed || attempt.attemptType == AttemptType.DIAGNOSTIC) {
        BpmSecondaryButton(onClick = { viewModel.startStudy() }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Repasar en modo estudio") }
        RaisedButton("Intentar otra vez", { val lesson = viewModel.state.value.evaluation?.lessonId; if (attempt.attemptType == AttemptType.MODULE && lesson != null) viewModel.startModuleEvaluation(lesson) else viewModel.startEvaluation(attempt.attemptType) }, Modifier.bpmPrimaryButtonWidth())
    }
    BpmTextButton(onClick = viewModel::closeEvaluation) { Text("Volver al curso") }
}

/** «+N XP» ganados con este intento; nulo si el servidor no lo informa (histórico o servidor anterior) o fue 0. */
internal fun xpEarnedChip(attempt: AttemptSummary): String? = attempt.xpEarned?.takeIf { it > 0 }?.let { "+$it XP" }

/**
 * Aviso de una evaluación final aprobada que no sumó XP porque ya se tenía ese puntaje. Solo para FINAL aprobada con
 * `xpEarned == 0`: nulo o un intento reprobado/de otro tipo no lleva aviso.
 */
internal fun xpRepeatedNotice(attempt: AttemptSummary): String? =
    if (attempt.attemptType == AttemptType.FINAL && attempt.passed && attempt.xpEarned == 0) "Sin XP nuevo: ya tenías este puntaje" else null
