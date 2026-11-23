package pe.bpm.features.training.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            BpmChunkyButton("Volver al curso", viewModel::closeEvaluation, Modifier.bpmPrimaryButtonWidth())
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
    val inPane = sideBySideClass
    val initialOpen = LocalInitialNavigatorOpen.current
    var navigatorOpen by rememberSaveable { mutableStateOf(initialOpen) }

    val main: @Composable ColumnScope.() -> Unit = {
        // Regla G1: un solo indicador de posición. Con panel lateral (expandida) es el navegador del panel y la cabecera
        // solo lleva el rótulo; sin panel es el botón «Pregunta 3 de 8 ▾» que abre la hoja inferior.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            Eyebrow("$label · APRUEBA CON ${course.version.passingScore}")
            if (!inPane) Box {
                val position = "Pregunta ${index + 1} de ${questions.size}"
                BpmQuestionPickerChip(position, { navigatorOpen = true }, enabled = !locked,
                    modifier = Modifier.semantics { contentDescription = "$position. Abrir la lista de preguntas" })
                if (navigatorOpen) BpmQuestionNavigatorSheet(answered, index, viewModel::goToQuestion, { navigatorOpen = false }) {
                    BpmChunkyButton("Revisar y enviar", { navigatorOpen = false; viewModel.openReview() }, Modifier.fillMaxWidth(), enabled = !locked)
                }
            }
        }
        if (evaluation.type == AttemptType.MODULE) Text("Esta evaluación solo mide esta lección: no completa el curso ni emite constancia, pero suma puntos de experiencia.", style = MaterialTheme.typography.bodySmall)
        Text(question.questionText, style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
            question.options.forEachIndexed { option, text ->
                BpmChoiceRow(text, evaluation.answers[question.id] == option, enabled = !locked) { viewModel.answerEvaluation(question.id, option) }
            }
        }
        EvaluationNotices(evaluation)
        BpmActionBar {
            val last = index >= questions.lastIndex
            BpmInlineActions(compactSecondaryFixed = true,
                secondary = { BackButton("Anterior", "Pregunta anterior", viewModel::previousQuestion, enabled = index > 0) },
                primary = { BpmChunkyButton(if (last) "Revisar respuestas" else "Siguiente pregunta", if (last) viewModel::openReview else viewModel::nextQuestion, Modifier.fillMaxWidth()) })
        }
    }
    if (inPane) BpmSupportPaneLayout(
        support = {
            BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                Text("Tus preguntas", style = MaterialTheme.typography.titleMedium)
                BpmQuestionNavigator(answered, index, viewModel::goToQuestion, wrap = true)
                BpmTextButton(onClick = viewModel::openReview, enabled = !locked, modifier = Modifier.fillMaxWidth()) { Text("Revisar y enviar") }
            }
        },
        main = main,
    ) else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section), content = main)
}

/** Valor inicial de la hoja de preguntas; solo las capturas de pantalla lo cambian para mostrarla abierta. */
internal val LocalInitialNavigatorOpen = compositionLocalOf { false }

/**
 * Botón secundario «atrás» de los flujos por pasos: solo el icono en clase compacta (de ancho fijo) y icono con
 * [label] desde la media. [description] es lo que anuncia el lector de pantalla.
 */
@Composable
internal fun BackButton(label: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    val compact = currentWindowClass == BpmWindowClass.Compact
    BpmSecondaryButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        BpmIcon(BpmSymbol.Back, modifier = Modifier.size(20.dp))
        if (!compact) { Spacer(Modifier.width(BpmSpacing.sm)); Text(label) }
    }
}

