package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
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
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.operations.presentation.TodayPlanRow
import pe.bpm.features.operations.presentation.homeTodayPlans
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAssignment
import pe.bpm.features.training.presentation.TrainingLearnerViewModel
import pe.bpm.features.training.presentation.assignmentChip
import pe.bpm.features.training.presentation.learnerSummary
import pe.bpm.features.training.presentation.assignmentsForToday
import pe.bpm.features.training.presentation.criterionLabel
import pe.bpm.features.training.presentation.nextAssignment
import pe.bpm.features.training.presentation.readableDate
import pe.bpm.features.training.presentation.assignmentProgress
import pe.bpm.features.training.presentation.trainingFailureMessage
import pe.bpm.features.workspace.domain.Location

/**
 * Pantalla «Hoy» del personal (WORKER). Solo muestra datos reales: la asignación que toca continuar o empezar,
 * «Tareas de hoy» (hasta 4 planes de limpieza con su estado del servidor, de la primera sede asignada), los accesos
 * del turno si no hay sede o planes, las capacitaciones asignadas, la racha y el resumen de logros.
 *
 * Pide al [learner] las asignaciones y los logros al abrirse, y a [operations] los planes de hoy de la sede
 * (`view=today`). En compacta el orden es siguiente paso, tareas/turno, asignadas, racha, recompensas y reportar;
 * desde media la racha, los logros y «Reportar un problema» van en el panel lateral.
 */
@Composable
internal fun WorkerTodayContent(
    learner: TrainingLearnerViewModel,
    userId: String,
    organizationId: String,
    operations: OperationsViewModel,
    locations: List<Location>,
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
    // Una sola sede asignada es «la sede»; con varias se usa la primera y «Ver todas» lleva a Tareas.
    val site = locations.firstOrNull()
    LaunchedEffect(userId, organizationId, site?.id) {
        if (site != null) {
            operations.open(userId, organizationId, "WORKER", site.id)
            operations.refreshToday()
        }
    }
    val state by learner.state.collectAsStateWithLifecycle()
    val ops by operations.state.collectAsStateWithLifecycle()
    val next = nextAssignment(state.assignments)
    val achievements = state.achievements
    val todayPlans = if (site != null && ops.userId == userId && ops.siteId == site.id && ops.todayLoaded) homeTodayPlans(ops.todayPlans) else emptyList()
    // Sin panel de apoyo (compacta y media) la racha, el XP y las insignias van en una franja bajo «Tu siguiente paso».
    val singleColumn = supportPaneWidth(currentWindowClass) == null
    BpmSupportPaneLayout(
        modifier,
        support = {
            if (!singleColumn) {
                achievements?.streak?.let { BpmStreakBadge(it.currentDays, it.activeToday, longestDays = it.longestDays) }
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
            if (singleColumn && achievements != null) BpmSummaryStrip(learnerSummary(achievements), onClick = onAchievements)
            if (todayPlans.isNotEmpty() && site != null) BpmCard(Modifier.fillMaxWidth()) {
                val more = ops.todayPlans.size > todayPlans.size || locations.size > 1
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Tareas de hoy", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (more) BpmTextButton(onTasks) { Text("Ver todas") }
                }
                if (locations.size > 1) Text("Sede: ${site.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Filas planas separadas por un divisor: nada de tarjetas dentro de tarjetas (G5).
                Column(Modifier.fillMaxWidth()) {
                    todayPlans.forEachIndexed { index, plan ->
                        if (index > 0) BpmRowDivider()
                        TodayPlanRow(plan, onClick = onTasks, framed = false)
                    }
                }
            }
            // La que ya es «Tu siguiente paso» no se repite en la lista.
            val otherAssignments = assignmentsForToday(state.assignments).filter { it.assignmentId != next?.assignmentId }
            if (otherAssignments.isNotEmpty()) {
                BpmCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Asignadas a ti", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (state.assignments.size > 3) BpmTextButton(onLearn) { Text("Ver todas") }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        otherAssignments.forEachIndexed { index, assignment ->
                            if (index > 0) BpmRowDivider()
                            val (chip, tone) = assignmentChip(assignment)
                            BpmTaskRow(
                                assignment.course.title, BpmSymbol.Book,
                                detail = assignment.course.code,
                                tone = if (assignment.status == AssignmentProgress.COMPLETED) BpmTone.Success else BpmTone.Info,
                                trailing = { BpmStatusChip(chip, tone) },
                                onClick = { onOpenCourse(assignment.course.id) },
                                framed = false,
                            )
                        }
                    }
                }
            }
            // Una sola acción rápida: «Reportar un problema» (incidencias y correcciones abren la misma pantalla).
            // En compacta ocupa el ancho de la columna; desde la media va a la derecha, con ancho de contenido.
            BpmInlineActions(primary = {
                BpmSecondaryButton(onClick = onReport, modifier = Modifier.fillMaxWidth()) {
                    BpmIcon(BpmSymbol.Camera, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(BpmSpacing.sm))
                    Text("Reportar un problema")
                }
            })
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
        progress = assignmentProgress(assignment),
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
