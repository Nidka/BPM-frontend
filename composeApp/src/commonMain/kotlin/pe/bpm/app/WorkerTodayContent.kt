package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAssignment
import pe.bpm.features.training.presentation.TrainingLearnerViewModel
import pe.bpm.features.training.presentation.assignmentChip
import pe.bpm.features.training.presentation.assignmentsForToday
import pe.bpm.features.training.presentation.criterionLabel
import pe.bpm.features.training.presentation.nextAssignment
import pe.bpm.features.training.presentation.readableDate
import pe.bpm.features.training.presentation.trainingFailureMessage

/**
 * Pantalla «Hoy» del personal (WORKER). Solo muestra datos reales: la asignación que toca continuar o empezar,
 * dos accesos del turno (limpieza e incidencias), las capacitaciones asignadas y el resumen de logros.
 * No hay racha ni lista de tareas con hora: el backend aún no las ofrece.
 *
 * Pide al [learner] las asignaciones y los logros al abrirse; en compacta el orden es siguiente paso, tu turno,
 * asignadas, recompensas y reportar; desde media los logros y «Reportar un problema» van en el panel lateral.
 */
@Composable
internal fun WorkerTodayContent(
    learner: TrainingLearnerViewModel,
    userId: String,
    organizationId: String,
    onOpenCourse: (String) -> Unit,
    onLearn: () -> Unit,
    onTasks: () -> Unit,
    onReport: () -> Unit,
    onAchievements: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(userId, organizationId) {
        learner.open(userId, organizationId, member = true)
        learner.loadAchievements()
    }
    val state by learner.state.collectAsStateWithLifecycle()
    val next = nextAssignment(state.assignments)
    val achievements = state.achievements
    BpmSupportPaneLayout(
        modifier,
        support = {
            if (achievements != null) {
                BpmRewardSummary(
                    experiencePoints = achievements.totals.experiencePoints,
                    earnedBadges = achievements.earned.size,
                    totalBadges = achievements.achievements.size,
                    nextBadgeTitle = achievements.pending.firstOrNull()?.name,
                    nextBadgeHint = achievements.pending.firstOrNull()?.let { criterionLabel(it) },
                    onOpen = onAchievements,
                )
            }
            BpmSecondaryButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                BpmIcon(BpmSymbol.Camera, modifier = Modifier.size(20.dp))
                Text("Reportar un problema")
            }
        },
        main = {
            state.assignmentsFailure?.let { failure ->
                BpmInlineNotice(trainingFailureMessage(failure), onDismiss = learner::refreshAssignments, dismissLabel = "Reintentar", warning = true)
            }
            when {
                next != null -> NextStep(next, onOpenCourse)
                !state.assignmentsLoaded && state.assignmentsFailure == null -> BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                    Eyebrow("TU SIGUIENTE PASO")
                    Text("Buscando tus capacitaciones…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.assignmentsLoaded -> AllCaughtUp(onLearn)
            }
            BpmCard(Modifier.fillMaxWidth()) {
                Text("Tu turno", style = MaterialTheme.typography.titleMedium)
                BpmTaskRow("Registrar limpieza", BpmSymbol.Control, detail = "Anota las tareas de limpieza de tu sede", onClick = onTasks)
                BpmTaskRow("Incidencias y correcciones", BpmSymbol.Alert, detail = "Reporta un problema o sube la foto de una corrección", tone = BpmTone.Warning, onClick = onReport)
            }
            if (state.assignments.isNotEmpty()) {
                BpmCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Capacitaciones asignadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (state.assignments.size > 3) BpmTextButton(onLearn) { Text("Ver todas") }
                    }
                    assignmentsForToday(state.assignments).forEach { assignment ->
                        val (chip, tone) = assignmentChip(assignment)
                        BpmTaskRow(
                            assignment.course.title, BpmSymbol.Book,
                            detail = assignment.course.code,
                            tone = if (assignment.status == AssignmentProgress.COMPLETED) BpmTone.Success else BpmTone.Info,
                            trailing = { BpmStatusChip(chip, tone) },
                            onClick = { onOpenCourse(assignment.course.id) },
                        )
                    }
                }
            }
        },
    )
}

/** Tarjeta principal: continuar la capacitación en curso o empezar la más urgente. */
@Composable
private fun NextStep(assignment: MyAssignment, onOpenCourse: (String) -> Unit) {
    val started = assignment.status == AssignmentProgress.IN_PROGRESS || assignment.progressStatus == pe.bpm.features.training.domain.ProgressStatus.IN_PROGRESS
    val due = assignment.dueAt?.let { readableDate(it) }?.takeIf { it.isNotEmpty() }
    BpmNextStepCard(
        eyebrow = "TU SIGUIENTE PASO",
        title = (if (started) "Continúa: " else "Empieza: ") + assignment.course.title,
        actionLabel = if (started) "Continuar curso" else "Empezar curso",
        onAction = { onOpenCourse(assignment.course.id) },
        supporting = due?.let { (if (assignment.status == AssignmentProgress.OVERDUE) "Venció el " else "Vence el ") + it },
    )
}

/** Sin capacitaciones pendientes: refuerzo positivo y salida hacia el catálogo. */
@Composable
private fun AllCaughtUp(onLearn: () -> Unit) {
    BpmCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
                Text("Todo al día", style = MaterialTheme.typography.titleLarge)
                Text("No tienes capacitaciones pendientes. Puedes seguir aprendiendo cuando quieras.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BpmSecondaryButton(onClick = onLearn) { Text("Explorar cursos") }
            }
            Mascot(Modifier.size(96.dp))
        }
    }
}
