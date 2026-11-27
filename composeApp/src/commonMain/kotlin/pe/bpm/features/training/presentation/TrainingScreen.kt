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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
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
 * (el «Siguiente curso» del resultado); si es nulo ese botón no se muestra. Con [worker] (rol WORKER) la lección, la
 * práctica, la evaluación y su resultado usan las pantallas de juego (`PlayCoursePane`) y la vista general es la guía del curso.
 */
// patrón: detalle de curso alojado por TrainingCourseContent; el marco del shell pone los márgenes y aquí solo se centra la lectura o se usa todo el ancho.
@Composable
fun TrainingScreen(viewModel: TrainingViewModel, modifier: Modifier = Modifier, onOpenCourse: ((String) -> Unit)? = null, worker: WorkerCourseContext? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    // Cada cambio de pantalla interna vuelve al inicio del contenido.
    LaunchedEffect(state.screen, state.lessonId, state.study?.index, state.evaluation?.index, state.evaluation?.reviewing) { scroll.scrollTo(0) }
    // Vista general: todo el ancho del patrón. Lección y evaluación por preguntas con panel lateral (clase expandida en adelante):
    // columna de lectura (720 dp) más panel (300 dp) centrados como grupo (regla G3); el resto, solo la columna de lectura.
    val sideBySide = currentWindowClass == BpmWindowClass.Expanded || currentWindowClass == BpmWindowClass.Large
    val playCourse = state.course
    if (worker != null && playCourse != null && state.screen != TrainingPane.OVERVIEW) {
        PlayCoursePane(state, playCourse, viewModel, worker, onOpenCourse)
        return
    }
    // Hoja del curso del personal: columna central de juego, sin las tarjetas del resto de roles.
    if (worker != null && state.screen == TrainingPane.OVERVIEW) {
        PlayCourseSheet(state, viewModel, worker, modifier)
        return
    }
    val maxWidth = when {
        worker != null -> BpmPatternRules.readingWidth
        state.screen == TrainingPane.OVERVIEW -> BpmSpacing.contentMax
        sideBySide && wantsWidePane(state) -> BpmPatternRules.readingWidth + BpmSpacing.grid + 300.dp
        else -> BpmPatternRules.readingWidth
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = maxWidth).fillMaxSize().verticalScroll(scroll).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.loading && state.course == null) BpmSkeletonCard(lines = 3)
        state.failure?.let { failure ->
            BpmCard(Modifier.fillMaxWidth()) {
                Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (failure.retryable || state.course == null) BpmChunkyButton("Volver a intentar", viewModel::refresh, tone = BpmChunkyTone.White)
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

/**
 * Modo enfoque (regla G4): lección, estudio, evaluación, revisión y resultado ocultan la navegación del shell (barra
 * inferior, riel y menú lateral) en todos los anchos; solo la vista general del curso la conserva.
 */
internal fun isFocusMode(state: TrainingState): Boolean = state.screen != TrainingPane.OVERVIEW

/**
 * Salir de una evaluación que todavía no se envió (preguntas o revisión, sin resultado ni envío en curso ni en cola)
 * pide confirmación porque se pierden las respuestas del intento.
 */
internal fun needsExitConfirmation(state: TrainingState): Boolean {
    val evaluation = state.evaluation ?: return false
    return state.screen == TrainingPane.EVALUATION && evaluation.outcome == null && !evaluation.queued && !evaluation.submitting
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

/** «Ver sin conexión» como tarjeta propia (panel lateral de la vista general en clase expandida). */
@Composable
internal fun DownloadsCard(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    if (!hasOfflineVideos(course)) return
    BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) { DownloadsRow(course, state, viewModel) }
}

internal fun hasOfflineVideos(course: TrainingCourse) = course.lessons.any { it.video != null || it.externalVideo != null }

/**
 * «Ver sin conexión» como una fila plana de una línea (regla G9): icono, título y un resumen («2 de 3 videos guardados
 * · 18 MB»). El detalle (descarga, espacio usado, límites y borrado) solo se muestra al expandir la fila; se expande
 * solo si la descarga tuvo un problema para que el aviso no pase desapercibido.
 */
@Composable
internal fun DownloadsRow(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val downloads = state.downloads
    // Solo cuentan los videos que el servidor permite guardar (offlineAvailable y con huella); los demás requieren conexión.
    // Antes se contaban todos: «Descargar» seguía visible y no hacía nada porque no quedaba ninguno descargable.
    val videos = downloadableVideos(course)
    val external = course.lessons.count { it.externalVideo != null || it.video != null && it !in videos }
    if (videos.isEmpty() && external == 0) return
    val online = LocalOnline.current
    val issue = videos.mapNotNull { downloads.of(it.video!!.mediaId)?.issue }.firstOrNull()
    val busy = downloads.activeCourseId == course.course.id
    val total = videos.sumOf { it.video!!.sizeBytes }
    val done = videos.count { it.offlineAvailable && downloads.downloaded(it.video!!.mediaId) }
    var open by rememberSaveable { mutableStateOf(issue != null) }
    val summary = when {
        issue != null -> "Hubo un problema con la descarga"
        !downloads.supported -> "Solo en la aplicación móvil"
        busy -> "Descargando…"
        videos.isNotEmpty() -> "$done de ${videos.size} videos guardados · ${formatBytes(total)}"
        else -> "Los videos de YouTube necesitan conexión"
    }
    Column(Modifier.fillMaxWidth()) {
        BpmTaskRow("Ver sin conexión", BpmSymbol.Download, detail = summary, framed = false, detailMaxLines = 1,
            modifier = Modifier.semantics { stateDescription = if (open) "Desplegado" else "Plegado" }, onClick = { open = !open })
        if (open) Column(Modifier.fillMaxWidth().padding(top = BpmSpacing.sm), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
            if (!downloads.supported) {
                Text("En este navegador los videos no se guardan para verlos sin conexión. Usa la aplicación móvil para descargarlos. El texto de las lecciones sí está disponible sin conexión.",
                    style = MaterialTheme.typography.bodyMedium)
            } else if (videos.isNotEmpty()) {
                if (busy) {
                    val progress = videos.mapNotNull { downloads.of(it.video!!.mediaId) }.sumOf { it.downloadedBytes }
                    LinearProgressIndicator(progress = { if (total > 0) (progress.toFloat() / total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("Descargando… ${formatBytes(progress)} de ${formatBytes(total)}", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    BpmTextButton(onClick = viewModel::cancelDownload) { Text("Pausar descarga") }
                } else if (done < videos.size) {
                    BpmChunkyButton(if (downloads.videos.values.any { it.status == VideoDownloadStatus.PAUSED }) "Reanudar descarga" else "Descargar para ver sin conexión", viewModel::downloadCourse, Modifier.bpmPrimaryButtonWidth(), tone = BpmChunkyTone.White, enabled = online)
                    if (!online) Text("Necesitas conexión para descargar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                issue?.let {
                    Text(downloadIssueMessage(it, downloads.limitBytes), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (!busy && done == videos.size) Text("Descargado, disponible sin conexión.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (done > 0) BpmTextButton(onClick = viewModel::deleteCourseDownloads) { Text("Borrar descargas de este curso") }
            }
            if (external > 0) Text("$external lección(es) tienen videos que requieren conexión y no se pueden descargar.", style = MaterialTheme.typography.bodySmall,
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
}

internal fun downloadIssueMessage(issue: DownloadIssue, limit: Long) = when (issue) {
    DownloadIssue.NETWORK -> "La descarga se interrumpió. Lo ya guardado se conserva: pulsa Reanudar cuando tengas conexión."
    DownloadIssue.CORRUPT -> "El video descargado no coincide con el original y se descartó. Vuelve a intentarlo."
    DownloadIssue.LIMIT -> "No hay espacio dentro del límite de ${formatBytes(limit)}. Borra descargas o aumenta el límite."
    DownloadIssue.UNSUPPORTED -> "Este dispositivo no permite guardar videos."
    DownloadIssue.TICKET -> "No pudimos obtener el video. Revisa tu conexión y vuelve a intentarlo."
}

/** Código de verificación en grupos de 4 («K7M2 QXD4 …»): se lee y se dicta mejor que 24 caracteres seguidos. */
internal fun groupVerificationCode(code: String): String = code.chunked(4).joinToString(" ")

/** Constancia del curso. Con [prominent] (resultado de la evaluación final) lleva icono y el código en grande. */
@Composable
internal fun CertificateCard(certificate: TrainingCertificate, prominent: Boolean = false) {
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("CONSTANCIA")
        if (prominent) Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            BpmIcon(BpmSymbol.Award, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text("Completaste este curso", style = MaterialTheme.typography.headlineSmall)
        } else Text("Completaste este curso", style = MaterialTheme.typography.titleMedium)
        Text("Código de verificación", style = MaterialTheme.typography.bodySmall)
        Text(groupVerificationCode(certificate.verificationCode), style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.semantics { contentDescription = "Código de verificación: ${certificate.verificationCode.toList().joinToString(" ")}" })
        Text("Emitida: ${certificate.issuedAt.substringBefore('T')}", style = MaterialTheme.typography.bodySmall)
    }
}

/** Videos de un curso que se pueden guardar en el teléfono: privados, marcados para uso sin conexión y con huella SHA-256. */
internal fun downloadableVideos(course: TrainingCourse) = course.lessons.filter { it.offlineAvailable && it.video?.sha256 != null }
