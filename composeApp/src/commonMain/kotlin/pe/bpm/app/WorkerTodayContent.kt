package pe.bpm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.operations.presentation.PlanMission
import pe.bpm.features.operations.presentation.ShiftProgress
import pe.bpm.features.operations.presentation.dailyGoal
import pe.bpm.features.operations.presentation.failureText
import pe.bpm.features.operations.presentation.goalPhrase
import pe.bpm.features.operations.presentation.missionOrder
import pe.bpm.features.operations.presentation.isLate
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.presentation.shiftProgress
import pe.bpm.features.operations.presentation.shiftTitle
import pe.bpm.features.operations.presentation.tasksText
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAchievements
import pe.bpm.features.training.domain.MyAssignment
import pe.bpm.features.training.presentation.TrainingLearnerViewModel
import pe.bpm.features.training.presentation.assignmentProgress
import pe.bpm.features.training.presentation.assignmentProgressNote
import pe.bpm.features.operations.presentation.DailyGoal
import pe.bpm.features.training.presentation.nextAssignment
import pe.bpm.features.training.presentation.trainingFailureMessage
import pe.bpm.features.workspace.domain.Location
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done
import pe.bpm.resources.mascot_ready

/** Cuántas misiones del turno muestra Hoy antes de remitir a «Tareas». */
private const val MISSION_LIMIT = 6

/**
 * Pantalla «Hoy» del personal (WORKER), estilo de juego: tres contadores (racha, XP, meta), el tomate con una frase que
 * dice lo que falta, la tarjeta «Meta de hoy» (barra dorada), «Tu turno» con las tareas como misiones (vencidas primero,
 * luego por hora, hechas al final en verde) y «Sigue aprendiendo» → camino. Solo datos reales: tareas `view=today` de
 * la sede, racha y XP de los logros y la asignación que toca. La meta ([dailyGoal]) suma 1 lección (actividad de hoy)
 * y las tareas del turno.
 *
 * El círculo de una misión (o tocar la fila) abre el registro rápido con ese plan ([OperationsViewModel.newCleaningFor]);
 * la raíz lo muestra en modo enfoque. Desde la clase expandida los contadores, la meta, el turno y la próxima insignia
 * pasan a un panel derecho de 330 dp.
 */
