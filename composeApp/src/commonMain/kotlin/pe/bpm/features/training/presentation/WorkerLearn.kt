package pe.bpm.features.training.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.painterResource
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_class
import pe.bpm.resources.mascot_learning

/**
 * Aprender del personal (WORKER): el camino. Fila de contadores (racha, XP y meta de hoy) y, por cada curso asignado,
 * una cabecera de módulo ([BpmUnitBanner], el libro abre la guía del curso). Los cursos sin terminar muestran su camino
 * de lecciones ([LessonPath]): solo el curso activo ([activeCourseId]) lleva la burbuja «Empezar» y el nodo actual; el
 * resto de lecciones desbloqueadas son nodos normales. Los cursos completados no dibujan su camino: solo la cabecera,
 * el chip «Completado» y una fila compacta con la constancia que abre la guía. Al final, «Más cursos» con el [catalog]
 * de cursos no asignados. Desde la clase expandida los contadores, la meta y la próxima insignia pasan a un panel derecho.
 */
@Composable
internal fun WorkerLearnContent(
    learner: TrainingLearnerViewModel,
    userId: String,
    organizationId: String,
    member: Boolean,
    onOpenLesson: (courseId: String, lessonId: String) -> Unit,
    onOpenFinal: (courseId: String) -> Unit,
    onOpenCourse: (courseId: String) -> Unit,
    catalog: @Composable () -> Unit,
) {
    val state by learner.state.collectAsStateWithLifecycle()
    val ids = state.assignments.map { it.course.id }
    LaunchedEffect(userId, organizationId, member) {
        learner.open(userId, organizationId, member)
        if (member) learner.loadAchievements()
    }
    // Al volver de una lección el avance cambió: se recargan las lecciones de cada curso asignado.
    LaunchedEffect(ids, member) { learner.loadPathCourses() }
    // Los completados van al final (estable: cada grupo conserva el orden del camino).
    val ordered = remember(state.assignments, state.pathCourses) {
        pathOrder(state.assignments).sortedBy { isCompletedCourse(it, state.pathCourses[it.course.id]) }
    }
    val active = remember(ordered, state.pathCourses) { activeCourseId(ordered.map { state.pathCourses[it.course.id] }) }
    BpmPlayColumns(rail = { PathRail(state.achievements) }) { railVisible ->
        if (!railVisible) PathPills(state.achievements)
        state.assignmentsFailure?.takeIf { state.assignments.isEmpty() }?.let { failure ->
            Text(trainingFailureMessage(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmChunkyButton("Volver a intentar", learner::refreshAssignments, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        ordered.forEachIndexed { index, assignment ->
            val course = state.pathCourses[assignment.course.id]
            CoursePath(index, assignment, course, assignment.course.id in state.pathCoursesFailed, member, active == assignment.course.id,
                onOpenLesson, onOpenFinal, onOpenCourse)
        }
        if (state.assignmentsLoaded && ordered.isEmpty()) BpmPlayEmpty(Res.drawable.mascot_learning, "Sin cursos asignados",
            "Cuando tu administrador te asigne un curso, aparecerá aquí. Mientras tanto, mira los cursos de tu organización más abajo y empieza por el que quieras.")
        Text("Más cursos", style = BpmPlayType.h2, color = BpmPlayColors.ink)
        catalog()
    }
}

/** Un curso está completado si su asignación o su avance lo marcan así. */
private fun isCompletedCourse(assignment: MyAssignment, course: TrainingCourse?): Boolean =
    assignment.status == AssignmentProgress.COMPLETED || course?.progress?.status == ProgressStatus.COMPLETED

/**
 * Curso del nodo «Empezar»: el primero, en orden del camino, que no está completado y tiene una lección siguiente.
 * [courses] va en el orden del camino y cada curso aún no cargado es nulo; un nulo detiene la búsqueda, así la burbuja
 * nunca salta a un curso posterior mientras uno anterior carga. Nulo si no hay ninguno.
 */
internal fun activeCourseId(courses: List<TrainingCourse?>): String? {
    for (course in courses) {
        if (course == null) return null
        if (course.progress.status == ProgressStatus.COMPLETED) continue
        if (lessonStepStates(course).any { it == BpmStepState.Current }) return course.course.id
    }
    return null
}

/**
 * Un módulo del camino: su cabecera y, según su estado, el camino de lecciones ([active] decide la burbuja), la fila
 * compacta de un curso completado, o un aviso mientras carga o si falla.
 */
@Composable
private fun CoursePath(
    index: Int, assignment: MyAssignment, course: TrainingCourse?, failed: Boolean, member: Boolean, active: Boolean,
    onOpenLesson: (String, String) -> Unit, onOpenFinal: (String) -> Unit, onOpenCourse: (String) -> Unit,
) {
    val id = assignment.course.id
    val colors = BpmPlayColors.unitColors
    val completed = isCompletedCourse(assignment, course)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmUnitBanner("Módulo ${index + 1} · ${assignment.course.code}", assignment.course.title, colors[index % colors.size], onGuide = { onOpenCourse(id) },
            guideDescription = "Lo esencial de ${assignment.course.title}")
        when {
            completed -> CompletedCourse { onOpenCourse(id) }
            course != null -> Box(Modifier.fillMaxWidth()) {
                val nodes = remember(course, member) { pathNodes(course, member) }
                LessonPath(nodes, active) { node ->
                    if (node.lessonId != null) onOpenLesson(id, node.lessonId) else onOpenFinal(id)
                }
                if (index == 0) Image(painterResource(Res.drawable.mascot_class), contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).padding(top = 96.dp).width(96.dp))
            }
            failed -> BpmChunkyButton("Abrir el curso", { onOpenCourse(id) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            else -> Text("Cargando lecciones…", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        }
    }
}

/** Curso completado: chip «Completado» y, debajo, la constancia en una fila compacta que abre la guía del curso. */
@Composable
private fun CompletedCourse(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clip(CircleShape).background(BpmPlayColors.correct.soft).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BpmIcon(BpmSymbol.Check, tint = BpmPlayColors.correct.shadow, modifier = Modifier.size(18.dp))
            Text("Completado", style = BpmPlayType.label, color = BpmPlayColors.correct.shadow)
        }
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Color.White).border(BpmPlayBorder, BpmPlayColors.line, shape)
                .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(BpmPlayColors.gold.face), contentAlignment = Alignment.Center) {
                BpmIcon(BpmSymbol.Trophy, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Constancia del curso", style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                Text("Abre la guía del curso", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
            }
            BpmIcon(BpmSymbol.ChevronRight, tint = BpmPlayColors.muted)
        }
    }
}

/**
 * Camino de lecciones de un curso sin terminar: los [nodes] en columna con zigzag (0, −60, 0, +60 dp) y 16 dp entre
 * nodos, como [BpmLearningPath]. Solo con [active] el nodo actual lleva la burbuja «Empezar» y el aro; en otro curso
 * su siguiente lección es un nodo desbloqueado normal, sin burbuja ni aro.
 */
@Composable
private fun LessonPath(nodes: List<PathNodeModel>, active: Boolean, onClick: (PathNodeModel) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        nodes.forEachIndexed { index, node ->
            val dx = ZigzagOffsets[index % ZigzagOffsets.size]
            Column(Modifier.offset { IntOffset(dx.dp.roundToPx(), 0) }, horizontalAlignment = Alignment.CenterHorizontally) {
                val current = node.state == BpmPathNodeState.Current
                when {
                    current && active -> {
                        StartBubble("Empezar")
                        Box(Modifier.size(1.dp, 12.dp))
                        BpmPathNode(node.state, { onClick(node) }, contentDescription = node.label)
                    }
                    current -> OpenNode(node.label) { onClick(node) }
                    else -> BpmPathNode(node.state, { onClick(node) }, enabled = node.enabled, contentDescription = node.label)
                }
            }
        }
    }
}

