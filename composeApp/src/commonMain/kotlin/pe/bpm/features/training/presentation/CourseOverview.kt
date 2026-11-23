package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
 * Vista general del curso. Clase compacta y media (una sola columna, regla G2): tarjeta de avance compacta (anillo,
 * «2 de 5 lecciones» y chips «Mejor 72», «60 XP», «Aprueba con 80»), temario y una tarjeta «Más opciones» de filas
 * planas (practicar sin calificación, ver sin conexión en una línea, constancia). Desde la expandida el temario va a la
 * izquierda y, en el panel, el avance con la acción principal, las descargas (resumidas, G9), la constancia y el historial.
 * El avance por lección sale de `completedLessonIds` del servidor y, si no lo envía, de la aproximación de
 * [lessonStepStates]; el anillo usa el porcentaje del servidor ([courseRing]). No se muestran duraciones por lección
 * porque el modelo no las tiene. En compacta la acción principal se publica en la barra fija ([BpmActionBar]); en media
 * va dentro de la tarjeta de avance.
 */
@Composable
internal fun CourseOverview(course: TrainingCourse, state: TrainingState, viewModel: TrainingViewModel) {
    val canPersist = state.canPersist
    val info = course.course
    val compact = currentWindowClass == BpmWindowClass.Compact
    val singleColumn = !sideBySideClass
    val states = lessonStepStates(course)
    val total = course.lessons.size
    val done = states.count { it == BpmStepState.Done }
    val actionLabel = courseMainActionLabel(course)
    val hasQuestions = course.questions.isNotEmpty()
    val started = course.progress.lastLessonId != null && course.progress.status != ProgressStatus.COMPLETED
    val ring = courseRing(course)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        course.cachedAt?.let { cachedAt ->
            Text("Copia guardada en este dispositivo (actualizada ${relativeAge(kotlin.time.Clock.System.now().toEpochMilliseconds(), cachedAt)}). Tu avance se enviará al recuperar la conexión.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        PendingBadge(info.id)
        Eyebrow("${info.code} · ${info.estimatedDurationMinutes} min · ${if (total == 1) "1 lección" else "$total lecciones"}")
        Text(info.title, style = MaterialTheme.typography.headlineMedium)
        if (info.description.isNotBlank()) Text(info.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    val syllabus: @Composable ColumnScope.() -> Unit = {
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
    }

    if (singleColumn) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (total > 0 && ring != null) BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                BpmProgressRing(ring.progress, ring.label, ring.description, size = 56.dp, strokeWidth = 6.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                    Text("$done de $total lecciones", style = MaterialTheme.typography.titleMedium)
                    ProgressChips(course)
                }
            }
            if (!compact) BpmInlineActions(primary = { BpmChunkyButton(actionLabel, viewModel::resume, Modifier.fillMaxWidth()) })
        }
        syllabus()
        course.certificate?.let { CertificateCard(it) }
        BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
            Text("Más opciones", style = MaterialTheme.typography.titleMedium)
            val rows = buildList<@Composable () -> Unit> {
                if (hasQuestions) add { BpmTaskRow("Practicar sin calificación", BpmSymbol.Play, detail = "Responde preguntas y mira al instante si acertaste",
                    framed = false, onClick = { viewModel.startStudy() }) }
                if (hasOfflineVideos(course)) add { DownloadsRow(course, state, viewModel) }
                if (canPersist && course.certificate == null) add { BpmTaskRow("Constancia", BpmSymbol.Lock, detail = "Se desbloquea al aprobar la evaluación final", framed = false, tone = BpmTone.Neutral) }
            }
            rows.forEachIndexed { i, row -> if (i > 0) BpmRowDivider(); row() }
        }
        AttemptsHistory(state.attempts, course.version.passingScore)
    } else BpmSupportPaneLayout(
        support = {
            BpmCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                    if (total > 0 && ring != null) {
                        BpmProgressRing(ring.progress, ring.label, ring.description, size = 96.dp, strokeWidth = 9.dp)
                        Text("$done de $total lecciones", style = MaterialTheme.typography.titleMedium)
                    }
                    ProgressChips(course, centered = true)
                }
                if (total > 0) BpmChunkyButton(actionLabel, viewModel::resume, Modifier.fillMaxWidth())
                BpmTextButton(onClick = { viewModel.startStudy() }, enabled = hasQuestions, modifier = Modifier.fillMaxWidth()) { Text("Practicar sin calificación") }
            }
            DownloadsCard(course, state, viewModel)
            val certificate = course.certificate
            if (certificate != null) CertificateCard(certificate)
            else if (canPersist) BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                BpmTaskRow("Constancia", BpmSymbol.Lock, detail = "Se desbloquea al aprobar la evaluación final", framed = false, tone = BpmTone.Neutral)
            }
            AttemptsHistory(state.attempts, course.version.passingScore)
        },
        main = syllabus,
    )
    if (compact && total > 0) BpmActionBar { BpmChunkyButton(actionLabel, viewModel::resume, Modifier.fillMaxWidth()) }
}

/** Chips de avance: «Completado» (solo si lo está), «Mejor 72», «60 XP» y «Aprueba con 80». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressChips(course: TrainingCourse, centered: Boolean = false) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        if (course.progress.status == ProgressStatus.COMPLETED) BpmStatusChip("Completado", BpmTone.Success, symbol = BpmSymbol.Check)
        course.progress.bestScore?.let { BpmStatusChip("Mejor $it", BpmTone.Info) }
        if (course.progress.experiencePoints > 0) BpmStatusChip("${course.progress.experiencePoints} XP", BpmTone.Info)
        BpmStatusChip("Aprueba con ${course.version.passingScore}", BpmTone.Neutral)
    }
}

/** Qué incluye la lección, según lo que el modelo sabe (no hay duración por lección). */
private fun lessonDetail(lesson: TrainingLesson, state: TrainingState): String = when {
    lesson.externalVideo != null -> "Video de YouTube · requiere conexión"
    lesson.video != null -> if (state.downloads.downloaded(lesson.video.mediaId)) "Incluye video · guardado en este dispositivo" else "Incluye video"
    else -> "Lectura"
}