@Composable
internal fun WorkerTodayContent(
    learner: TrainingLearnerViewModel,
    userId: String,
    organizationId: String,
    operations: OperationsViewModel,
    locations: List<Location>,
    onOpenCourse: (String) -> Unit,
    /** «Continuar» de «Sigue aprendiendo»: abre la siguiente lección del curso, sin pasar por el camino. */
    onContinueCourse: (String) -> Unit,
    /** Abre la encuesta del estudio CAP; nulo si la cuenta no puede responderla. */
    onStudySurvey: (() -> Unit)?,
    onLearn: () -> Unit,
    onTasks: () -> Unit,
    onReport: () -> Unit,
    onAchievements: () -> Unit,
    modifier: Modifier = Modifier,
    displayName: String = "",
    /** Abre el buscador de normativa; su acceso vive en la columna principal, alineado con el resto del contenido. */
    onNormative: (() -> Unit)? = null,
) {
    LaunchedEffect(userId, organizationId) {
        learner.open(userId, organizationId, member = true)
        learner.loadAchievements()
    }
    // Una sola sede asignada es «la sede»; con varias se usa la primera y «Tareas» permite cambiarla.
    val site = locations.firstOrNull()
    LaunchedEffect(userId, organizationId, site?.id) {
        if (site != null) {
            operations.open(userId, organizationId, "WORKER", site.id)
            operations.refreshToday()
        }
    }
    val state by learner.state.collectAsStateWithLifecycle()
    val ops by operations.state.collectAsStateWithLifecycle()
    val achievements = state.achievements
    val ready = site != null && ops.userId == userId && ops.siteId == site.id && ops.todayLoaded
    val plans = if (ready) ops.todayPlans else emptyList()
    val progress = shiftProgress(plans)
    val goal = dailyGoal(achievements?.streak?.activeToday == true, progress.done, progress.total)
    val next = nextAssignment(state.assignments)
    BpmPlayColumns(modifier, rail = { TodayRail(achievements, goal, plans, progress, onAchievements) }) { railVisible ->
        if (!railVisible) TodayPills(achievements)
        // El tomate habla cuando ya hay con qué calcular la frase (logros y tareas cargados); sin tareas, lo hace el estado vacío.
        if (achievements != null && (ready || site == null) && !(ready && plans.isEmpty())) {
            BpmMascotRow(Res.drawable.mascot_ready, buildAnnotatedString {
                goalPhrase(goal, displayName.substringBefore(' ')).forEach { part ->
                    if (part.bold) withStyle(BpmPlayType.strong) { append(part.text) } else append(part.text)
                }
            })
        }
        if (!railVisible) GoalCard(goal)
        onStudySurvey?.let { open -> StudySurveyCard(open) }
        state.assignmentsFailure?.takeIf { state.assignments.isEmpty() }?.let { failure ->
            Text(trainingFailureMessage(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmChunkyButton("Volver a intentar", learner::refreshAssignments, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        // «Tu turno»: las tareas del día como misiones.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tu turno", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.weight(1f).semantics { heading() })
            if (plans.isNotEmpty()) {
                // El turno abierto («Turno tarde») es una ficha; el avance («1 de 3») solo aquí cuando no hay panel que lo lleve.
                shiftTitle(plans).takeIf { it != "Tu turno" }?.let { BpmPlayChip(it, BpmPlayTone.Action) }
                if (!railVisible) BpmPlayChip(progress.label, if (progress.complete) BpmPlayTone.Correct else BpmPlayTone.Gold)
            }
        }
        when {
            site == null -> PlayNote("Todavía no tienes una sede asignada. Pide a tu administrador que te agregue a una.")
            ops.todayFailure != null -> {
                Text(failureText(ops.todayFailure!!), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow)
                BpmChunkyButton("Reintentar", operations::refreshToday, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !ops.todayLoading)
            }
            !ready -> BpmSkeletonList(rows = 3, label = "Buscando tus tareas")
            plans.isEmpty() -> BpmPlayEmpty(Res.drawable.mascot_done, "¡Todo al día!", "Hoy no tienes tareas de limpieza en esta sede. Aprovecha para seguir aprendiendo.",
                action = { BpmChunkyButton("Ver el camino", onLearn, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White) })
            else -> {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    missionOrder(plans).take(MISSION_LIMIT).forEach { plan -> PlanMission(plan, operations) }
                }
                if (plans.size > MISSION_LIMIT) BpmChunkyButton("Ver todas las tareas", onTasks, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
        }
        LearnCard(next, state.assignmentsLoaded, onLearn, onContinueCourse)
        BpmChunkyButton("Reportar un problema", onReport, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, icon = BpmSymbol.Alert)
        onNormative?.let { open -> NormativeTile(open) }
    }
}

/** Invitación a la encuesta del estudio CAP (voluntaria y anónima). */
@Composable
internal fun StudySurveyCard(onOpen: () -> Unit) {
    BpmPlayCard {
        Text("Encuesta del estudio", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        Text("Participa de forma voluntaria y anónima en el estudio sobre buenas prácticas de manipulación.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        BpmChunkyButton("Abrir encuesta", onOpen, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}

/** Racha y XP en una fila; no muestra nada hasta que llegan los logros. La meta del día va en su propia ficha ([GoalCard]). */
@Composable
private fun TodayPills(achievements: MyAchievements?) {
    if (achievements == null) return
    val streak = achievements.streak
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        streak?.let { BpmStatPill(BpmSymbol.Flame, it.currentDays.toString(), BpmPlayTone.Streak, contentDescription = "Racha de ${it.currentDays} días") }
        BpmStatPill(BpmSymbol.Bolt, achievements.totals.experiencePoints.toString(), BpmPlayTone.Gold, contentDescription = "${achievements.totals.experiencePoints} puntos de experiencia")
    }
}

/**
 * «Meta de hoy»: la única ficha con la cuenta del día. La meta es **1 lección + las tareas del turno** («0 de 2»), y debajo
 * se desglosa en esas dos partes para que se entienda de dónde sale el número. (En Aprender solo se cuenta la lección y
 * así se llama: «Lección de hoy».)
 */
@Composable
private fun GoalCard(goal: DailyGoal) {
    BpmPlayCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Meta de hoy", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            BpmPlayChip(goal.chip, if (goal.complete) BpmPlayTone.Correct else BpmPlayTone.Gold)
        }
        BpmChunkyProgress(goal.progress, Modifier.fillMaxWidth(), BpmPlayTone.Gold, contentDescription = "Meta de hoy: ${goal.chip}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BpmPlayChip(if (goal.lessonDone) "Lección hecha" else "1 lección", if (goal.lessonDone) BpmPlayTone.Correct else BpmPlayTone.Gold)
            if (goal.tasksTotal > 0) BpmPlayChip("Tareas ${goal.tasksDone}/${goal.tasksTotal}", if (goal.missingTasks == 0) BpmPlayTone.Correct else BpmPlayTone.Gold)
        }
    }
}

/** Acceso al buscador de normativa: dentro de la columna principal, con el mismo ancho que las demás tarjetas. */
@Composable
internal fun NormativeTile(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    BpmActionTile(BpmSymbol.Learn, "Buscar en la normativa", "Encuentra lo que dice la norma sanitaria sobre temperaturas, higiene y más.", onOpen, modifier.fillMaxWidth())
}

/** Texto de apoyo en una línea (cargando, vacío, sin sede). */
@Composable
private fun PlayNote(text: String) {
    Text(text, style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
}

/** «Sigue aprendiendo» ([title]): la capacitación que toca (con su avance) y un botón al camino; sin pendientes, un refuerzo. */
@Composable
internal fun LearnCard(next: MyAssignment?, loaded: Boolean, onLearn: () -> Unit, onContinue: (String) -> Unit, title: String = "Sigue aprendiendo") {
    if (next == null && !loaded) return
    BpmPlayCard {
        Text(title, style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        if (next != null) {
            val started = next.status == AssignmentProgress.IN_PROGRESS
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(BpmPlayColors.action.soft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    BpmIcon(BpmSymbol.Book, tint = BpmPlayColors.action.face, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(next.course.title, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, maxLines = 2)
                    Text(next.course.code, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                }
            }
            assignmentProgress(next)?.let { BpmChunkyProgress(it, Modifier.fillMaxWidth(), contentDescription = "Avance del curso") }
            assignmentProgressNote(next)?.let { Text(it, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted) }
            BpmChunkyButton(if (started) "Continuar" else "Empezar", { onContinue(next.course.id) }, Modifier.fillMaxWidth())
        } else {
            Text("No tienes capacitaciones pendientes. Puedes seguir aprendiendo cuando quieras.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            BpmChunkyButton("Ver el camino", onLearn, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
    }
}

/** Panel derecho (clase expandida): contadores, meta de hoy, el avance del turno y la próxima insignia por ganar. */
@Composable
private fun ColumnScope.TodayRail(
    achievements: MyAchievements?,
    goal: DailyGoal,
    plans: List<CleaningPlan>,
    shift: ShiftProgress,
    onAchievements: () -> Unit,
) {
    TodayPills(achievements)
    GoalCard(goal)
    // «Avance del turno»: cuenta y vencidas; la lista de tareas ya está a la izquierda, no se repite aquí.
    if (plans.isNotEmpty()) BpmPlayCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Avance del turno", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.weight(1f))
            BpmPlayChip(shift.label, if (shift.complete) BpmPlayTone.Correct else BpmPlayTone.Gold)
        }
        BpmChunkyProgress(shift.progress, Modifier.fillMaxWidth(), contentDescription = "Avance del turno: ${shift.label}")
        val late = plans.count { isLate(it) }
        if (shift.complete) Text("¡Terminaste las tareas del turno!", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tasksText(shift.total - shift.done) + " por hacer", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            if (late > 0) BpmPlayChip(if (late == 1) "1 vencida" else "$late vencidas", BpmPlayTone.Error)
        }
    }
    achievements?.pending?.minByOrNull { it.criterionThreshold }?.let { badge ->
        BpmPlayCard(Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onAchievements)) {
            Text("Próxima insignia", style = BpmPlayType.h2, color = BpmPlayColors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(BpmPlayColors.streak.face), contentAlignment = Alignment.Center) {
                    BpmIcon(BpmSymbol.Award, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(badge.name, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                    if (badge.description.isNotBlank()) Text(badge.description, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                }
            }
        }
    }
}
