package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.core.offline.LocalOnline
import pe.bpm.core.offline.PendingBadge
import pe.bpm.core.offline.relativeAge
import pe.bpm.features.training.domain.*

/** Aprender: detalle del curso, lección con video, modo estudio y modo evaluación. */
@Composable
fun TrainingScreen(viewModel: TrainingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // Cada cambio de pantalla interna vuelve al inicio del contenido.
    LaunchedEffect(state.screen, state.lessonId, state.study?.index) { scroll.scrollTo(0) }
    Column(modifier.fillMaxSize().widthIn(max = 860.dp).verticalScroll(scroll).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.loading && state.course == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            BpmCard(Modifier.fillMaxWidth()) {
                Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (failure.retryable || state.course == null) BpmSecondaryButton(onClick = viewModel::refresh) { Text("Volver a intentar") }
            }
        }
        state.notice?.let { notice ->
            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Text(notice, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmTextButton(onClick = viewModel::dismissNotice) { Text("Entendido") }
            }
        }
        val course = state.course ?: return@Column
        when (state.screen) {
            TrainingPane.OVERVIEW -> CourseOverview(course, state, viewModel)
            TrainingPane.LESSON -> state.lesson?.let { LessonViewer(state, course, it, viewModel) }
            TrainingPane.STUDY -> state.study?.let { StudyPractice(it, viewModel) }
            TrainingPane.EVALUATION -> state.evaluation?.let { EvaluationPanel(state, course, it, viewModel) }
        }
    }
}

internal fun progressLabel(status: ProgressStatus) = when (status) {
    ProgressStatus.NOT_STARTED -> "Sin iniciar"
    ProgressStatus.IN_PROGRESS -> "En progreso"
    ProgressStatus.COMPLETED -> "Completado"
}

