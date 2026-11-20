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
import androidx.compose.ui.Alignment
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

/**
 * Aprender: detalle del curso, lección con video, modo estudio y modo evaluación. [onOpenCourse] abre otro curso
 * (el «Siguiente curso» del resultado); si es nulo ese botón no se muestra.
 */
@Composable
fun TrainingScreen(viewModel: TrainingViewModel, modifier: Modifier = Modifier, onOpenCourse: ((String) -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // Cada cambio de pantalla interna vuelve al inicio del contenido.
    LaunchedEffect(state.screen, state.lessonId, state.study?.index, state.evaluation?.index, state.evaluation?.reviewing) { scroll.scrollTo(0) }
    // Vista general: todo el ancho del patrón. Lección y evaluación por preguntas con panel lateral (clase expandida en adelante):
    // también ancho completo; el resto conserva el ancho de lectura, centrado.
    val sideBySide = currentWindowClass == BpmWindowClass.Expanded || currentWindowClass == BpmWindowClass.Large
    val maxWidth = if (state.screen == TrainingPane.OVERVIEW || sideBySide && wantsWidePane(state)) BpmSpacing.contentMax else BpmPatternRules.readingWidth
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.fillMaxSize().widthIn(max = maxWidth).verticalScroll(scroll).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
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
            TrainingPane.EVALUATION -> state.evaluation?.let { EvaluationPanel(state, course, it, viewModel, onOpenCourse) }
        }
    }
    }
}

/**
 * Pantallas internas que usan panel lateral en clase expandida (temario de la lección, progreso de la evaluación) y por
 * eso necesitan todo el ancho: la lección y la evaluación mientras se responden. Revisión, resultado y cola usan lectura.
 */
internal fun wantsWidePane(state: TrainingState): Boolean = when (state.screen) {
    TrainingPane.LESSON -> true
    TrainingPane.EVALUATION -> state.evaluation?.let { it.outcome == null && !it.queued && !it.reviewing } ?: false
    else -> false
}

internal fun progressLabel(status: ProgressStatus) = when (status) {
    ProgressStatus.NOT_STARTED -> "Sin iniciar"
    ProgressStatus.IN_PROGRESS -> "En progreso"
    ProgressStatus.COMPLETED -> "Completado"
}

/** Evaluaciones enviadas desde este dispositivo: por calificar (sin conexión) o con la calificación del servidor. */
@Composable
internal fun AttemptsHistory(attempts: List<AttemptRecord>, passingScore: Int) {
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
internal fun DownloadsCard(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
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
            Text("${done} de ${videos.size} videos guardados · ${formatBytes(total)} en total", style = MaterialTheme.typography.titleSmall)
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
            // Cuatro opciones no caben en una fila a 360 dp: se envuelven en lugar de cortarse.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TrainingDownloads.LIMIT_CHOICES.forEach { choice ->
                    BpmTextButton(onClick = { viewModel.setDownloadLimit(choice) }) { Text((if (choice == downloads.limitBytes) "✓ " else "") + formatBytes(choice)) }
                }
            }
            if (downloads.usedBytes > 0) BpmTextButton(onClick = viewModel::deleteAllDownloads) { Text("Borrar descargas") }
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

/** Constancia del curso. Con [prominent] (resultado de la evaluación final) lleva icono y el código en grande. */
@Composable
internal fun CertificateCard(certificate: TrainingCertificate, prominent: Boolean = false) {
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("CONSTANCIA")
        if (prominent) Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            BpmIcon(BpmSymbol.Award, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text("Completaste este curso", style = MaterialTheme.typography.headlineSmall)
        } else Text("Completaste este curso", style = MaterialTheme.typography.titleMedium)
        Text("Código de verificación")
        Text(certificate.verificationCode, style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge)
        Text("Emitida: ${certificate.issuedAt.substringBefore('T')}", style = MaterialTheme.typography.bodySmall)
    }
}
