package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * «Guía del curso» del personal (el botón de libro del camino): el tomate con el resumen del curso, la acción para
 * empezar o continuar, «Lo esencial» de cada lección, las prácticas, las descargas para ver sin conexión, la constancia
 * y las evaluaciones enviadas. Sustituye a la vista general del curso solo para el rol WORKER.
 */
@Composable
internal fun PlayCourseGuide(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val info = course.course
    val total = course.lessons.size
    val hasQuestions = course.questions.isNotEmpty()
    course.cachedAt?.let { cachedAt ->
        Text("Copia guardada en este dispositivo (actualizada ${relativeAge(kotlin.time.Clock.System.now().toEpochMilliseconds(), cachedAt)}). Tu avance se enviará al recuperar la conexión.",
            style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    PendingBadge(info.id)
    BpmMascotRow(Res.drawable.mascot_class, buildString {
        append(info.title); append(": "); append(if (total == 1) "1 lección" else "$total lecciones"); append(" · ${info.estimatedDurationMinutes} min")
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${info.code} · aprueba con ${course.version.passingScore}", style = BpmPlayType.label, color = BpmPlayColors.action.face)
        Text(info.title, style = BpmPlayType.h1, color = BpmPlayColors.ink)
        if (info.description.isNotBlank()) Text(info.description, style = BpmPlayType.sub, color = BpmPlayColors.muted)
    }
    if (total > 0) BpmChunkyButton(courseMainActionLabel(course), viewModel::resume, Modifier.fillMaxWidth())
    Text("Lo esencial", style = BpmPlayType.h2, color = BpmPlayColors.ink)
    if (course.lessons.isEmpty()) Text("Este curso todavía no tiene lecciones publicadas.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    val done = course.progress.completedLessonIds.toSet()
    course.lessons.forEachIndexed { index, lesson ->
        BpmPlayCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Lección ${index + 1}", style = BpmPlayType.label, color = BpmPlayColors.action.face, modifier = Modifier.weight(1f))
                if (lesson.id in done || course.progress.status == ProgressStatus.COMPLETED) BpmIcon(BpmSymbol.Check, tint = BpmPlayColors.correct.face)
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
    if (hasQuestions) BpmChunkyButton("Practicar sin calificación", { viewModel.startStudy(repeatMissed = true) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    if (hasQuestions && state.canPersist) BpmChunkyButton("Evaluación diagnóstica", { viewModel.startEvaluation(AttemptType.DIAGNOSTIC) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    if (!state.canPersist) Text("Tu cuenta no tiene una membresía en esta organización: puedes leer y practicar, pero la evaluación y el avance solo están disponibles para sus miembros.",
        style = BpmPlayType.sub, color = BpmPlayColors.muted)
    DownloadsCard(course, state, viewModel)
    course.certificate?.let { CertificateCard(it) }
    AttemptsHistory(state.attempts, course.version.passingScore)
}