/** Envío en curso, error y nota de reintento: iguales en la pregunta y en la revisión. */
@Composable
internal fun EvaluationNotices(evaluation: EvaluationState) {
    if (evaluation.submitting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Enviando tu evaluación…") }
    evaluation.failure?.let { failure ->
        Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (evaluation.pending != null && failure.retryable) Text("Tus respuestas están guardadas. Reintentar envía exactamente el mismo intento, sin duplicarlo.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Revisión previa al envío: una lista plana con divisores (sin tarjetas dentro de tarjetas), las preguntas sin
 * responder primero y resaltadas, «Cambiar» para volver a cada una y «Enviar evaluación» como acción principal (solo
 * con todas respondidas, o «Reintentar envío» si ya hay un intento pendiente).
 */
@Composable
private fun EvaluationReview(label: String, state: TrainingState, course: TrainingCourse, evaluation: EvaluationState,
    questions: List<TrainingQuestion>, answered: List<Boolean>, viewModel: TrainingViewModel) {
    val locked = evaluation.pending != null || evaluation.submitting
    val missing = answered.count { !it }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            Eyebrow("$label · APRUEBA CON ${course.version.passingScore}")
            Text("Revisa tus respuestas", style = MaterialTheme.typography.headlineSmall)
        }
        if (missing > 0) BpmInlineNotice(if (missing == 1) "Te falta 1 pregunta por responder. Pulsa «Cambiar» para contestarla." else "Te faltan $missing preguntas por responder. Pulsa «Cambiar» para contestarlas.", warning = true)
        else Text("Todas las preguntas tienen respuesta. Revisa y envía cuando estés listo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
            val order = reviewOrder(answered)
            order.forEachIndexed { position, number ->
                val question = questions[number]
                val choice = evaluation.answers[question.id]
                if (position > 0) BpmRowDivider()
                ReviewRow(number + 1, question.questionText, choice?.let { question.options.getOrNull(it).orEmpty() }, enabled = !locked) { viewModel.goToQuestion(number) }
            }
        }
        EvaluationNotices(evaluation)
        val retrying = evaluation.pending != null
        BpmActionBar {
            BpmInlineActions(compactSecondaryFixed = true,
                secondary = { BackButton("Volver", "Volver a las preguntas", viewModel::closeReview, enabled = !locked) },
                primary = { BpmChunkyButton(if (retrying) "Reintentar envío" else "Enviar evaluación", viewModel::submitEvaluation, Modifier.fillMaxWidth(), enabled = viewModel.canSubmitEvaluation()) })
        }
    }
}

/** Orden de la revisión: primero los índices de las preguntas sin responder y luego los de las respondidas, cada grupo en su orden. */
internal fun reviewOrder(answered: List<Boolean>): List<Int> = answered.indices.filter { !answered[it] } + answered.indices.filter { answered[it] }

/** Fila plana de la revisión: pregunta, respuesta elegida o «Sin responder» (resaltada) y «Cambiar». */
@Composable
private fun ReviewRow(number: Int, text: String, choice: String?, enabled: Boolean, onChange: () -> Unit) {
    val warning = choice == null
    val tint = if (warning) BpmTone.Warning.colors().first else androidx.compose.ui.graphics.Color.Transparent
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint).padding(vertical = BpmSpacing.xs, horizontal = if (warning) BpmSpacing.sm else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
            Text("$number. $text", style = BpmType.bodyStrong)
            if (choice == null) BpmStatusChip("Sin responder", BpmTone.Warning)
            else Text(choice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BpmTextButton(onClick = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = "Cambiar la respuesta de la pregunta $number" }) { Text("Cambiar") }
    }
}

/**
 * Resultado en modo enfoque. Héroe: mascota si aprobó, estado, puntaje grande, mínimo y «+N XP» (el total del curso va
 * como texto pequeño). Después insignias nuevas, constancia con el código en grupos, la acción principal (siguiente
 * curso con su título y ruta, «Intentar otra vez» o «Volver al curso»; fija abajo en clase compacta) y, como botones
 * de texto, «Revisar respuestas» (plegado) y las salidas secundarias.
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
            Text("Mínimo ${course.version.passingScore}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            xpEarnedChip(attempt)?.let { BpmStatusChip(it, BpmTone.Info, symbol = BpmSymbol.Award) }
            xpRepeatedNotice(attempt)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (attempt.attemptType == AttemptType.FINAL && outcome.progress.experiencePoints > 0) {
                Text("Total del curso: ${outcome.progress.experiencePoints} XP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (outcome.achievements.isNotEmpty()) AchievementCelebration(outcome.achievements)
    if (attempt.attemptType == AttemptType.MODULE) Text("La evaluación de lección no completa el curso. Tu XP total está en «Mis logros».", style = MaterialTheme.typography.bodySmall)
    outcome.certificate?.let { CertificateCard(it, prominent = true) }
    val next = outcome.nextCourse?.takeIf { attempt.passed && attempt.attemptType == AttemptType.FINAL && onOpenCourse != null }
    val retry = !attempt.passed || attempt.attemptType == AttemptType.DIAGNOSTIC
    if (next != null) Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
        Text(next.courseTitle, style = MaterialTheme.typography.titleMedium)
        next.pathTitle?.takeIf { it.isNotBlank() }?.let { Text("Ruta: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    BpmActionBar {
        BpmInlineActions(primary = when {
            next != null -> ({ BpmChunkyButton("Siguiente curso", { onOpenCourse?.invoke(next.courseId) }, Modifier.fillMaxWidth()) })
            retry -> ({ BpmChunkyButton("Intentar otra vez", {
                val lesson = viewModel.state.value.evaluation?.lessonId
                if (attempt.attemptType == AttemptType.MODULE && lesson != null) viewModel.startModuleEvaluation(lesson) else viewModel.startEvaluation(attempt.attemptType)
            }, Modifier.fillMaxWidth()) })
            else -> ({ BpmChunkyButton("Volver al curso", viewModel::closeEvaluation, Modifier.fillMaxWidth()) })
        })
    }

    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        BpmTextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (open) "Desplegado" else "Plegado" }) {
            Text(if (open) "Ocultar respuestas" else "Revisar respuestas", modifier = Modifier.weight(1f))
            BpmIcon(BpmSymbol.ChevronDown, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
        }
        if (open) BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
            outcome.results.forEachIndexed { number, result ->
                val question = course.questions.firstOrNull { it.id == result.questionId }
                if (number > 0) BpmRowDivider()
                Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                    Eyebrow(if (result.correct) "CORRECTA" else "INCORRECTA")
                    Text("${number + 1}. ${question?.questionText.orEmpty()}", style = BpmType.bodyStrong)
                    Text("Tu respuesta: ${question?.options?.getOrNull(result.selectedOption).orEmpty()}")
                    if (!result.correct) Text("Respuesta correcta: ${question?.options?.getOrNull(result.correctOption).orEmpty()}")
                    if (result.explanation.isNotBlank()) Text(result.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (retry) BpmTextButton(onClick = { viewModel.startStudy() }, modifier = Modifier.fillMaxWidth()) { Text("Repasar en modo estudio") }
        if (next != null || retry) BpmTextButton(onClick = viewModel::closeEvaluation, modifier = Modifier.fillMaxWidth()) { Text("Volver al curso") }
    }
}

/** «+N XP» ganados con este intento; nulo si el servidor no lo informa (histórico o servidor anterior) o fue 0. */
internal fun xpEarnedChip(attempt: AttemptSummary): String? = attempt.xpEarned?.takeIf { it > 0 }?.let { "+$it XP" }

/**
 * Aviso de una evaluación final aprobada que no sumó XP porque ya se tenía ese puntaje. Solo para FINAL aprobada con
 * `xpEarned == 0`: nulo o un intento reprobado/de otro tipo no lleva aviso.
 */
internal fun xpRepeatedNotice(attempt: AttemptSummary): String? =
    if (attempt.attemptType == AttemptType.FINAL && attempt.passed && attempt.xpEarned == 0) "Sin XP nuevo: ya tenías este puntaje" else null