@Composable
private fun CourseOverview(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val canPersist = state.canPersist
    val info = course.course
    course.cachedAt?.let { cachedAt ->
        Text("Estás viendo la copia guardada en este dispositivo (actualizada ${relativeAge(kotlin.time.Clock.System.now().toEpochMilliseconds(), cachedAt)}). El avance y las evaluaciones se enviarán al recuperar la conexión.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    PendingBadge(info.id)
    Text(info.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    if (info.description.isNotBlank()) Text(info.description)
    Text("${info.code} · ${info.estimatedDurationMinutes} min · versión ${course.version.versionNumber}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("TU AVANCE")
        Text(progressLabel(course.progress.status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        course.progress.bestScore?.let { Text("Mejor puntaje en evaluación final: $it") }
        if (course.progress.experiencePoints > 0) Text("Puntos de experiencia: ${course.progress.experiencePoints} XP")
        if (course.lessons.isNotEmpty()) RaisedButton(
            if (course.progress.lastLessonId != null) "Continuar donde quedaste" else "Empezar el curso", viewModel::resume, Modifier.widthIn(max = 360.dp).fillMaxWidth())
    }
    course.certificate?.let { CertificateCard(it) }
    Text("Lecciones", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (course.lessons.isEmpty()) EmptyState("Sin lecciones", "Este curso todavía no tiene lecciones publicadas.")
    BpmCardGrid(course.lessons, minCardWidth = 320.dp, maxColumns = 2) { lesson, cardModifier ->
        BpmCard(cardModifier) {
            Eyebrow("LECCIÓN ${course.lessons.indexOf(lesson) + 1}" + if (lesson.id == course.progress.lastLessonId) " · ÚLTIMA QUE VISTE" else "")
            Text(lesson.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (lesson.video != null || lesson.externalVideo != null) Text(
                when {
                    lesson.externalVideo != null -> "Incluye video de YouTube · Requiere conexión"
                    state.downloads.downloaded(lesson.video!!.mediaId) -> "Incluye video · Guardado en este dispositivo"
                    else -> "Incluye video"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BpmSecondaryButton(onClick = { viewModel.selectLesson(lesson.id) }, modifier = Modifier.fillMaxWidth()) { Text("Abrir lección") }
        }
    }
    DownloadsCard(course, state, viewModel)
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("MODO ESTUDIO · SIN CALIFICACIÓN")
        Text("Repasa y practica", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Responde preguntas una por una y mira al instante si acertaste y por qué. No genera puntaje, intentos, puntos ni constancia, y puedes repetir cuantas veces quieras.")
        BpmSecondaryButton(onClick = { viewModel.startStudy() }, enabled = course.questions.isNotEmpty(), modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Practicar en modo estudio") }
    }
    if (!canPersist) BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("MODO EVALUACIÓN · SE CALIFICA")
        Text("Tu cuenta no tiene una membresía en esta organización: puedes leer y practicar, pero la evaluación y el avance solo están disponibles para sus miembros.")
    } else BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("MODO EVALUACIÓN · SE CALIFICA")
        Text("Demuestra lo aprendido", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Responde todas las preguntas y envía el intento. Para aprobar necesitas ${course.version.passingScore} puntos. La evaluación final aprobada completa el curso y emite tu constancia; la diagnóstica solo mide dónde estás.")
        BpmSecondaryButton(onClick = { viewModel.startEvaluation(AttemptType.DIAGNOSTIC) }, enabled = course.questions.isNotEmpty(), modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Evaluación diagnóstica") }
        RaisedButton("Evaluación final", { viewModel.startEvaluation(AttemptType.FINAL) }, Modifier.widthIn(max = 360.dp).fillMaxWidth(), enabled = course.questions.isNotEmpty())
    }
    AttemptsHistory(state.attempts, course.version.passingScore)
}

/** Evaluaciones enviadas desde este dispositivo: por calificar (sin conexión) o con la calificación del servidor. */
@Composable
private fun AttemptsHistory(attempts: List<AttemptRecord>, passingScore: Int) {
    if (attempts.isEmpty()) return
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("EVALUACIONES ENVIADAS")
        attempts.take(5).forEach { record ->
            val kind = when (record.type) { AttemptType.FINAL -> "Evaluación final"; AttemptType.MODULE -> "Evaluación de lección"; else -> "Evaluación diagnóstica" }
            val result = record.result
            Text(if (result == null) "$kind: enviada, se calificará al conectarse" else "$kind: ${result.score} de 100 · ${if (result.passed) "Aprobado" else "No aprobado (mínimo $passingScore)"}",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** «Descargar para ver sin conexión»: videos privados verificados, espacio usado y límite configurable. */
@Composable
private fun DownloadsCard(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val downloads = state.downloads
    val videos = course.lessons.filter { it.video != null }
    val external = course.lessons.count { it.externalVideo != null }
    if (videos.isEmpty() && external == 0) return
    val online = LocalOnline.current
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("VER SIN CONEXIÓN")
        if (!downloads.supported) {
            Text("En este navegador los videos no se guardan para verlos sin conexión. Usa la aplicación móvil para descargarlos. El texto de las lecciones sí está disponible sin conexión.",
                style = MaterialTheme.typography.bodyMedium)
        } else if (videos.isNotEmpty()) {
            val total = videos.sumOf { it.video!!.sizeBytes }
            val done = videos.count { it.offlineAvailable && downloads.downloaded(it.video!!.mediaId) }
            val busy = downloads.activeCourseId == course.course.id
            Text("${done} de ${videos.size} videos guardados · ${formatBytes(total)} en total", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (busy) {
                val progress = videos.mapNotNull { downloads.of(it.video!!.mediaId) }.sumOf { it.downloadedBytes }
                LinearProgressIndicator(progress = { if (total > 0) (progress.toFloat() / total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Text("Descargando… ${formatBytes(progress)} de ${formatBytes(total)}", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmTextButton(onClick = viewModel::cancelDownload) { Text("Pausar descarga") }
            } else if (done < videos.size) {
                BpmSecondaryButton(onClick = viewModel::downloadCourse, enabled = online, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) {
                    Text(if (downloads.videos.values.any { it.status == VideoDownloadStatus.PAUSED }) "Reanudar descarga" else "Descargar para ver sin conexión")
                }
                if (!online) Text("Necesitas conexión para descargar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            videos.mapNotNull { downloads.of(it.video!!.mediaId)?.issue }.firstOrNull()?.let { issue ->
                Text(downloadIssueMessage(issue, downloads.limitBytes), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (done > 0) BpmTextButton(onClick = viewModel::deleteCourseDownloads) { Text("Borrar descargas de este curso") }
        }
        if (external > 0) Text("$external lección(es) usan video de YouTube: requieren conexión y no se pueden descargar.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (downloads.supported) {
            Text("Espacio usado por descargas: ${formatBytes(downloads.usedBytes)} de ${formatBytes(downloads.limitBytes)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainingDownloads.LIMIT_CHOICES.forEach { choice ->
                    BpmTextButton(onClick = { viewModel.setDownloadLimit(choice) }) { Text((if (choice == downloads.limitBytes) "✓ " else "") + formatBytes(choice)) }
                }
            }
            if (downloads.usedBytes > 0) BpmTextButton(onClick = viewModel::deleteAllDownloads) { Text("Borrar todas las descargas") }
        }
    }
}

internal fun downloadIssueMessage(issue: DownloadIssue, limit: Long) = when (issue) {
    DownloadIssue.NETWORK -> "La descarga se interrumpió. Lo ya guardado se conserva: pulsa Reanudar cuando tengas conexión."
    DownloadIssue.CORRUPT -> "El video descargado no coincide con el original y se descartó. Vuelve a intentarlo."
    DownloadIssue.LIMIT -> "No hay espacio dentro del límite de ${formatBytes(limit)}. Borra descargas o aumenta el límite."
    DownloadIssue.UNSUPPORTED -> "Este dispositivo no permite guardar videos."
    DownloadIssue.TICKET -> "No pudimos obtener el video. Revisa tu conexión y vuelve a intentarlo."
}

@Composable
private fun CertificateCard(certificate: TrainingCertificate) {
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("CONSTANCIA")
        Text("Completaste este curso", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Código de verificación")
        Text(certificate.verificationCode, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text("Emitida: ${certificate.issuedAt.substringBefore('T')}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LessonViewer(state: TrainingState, course: TrainingCourse, lesson: TrainingLesson, viewModel: TrainingViewModel) {
    val index = course.lessons.indexOfFirst { it.id == lesson.id }
    Eyebrow("LECCIÓN ${index + 1} DE ${course.lessons.size}")
    Text(lesson.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 520.dp
        val previous: @Composable (Modifier) -> Unit = { m -> BpmSecondaryButton(onClick = viewModel::previousLesson, enabled = index > 0, modifier = m) { Text("Anterior") } }
        val next: @Composable (Modifier) -> Unit = { m ->
            if (index < course.lessons.lastIndex) RaisedButton("Siguiente", viewModel::nextLesson, m)
            else BpmSecondaryButton(onClick = viewModel::back, modifier = m) { Text("Terminar lecciones") }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) { next(Modifier.fillMaxWidth()); previous(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) { previous(Modifier.weight(1f)); next(Modifier.weight(1f)) }
    }
    if (course.questions.isNotEmpty()) BpmTextButton(onClick = { viewModel.startStudy(lesson.id) }) { Text("Practicar esta lección · modo estudio, sin calificación") }
    if (state.canPersist && course.questions.any { it.lessonId == lesson.id })
        BpmSecondaryButton(onClick = { viewModel.startModuleEvaluation(lesson.id) }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Evaluar esta lección · se califica") }
    BpmTextButton(onClick = { viewModel.back() }) { Text("Volver al curso") }
}

@Composable
private fun StudyPractice(study: StudyState, viewModel: TrainingViewModel) {
    Eyebrow("MODO ESTUDIO · SIN CALIFICACIÓN")
    if (study.finished) {
        BpmCard(Modifier.fillMaxWidth()) {
            Text("Terminaste la práctica", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Repasar no genera puntaje ni constancia. Cuando quieras que cuente, usa el modo evaluación.")
            RaisedButton("Practicar de nuevo", viewModel::restartStudy, Modifier.widthIn(max = 360.dp).fillMaxWidth())
            BpmSecondaryButton(onClick = { viewModel.back() }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Volver al curso") }
        }
        return
    }
    val question = study.current ?: return
    Text("Pregunta ${study.index + 1} de ${study.questions.size}", style = MaterialTheme.typography.labelLarge)
    Text(question.questionText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                Text(if (feedback.correct) "¡Correcto!" else "Todavía no", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (!feedback.correct) Text("La respuesta correcta es: ${question.options.getOrNull(feedback.correctOption).orEmpty()}")
                if (feedback.explanation.isNotBlank()) Text(feedback.explanation)
            }
            BpmSecondaryButton(onClick = viewModel::retryStudyQuestion, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Reintentar esta pregunta") }
            RaisedButton(if (study.index == study.questions.lastIndex) "Terminar práctica" else "Siguiente pregunta", viewModel::nextStudyQuestion, Modifier.widthIn(max = 360.dp).fillMaxWidth())
        }
    }
    BpmTextButton(onClick = { viewModel.back() }) { Text("Salir de la práctica") }
}

@Composable
private fun EvaluationPanel(state: TrainingState, course: TrainingCourse, evaluation: EvaluationState, viewModel: TrainingViewModel) {
    val label = when (evaluation.type) { AttemptType.FINAL -> "EVALUACIÓN FINAL"; AttemptType.MODULE -> "EVALUACIÓN DE LECCIÓN"; else -> "EVALUACIÓN DIAGNÓSTICA" }
    val questions = state.evaluationQuestions
    Eyebrow("$label · SE CALIFICA")
    val outcome = evaluation.outcome
    if (outcome != null) { EvaluationResult(course, outcome, viewModel); return }
    if (evaluation.queued) {
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Evaluación enviada: se calificará al conectarse", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Tus respuestas están guardadas en este dispositivo. El servidor las calificará cuando recuperes la conexión y verás el resultado en el curso.")
            }
            RaisedButton("Volver al curso", viewModel::closeEvaluation, Modifier.widthIn(max = 360.dp).fillMaxWidth())
        }
        return
    }
    Text("Responde todas las preguntas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Aprobar requiere ${course.version.passingScore} puntos. Respondidas: ${evaluation.answers.size} de ${questions.size}.")
    if (evaluation.type == AttemptType.MODULE) Text("Esta evaluación solo mide esta lección: no completa el curso ni emite constancia, pero suma puntos de experiencia.", style = MaterialTheme.typography.bodySmall)
    val locked = evaluation.pending != null || evaluation.submitting
    questions.forEachIndexed { number, question ->
        BpmCard(Modifier.fillMaxWidth()) {
            Text("${number + 1}. ${question.questionText}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            question.options.forEachIndexed { option, text ->
                BpmChoiceRow(text, evaluation.answers[question.id] == option, enabled = !locked) { viewModel.answerEvaluation(question.id, option) }
            }
        }
    }
    if (evaluation.submitting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Enviando tu evaluación…") }
    evaluation.failure?.let { failure ->
        Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (evaluation.pending != null && failure.retryable) Text("Tus respuestas están guardadas. Reintentar envía exactamente el mismo intento, sin duplicarlo.", style = MaterialTheme.typography.bodySmall)
    }
    val retrying = evaluation.pending != null
    RaisedButton(if (retrying) "Reintentar envío" else "Enviar evaluación", viewModel::submitEvaluation, Modifier.widthIn(max = 360.dp).fillMaxWidth(), enabled = viewModel.canSubmitEvaluation())
    BpmTextButton(onClick = { viewModel.back() }, enabled = !evaluation.submitting) { Text("Salir sin enviar") }
}

@Composable
private fun EvaluationResult(course: TrainingCourse, outcome: AttemptOutcome, viewModel: TrainingViewModel) {
    val attempt = outcome.attempt
    BpmCard(Modifier.fillMaxWidth(), tint = if (attempt.passed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (attempt.passed) "Aprobado" else "No aprobado", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text("Puntaje: ${attempt.score} de 100 (mínimo ${course.version.passingScore})", style = MaterialTheme.typography.titleMedium)
            if (attempt.attemptType == AttemptType.FINAL && outcome.progress.experiencePoints > 0) Text("Puntos de experiencia: ${outcome.progress.experiencePoints} XP")
        }
    }
    if (outcome.achievements.isNotEmpty()) AchievementCelebration(outcome.achievements)
    if (attempt.attemptType == AttemptType.MODULE) Text("La evaluación de lección no completa el curso. Tu XP total está en «Mis logros».", style = MaterialTheme.typography.bodySmall)
    outcome.certificate?.let { CertificateCard(it) }
    Text("Resultado por pregunta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    outcome.results.forEachIndexed { number, result ->
        val question = course.questions.firstOrNull { it.id == result.questionId }
        BpmCard(Modifier.fillMaxWidth()) {
            Eyebrow(if (result.correct) "CORRECTA" else "INCORRECTA")
            Text("${number + 1}. ${question?.questionText.orEmpty()}", fontWeight = FontWeight.Bold)
            Text("Tu respuesta: ${question?.options?.getOrNull(result.selectedOption).orEmpty()}")
            if (!result.correct) Text("Respuesta correcta: ${question?.options?.getOrNull(result.correctOption).orEmpty()}")
            if (result.explanation.isNotBlank()) Text(result.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!attempt.passed || attempt.attemptType == AttemptType.DIAGNOSTIC) {
        BpmSecondaryButton(onClick = { viewModel.startStudy() }, modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth()) { Text("Repasar en modo estudio") }
        RaisedButton("Intentar otra vez", { val lesson = viewModel.state.value.evaluation?.lessonId; if (attempt.attemptType == AttemptType.MODULE && lesson != null) viewModel.startModuleEvaluation(lesson) else viewModel.startEvaluation(attempt.attemptType) }, Modifier.widthIn(max = 360.dp).fillMaxWidth())
    }
    BpmTextButton(onClick = viewModel::closeEvaluation) { Text("Volver al curso") }
}
