package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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

private const val FINAL_STEP_ID = "final-evaluation"

/**
 * Vista general del curso: cabecera, temario como lista de pasos con la evaluación final al final y, al lado
 * (o debajo en compacta), el avance, la acción principal, descargas, constancia e historial de intentos.
 * El avance por lección es una aproximación ([lessonStepStates]); no se muestran duraciones por lección porque
 * el modelo no las tiene. En compacta la acción principal también se publica en la barra fija ([BpmActionBar]).
 */
@Composable
internal fun CourseOverview(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val canPersist = state.canPersist
    val info = course.course
    val compact = currentWindowClass == BpmWindowClass.Compact
    val states = lessonStepStates(course)
    val total = course.lessons.size
    val done = states.count { it == BpmStepState.Done }
    val actionLabel = courseMainActionLabel(course)
    val hasQuestions = course.questions.isNotEmpty()
    val started = course.progress.lastLessonId != null && course.progress.status != ProgressStatus.COMPLETED

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        course.cachedAt?.let { cachedAt ->
            Text("Estás viendo la copia guardada en este dispositivo (actualizada ${relativeAge(kotlin.time.Clock.System.now().toEpochMilliseconds(), cachedAt)}). El avance y las evaluaciones se enviarán al recuperar la conexión.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        PendingBadge(info.id)
        Eyebrow("${info.code} · ${info.estimatedDurationMinutes} min · ${if (total == 1) "1 lección" else "$total lecciones"}")
        Text(info.title, style = MaterialTheme.typography.headlineMedium)
        if (info.description.isNotBlank()) Text(info.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val progressText: @Composable () -> Unit = {
        Text(progressLabel(course.progress.status), style = BpmType.bodyStrong)
        course.progress.bestScore?.let { Text("Mejor puntaje en evaluación final: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (course.progress.experiencePoints > 0) Text("${course.progress.experiencePoints} XP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Se aprueba con ${course.version.passingScore} puntos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val ringDescription = "Avance del curso: $done de $total lecciones"

    BpmSupportPaneLayout(
        support = {
            if (compact) {
                if (hasQuestions) BpmTaskRow("Practicar sin calificación", BpmSymbol.Play, detail = "Responde preguntas y mira al instante si acertaste", onClick = { viewModel.startStudy() })
            } else BpmCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                    if (total > 0) {
                        BpmProgressRing(done.toFloat() / total, "$done/$total", ringDescription, size = 96.dp, strokeWidth = 9.dp)
                        Text("$done de $total lecciones", style = MaterialTheme.typography.titleMedium)
                    }
                    progressText()
                }
                if (total > 0) RaisedButton(actionLabel, viewModel::resume, Modifier.fillMaxWidth())
                BpmTextButton(onClick = { viewModel.startStudy() }, enabled = hasQuestions, modifier = Modifier.fillMaxWidth()) { Text("Practicar sin calificación") }
            }
            DownloadsCard(course, state, viewModel)
            val certificate = course.certificate
            if (certificate != null) CertificateCard(certificate)
            else if (canPersist) BpmCard(Modifier.fillMaxWidth()) {
                Eyebrow("CONSTANCIA")
                Text("Tu constancia aparecerá aquí cuando apruebes la evaluación final.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AttemptsHistory(state.attempts, course.version.passingScore)
        },
        main = {
            if (compact && total > 0) BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    BpmProgressRing(done.toFloat() / total, "$done/$total", ringDescription, size = 56.dp, strokeWidth = 6.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                        Text("$done de $total lecciones", style = MaterialTheme.typography.titleMedium)
                        progressText()
                    }
                }
            }
            BpmCard(Modifier.fillMaxWidth()) {
                Text("Temario", style = MaterialTheme.typography.titleMedium)
                if (course.lessons.isEmpty()) Text("Este curso todavía no tiene lecciones publicadas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val steps = course.lessons.mapIndexed { index, lesson ->
                    BpmStep(lesson.id, lesson.title, lessonDetail(lesson, state), states[index],
                        badge = if (states[index] == BpmStepState.Current && started) "Última vista" else null)
                } + if (canPersist && hasQuestions) listOf(BpmStep(FINAL_STEP_ID, "Evaluación final", "Aprueba con ${course.version.passingScore} · emite constancia",
                    if (course.progress.status == ProgressStatus.COMPLETED) BpmStepState.Done else BpmStepState.Pending, highlight = true)) else emptyList()
                BpmStepList(steps, onStep = { step -> if (step.id == FINAL_STEP_ID) viewModel.startEvaluation(AttemptType.FINAL) else viewModel.selectLesson(step.id) })
                if (canPersist) BpmTextButton(onClick = { viewModel.startEvaluation(AttemptType.DIAGNOSTIC) }, enabled = hasQuestions) { Text("Evaluación diagnóstica") }
                else Text("Tu cuenta no tiene una membresía en esta organización: puedes leer y practicar, pero la evaluación y el avance solo están disponibles para sus miembros.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
    if (compact && total > 0) BpmActionBar { RaisedButton(actionLabel, viewModel::resume, Modifier.fillMaxWidth()) }
}

/** Qué incluye la lección, según lo que el modelo sabe (no hay duración por lección). */
private fun lessonDetail(lesson: TrainingLesson, state: TrainingState): String = when {
    lesson.externalVideo != null -> "Video de YouTube · requiere conexión"
    lesson.video != null -> if (state.downloads.downloaded(lesson.video.mediaId)) "Incluye video · guardado en este dispositivo" else "Incluye video"
    else -> "Lectura"
}
