package pe.bpm.features.training.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import pe.bpm.core.offline.LocalOnline
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.offline.PendingBadge
import pe.bpm.core.offline.relativeAge
import pe.bpm.features.training.domain.*
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_class

/** Qué abrir al llegar a un curso desde el camino de Aprender: una lección o la evaluación final. */
sealed interface CourseIntent {
    /** Abre la lección [lessonId] (la siguiente o una ya hecha para repasarla). */
    data class Lesson(val lessonId: String) : CourseIntent
    /** Abre la evaluación final. */
    data object FinalEvaluation : CourseIntent
    /** Abre el repaso espaciado del curso (las preguntas falladas o vencidas). */
    data object Review : CourseIntent
    /** Retoma el curso: abre la siguiente lección (la última vista o la primera), sin pasar por la guía. */
    data object Resume : CourseIntent
}

/**
 * Contexto de un curso para el rol WORKER (juego): salir al camino ([onClose]), la racha del día para la celebración,
 * la posición del curso en el camino (resultado), qué abrir al llegar ([intent], que [onIntentHandled] consume) y el
 * aviso de que se completó una lección ([onLessonCompleted], para refrescar la racha). Sin este contexto el curso se
 * muestra con las pantallas de siempre (ADMIN, CHEF).
 */
class WorkerCourseContext(
    val onClose: () -> Unit,
    val streakDays: Int? = null,
    val moduleNumber: Int? = null,
    val moduleTotal: Int? = null,
    val intent: CourseIntent? = null,
    val onIntentHandled: () -> Unit = {},
    val onLessonCompleted: () -> Unit = {},
)

/** Pantallas del curso en modo enfoque para el personal: lección en pasos, práctica y evaluación. */
@Composable
internal fun PlayCoursePane(state: TrainingState, course: TrainingCourse, viewModel: TrainingViewModel, worker: WorkerCourseContext,
    onOpenCourse: ((String) -> Unit)?) {
    when (state.screen) {
        TrainingPane.LESSON -> state.lesson?.let { PlayLesson(state, course, it, viewModel, worker) }
        TrainingPane.STUDY -> state.study?.let { PlayPractice(state, course, it, viewModel, worker) }
        TrainingPane.EVALUATION -> state.evaluation?.let { PlayEvaluation(state, course, it, viewModel, worker, onOpenCourse) }
        TrainingPane.OVERVIEW -> Unit
    }
}

/**
 * Hoja del curso del personal (la vista general): centrada en la columna de 600 dp del juego, con el aviso, el fallo y
 * la guía en el estilo de juego. Con una intención pendiente ([WorkerCourseContext.intent]) no muestra la guía: la
 * lección o la evaluación se abre apenas carga el curso.
 */