/** Desplazamientos horizontales (dp) del zigzag, igual que en [BpmLearningPath]. */
private val ZigzagOffsets = intArrayOf(0, -60, 0, 60)

/**
 * Lección desbloqueada que no es la del nodo «Empezar»: óvalo claro de 72 × 66 dp con relieve y un triángulo de
 * reproducción en teal. Mismas medidas que [BpmPathNode], sin aro ni estrella. Se hunde al pulsar.
 */
@Composable
private fun OpenNode(label: String, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val relief = BpmPlayColors.navBorder
    val face = BpmPlayColors.action.soft
    Box(Modifier.size(72.dp, 73.dp).drawBehind {
        drawOval(relief, Offset(0f, 7.dp.toPx()), Size(72.dp.toPx(), 66.dp.toPx()))
    }) {
        Box(
            Modifier.offset { IntOffset(0, if (pressed) 4.dp.roundToPx() else 0) }.size(72.dp, 66.dp).clip(CircleShape).background(face)
                .semantics { contentDescription = label }
                .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { BpmIcon(BpmSymbol.Play, tint = BpmPlayColors.action.face, modifier = Modifier.size(32.dp)) }
    }
}

/** Burbuja «Empezar»: blanca, borde de 2 dp, radio 14, texto teal en mayúsculas y punta hacia abajo. */
@Composable
private fun StartBubble(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text.uppercase(), style = BpmPlayType.label.copy(fontSize = 14.sp, letterSpacing = 0.84.sp), color = BpmPlayColors.action.face, textAlign = TextAlign.Center,
        modifier = Modifier
            .drawWithContent {
                drawContent()
                val side = 12.dp.toPx(); val line = BpmPlayBorder.toPx()
                val topLeft = Offset(size.width / 2f - side / 2f, size.height - side / 2f)
                rotate(45f, pivot = Offset(topLeft.x + side / 2f, topLeft.y + side / 2f)) {
                    drawRect(Color.White, topLeft, Size(side, side))
                    drawRect(BpmPlayColors.line, Offset(topLeft.x + side - line, topLeft.y), Size(line, side))
                    drawRect(BpmPlayColors.line, Offset(topLeft.x, topLeft.y + side - line), Size(side, line))
                }
            }
            .background(Color.White, shape).border(BpmPlayBorder, BpmPlayColors.line, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Racha, XP y meta del día en una fila; no muestra nada hasta que llegan los logros. */
@Composable
private fun PathPills(achievements: MyAchievements?) {
    if (achievements == null) return
    val streak = achievements.streak
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        streak?.let { BpmStatPill(BpmSymbol.Flame, it.currentDays.toString(), BpmPlayTone.Streak, contentDescription = "Racha de ${it.currentDays} días") }
        BpmStatPill(BpmSymbol.Bolt, achievements.totals.experiencePoints.toString(), BpmPlayTone.Gold, contentDescription = "${achievements.totals.experiencePoints} puntos de experiencia")
        Spacer(Modifier.weight(1f))
        streak?.let { BpmStatPill(BpmSymbol.Target, lessonGoalLabel(it.activeToday), BpmPlayTone.Action, contentDescription = "Meta de hoy: " + if (it.activeToday) "cumplida" else "una lección") }
    }
}

/** Panel derecho del camino (clase expandida): contadores, meta de hoy y la próxima insignia por ganar. */
@Composable
private fun ColumnScope.PathRail(achievements: MyAchievements?) {
    PathPills(achievements)
    val activeToday = achievements?.streak?.activeToday == true
    BpmPlayCard {
        Text("Meta de hoy", style = BpmPlayType.h2, color = BpmPlayColors.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BpmChunkyProgress(if (activeToday) 1f else 0f, Modifier.weight(1f), BpmPlayTone.Gold, contentDescription = "Meta de hoy")
            Text(lessonGoalLabel(activeToday), style = BpmPlayType.pill, color = BpmPlayColors.gold.shadow)
        }
        Text(if (activeToday) "¡Meta cumplida! Hoy ya aprendiste." else "Completa 1 lección hoy para mantener tu racha.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    }
    achievements?.pending?.minByOrNull { it.criterionThreshold }?.let { next ->
        BpmPlayCard {
            Text("Próxima insignia", style = BpmPlayType.h2, color = BpmPlayColors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(BpmPlayColors.streak.face), contentAlignment = Alignment.Center) {
                    BpmIcon(BpmSymbol.Award, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(next.name, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                    if (next.description.isNotBlank()) Text(next.description, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                }
            }
        }
    }
}