@Composable
internal fun PlayCourseSheet(state: TrainingState, viewModel: TrainingViewModel, worker: WorkerCourseContext, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize().verticalScroll(scroll).bpmAppear()) {
        BpmPlayColumns { _ ->
            if (state.loading && state.course == null) BpmSkeletonDetail(label = "Cargando el curso")
            state.failure?.let { failure ->
                Text(trainingFailureMessage(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                if (failure.retryable || state.course == null) BpmChunkyButton("Volver a intentar", viewModel::refresh, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
            state.notice?.let { notice ->
                BpmPlayCard {
                    Text(notice, style = BpmPlayType.text, color = BpmPlayColors.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    BpmChunkyButton("Entendido", viewModel::dismissNotice, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                }
            }
            val course = state.course
            if (course != null && worker.intent == null) PlayCourseGuide(course, state, viewModel)
        }
    }
}

/**
 * «Guía del curso» del personal (el botón de libro del camino): el tomate con el resumen del curso, la acción para
 * empezar o continuar, «Lo esencial» de cada lección (tocar una abre esa lección), las prácticas, las descargas para
 * ver sin conexión, la constancia y las evaluaciones enviadas. Sustituye a la vista general del curso solo para el rol
 * WORKER. Va dentro de la columna de [BpmPlayColumns], por eso no pone sus propios márgenes.
 */
@Composable
internal fun ColumnScope.PlayCourseGuide(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val info = course.course
    val total = course.lessons.size
    val hasQuestions = course.questions.isNotEmpty()
    course.cachedAt?.let { cachedAt ->
        Text("Copia guardada en este dispositivo (actualizada ${relativeAge(kotlin.time.Clock.System.now().toEpochMilliseconds(), cachedAt)}). Tu avance se enviará al recuperar la conexión.",
            style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    PendingBadge(info.id)
    BpmMascotRow(Res.drawable.mascot_class, buildString {
        append(if (total == 1) "1 lección" else "$total lecciones"); append(" · ${info.estimatedDurationMinutes} min")
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Se aprueba con ${course.version.passingScore} %", style = BpmPlayType.label, color = BpmPlayColors.action.face)
        Text(info.title, style = BpmPlayType.h1, color = BpmPlayColors.ink)
        if (info.description.isNotBlank()) Text(info.description, style = BpmPlayType.sub, color = BpmPlayColors.muted)
    }
    if (total > 0) BpmChunkyButton(courseMainActionLabel(course), viewModel::resume, Modifier.fillMaxWidth())
    Text("Lo esencial", style = BpmPlayType.h2, color = BpmPlayColors.ink)
    BpmListenButton(info.title + ". " + info.description + " " + course.lessons.mapIndexed { i, l -> "Lección ${i + 1}: ${l.title}." }.joinToString(" "), label = "Escuchar el curso")
    if (course.lessons.isEmpty()) Text("Este curso todavía no tiene lecciones publicadas.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    val done = course.progress.completedLessonIds.toSet()
    course.lessons.forEachIndexed { index, lesson ->
        val finished = lesson.id in done || course.progress.status == ProgressStatus.COMPLETED
        BpmPlayCard(Modifier.clip(RoundedCornerShape(18.dp)).semantics { contentDescription = "Abrir la lección ${index + 1}: ${lesson.title}" }
            .clickable(role = Role.Button) { viewModel.selectLesson(lesson.id) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Lección ${index + 1}", style = BpmPlayType.label, color = BpmPlayColors.action.face, modifier = Modifier.weight(1f))
                if (finished) BpmIcon(BpmSymbol.Check, tint = BpmPlayColors.correct.face)
                BpmIcon(BpmSymbol.ChevronRight, tint = BpmPlayColors.muted)
            }
            Text(lesson.title, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
            if (lesson.keyPoints.isEmpty()) Text(if (lesson.video != null || lesson.externalVideo != null) "Incluye video" else "Lectura", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
            lesson.keyPoints.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = BpmPlayType.rowTitle, color = BpmPlayColors.action.face)
                    Text(point, Modifier.weight(1f), style = BpmPlayType.rowDetail, color = BpmPlayColors.ink)
                }
            }
        }
    }
    if (course.review.dueCount > 0 && state.canPersist) BpmChunkyButton(reviewCaption(course.review.dueCount), viewModel::startReview, Modifier.fillMaxWidth())
    if (hasQuestions) BpmChunkyButton("Practicar sin calificación", { viewModel.startStudy(repeatMissed = true) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    if (hasQuestions && state.canPersist) BpmChunkyButton("Evaluación diagnóstica", { viewModel.startEvaluation(AttemptType.DIAGNOSTIC) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    if (!state.canPersist) Text("Tu cuenta no tiene una membresía en esta organización: puedes leer y practicar, pero la evaluación y el avance solo están disponibles para sus miembros.",
        style = BpmPlayType.sub, color = BpmPlayColors.muted)
    PlayDownloads(course, state, viewModel)
    course.certificate?.let { certificate ->
        BpmPlayCard(dashed = true) {
            Text("CONSTANCIA", Modifier.background(BpmPlayColors.correct.soft, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp), style = BpmPlayType.statLabel, color = BpmPlayColors.correct.shadow)
            Text("Completaste este curso", style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
            Text(groupVerificationCode(certificate.verificationCode), style = BpmPlayType.sub.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp), color = BpmPlayColors.muted,
                modifier = Modifier.semantics { contentDescription = "Código de verificación: ${certificate.verificationCode.toList().joinToString(" ")}" })
            Text("Emitida: ${certificate.issuedAt.substringBefore('T')}", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
        }
    }
    PlayAttempts(state.attempts, course.version.passingScore)
}

/** «Ver sin conexión» en el estilo de juego. En la web no se muestra: los videos solo se guardan en la aplicación móvil. */
@Composable
private fun PlayDownloads(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val downloads = state.downloads
    val videos = downloadableVideos(course)
    if (!downloads.supported || videos.isEmpty()) return
    val online = LocalOnline.current
    val busy = downloads.activeCourseId == course.course.id
    val saved = videos.count { it.offlineAvailable && downloads.downloaded(it.video!!.mediaId) }
    val issue = videos.mapNotNull { downloads.of(it.video!!.mediaId)?.issue }.firstOrNull()
    BpmPlayCard {
        Text("Ver sin conexión", style = BpmPlayType.h2, color = BpmPlayColors.ink)
        Text(if (busy) "Descargando…" else "$saved de ${videos.size} videos guardados · ${formatBytes(videos.sumOf { it.video!!.sizeBytes })}", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        issue?.let { Text(downloadIssueMessage(it, downloads.limitBytes), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (busy) BpmChunkyButton("Pausar descarga", viewModel::cancelDownload, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        else if (saved < videos.size) BpmChunkyButton("Descargar", viewModel::downloadCourse, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = online)
        if (!busy && saved == videos.size) Text("Descargado, disponible sin conexión.", style = BpmPlayType.sub, color = BpmPlayColors.correct.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (!busy && saved > 0) BpmChunkyButton("Borrar descargas", viewModel::deleteCourseDownloads, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}

/** Evaluaciones enviadas desde este dispositivo, en el estilo de juego: por calificar (sin conexión) o con la nota del servidor. */
@Composable
private fun PlayAttempts(attempts: List<AttemptRecord>, passingScore: Int) {
    if (attempts.isEmpty()) return
    BpmPlayCard {
        Text("Evaluaciones enviadas", style = BpmPlayType.h2, color = BpmPlayColors.ink)
        attempts.take(5).forEach { record ->
            val kind = when (record.type) { AttemptType.FINAL -> "Evaluación final"; AttemptType.MODULE -> "Evaluación de lección"; else -> "Evaluación diagnóstica" }
            val result = record.result
            Text(if (result == null) "$kind: enviada, se calificará al conectarse" else "$kind: ${result.score} de 100 · ${if (result.passed) "Aprobado" else "No aprobado (mínimo $passingScore)"}",
                style = BpmPlayType.rowDetail, color = BpmPlayColors.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
