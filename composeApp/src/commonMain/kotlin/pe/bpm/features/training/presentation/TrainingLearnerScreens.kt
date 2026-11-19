package pe.bpm.features.training.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*

internal fun assignmentStatusLabel(status: AssignmentProgress) = when (status) {
    AssignmentProgress.PENDING -> "Pendiente"
    AssignmentProgress.IN_PROGRESS -> "En curso"
    AssignmentProgress.COMPLETED -> "Completada"
    AssignmentProgress.OVERDUE -> "Vencida"
}

internal fun dateLabel(instant: String?): String = instant?.substringBefore('T').orEmpty()

@Composable
private fun FailureText(failure: TrainingFailure?) {
    failure?.let { Text(trainingFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

/**
 * Celebración breve de las insignias que otorgó un intento. Con «reducir movimiento» aparece sin
 * animación; el texto se anuncia a los lectores de pantalla en cualquier caso.
 */
@Composable
fun AchievementCelebration(achievements: List<Achievement>) {
    val reduce = rememberReduceMotion()
    val visible = remember { MutableTransitionState(reduce).apply { targetState = true } }
    AnimatedVisibility(visibleState = visible, enter = if (reduce) EnterTransition.None else fadeIn(BpmMotion.fadeIn()) + scaleIn(BpmMotion.spatial(), initialScale = 0.9f)) {
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(if (achievements.size == 1) "NUEVA INSIGNIA" else "NUEVAS INSIGNIAS")
                achievements.forEach {
                    Text("¡${it.name}!", style = MaterialTheme.typography.titleLarge)
                    if (it.description.isNotBlank()) Text(it.description)
                    if (it.points > 0) Text("+${it.points} XP", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ---- Mis capacitaciones ----------------------------------------------------------------------

/** Entrada principal de la Academia: asignaciones con estado y fecha límite, más accesos a logros y rutas. */
@Composable
fun MyAssignmentsPanel(
    viewModel: TrainingLearnerViewModel,
    onOpenCourse: (String) -> Unit,
    onAchievements: () -> Unit,
    onPaths: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Text("Mis capacitaciones", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.member) BpmSecondaryButton(onClick = onAchievements) { Text("Mis logros") }
            BpmSecondaryButton(onClick = onPaths) { Text("Rutas de aprendizaje") }
        }
        if (!state.member) return@Column
        if (state.assignmentsLoading && state.assignments.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.assignmentsFailure?.let {
            FailureText(it)
            BpmSecondaryButton(onClick = viewModel::refreshAssignments) { Text("Volver a intentar") }
        }
        if (state.assignmentsLoaded && state.assignments.isEmpty() && state.assignmentsFailure == null)
            EmptyState("Sin capacitaciones asignadas", "Cuando tu administrador te asigne un curso aparecerá aquí con su fecha límite.")
        BpmGridLayout(state.assignments) { item, cardModifier ->
            BpmCard(cardModifier, tint = if (item.status == AssignmentProgress.OVERDUE) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface) {
                Eyebrow(assignmentStatusLabel(item.status).uppercase())
                Text(item.course.title, style = MaterialTheme.typography.titleMedium)
                Text("${item.course.code} · ${item.course.estimatedDurationMinutes} min", style = MaterialTheme.typography.bodySmall)
                item.dueAt?.let { Text("Fecha límite: ${dateLabel(it)}", color = if (item.status == AssignmentProgress.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                item.completedAt?.let { Text("Completada el ${dateLabel(it)}") }
                item.bestScore?.let { Text("Mejor puntaje: $it") }
                BpmSecondaryButton(onClick = { onOpenCourse(item.course.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (item.status == AssignmentProgress.COMPLETED) "Repasar curso" else if (item.progressStatus == ProgressStatus.NOT_STARTED) "Empezar" else "Continuar")
                }
            }
        }
        if (state.assignmentsNext != null) BpmSecondaryButton(onClick = viewModel::moreAssignments, enabled = !state.assignmentsLoading) { Text("Ver más capacitaciones") }
    }
}

// ---- Mis logros ------------------------------------------------------------------------------

@Composable
fun AchievementsScreen(viewModel: TrainingLearnerViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().widthIn(max = 860.dp).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Text("Mis logros", style = MaterialTheme.typography.headlineMedium)
        if (state.achievementsLoading && state.achievements == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.achievementsFailure?.let {
            FailureText(it)
            BpmSecondaryButton(onClick = viewModel::loadAchievements) { Text("Volver a intentar") }
        }
        if (!state.member) EmptyState("Sin logros", "Los logros se registran para quienes pertenecen a la organización.")
        state.achievements?.let { mine ->
            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Eyebrow("PUNTOS DE EXPERIENCIA")
                Text("${mine.totals.experiencePoints} XP", style = MaterialTheme.typography.headlineMedium)
                Text("Cursos: ${mine.totals.courseExperiencePoints} · Lecciones: ${mine.totals.moduleExperiencePoints} · Insignias: ${mine.totals.achievementPoints}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Insignias obtenidas (${mine.earned.size})", style = MaterialTheme.typography.titleLarge)
            if (mine.earned.isEmpty()) Text("Todavía no obtienes insignias. Completa cursos y evaluaciones para ganarlas.")
            BpmGridLayout(mine.earned) { a, m -> AchievementCard(a, m) }
            if (mine.pending.isNotEmpty()) {
                Text("Por lograr", style = MaterialTheme.typography.titleLarge)
                BpmGridLayout(mine.pending) { a, m -> AchievementCard(a, m) }
            }
        }
    }
}

internal fun criterionLabel(a: Achievement) = when (a.criterion) {
    AchievementCriterion.COURSES_COMPLETED -> "Completa ${a.criterionThreshold} curso(s)"
    AchievementCriterion.PERFECT_SCORE -> "Logra ${a.criterionThreshold} evaluación(es) con puntaje perfecto"
    AchievementCriterion.STREAK_DAYS -> "Evalúa ${a.criterionThreshold} día(s) seguidos"
}

@Composable
private fun AchievementCard(a: Achievement, modifier: Modifier) {
    BpmCard(modifier, tint = if (a.awardedAt != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
        Eyebrow(if (a.awardedAt != null) "OBTENIDA" else "POR LOGRAR")
        Text(a.name, style = MaterialTheme.typography.titleMedium)
        if (a.description.isNotBlank()) Text(a.description)
        Text(criterionLabel(a) + " · ${a.points} XP", style = MaterialTheme.typography.bodySmall)
        a.awardedAt?.let { Text("Obtenida el ${dateLabel(it)}", style = MaterialTheme.typography.bodySmall) }
    }
}

// ---- Rutas de aprendizaje --------------------------------------------------------------------

internal fun pathStatusLabel(status: PathStatus) = when (status) {
    PathStatus.DRAFT -> "Borrador"
    PathStatus.PUBLISHED -> "Publicada"
    PathStatus.ARCHIVED -> "Archivada"
}

@Composable
fun LearningPathsScreen(viewModel: TrainingLearnerViewModel, onOpenCourse: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().widthIn(max = 860.dp).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        val detail = state.pathDetail
        if (state.pathDetailLoading || state.pathsLoading && state.paths.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (detail != null) { PathDetail(detail, onOpenCourse, viewModel::closePath); return@Column }
        Text("Rutas de aprendizaje", style = MaterialTheme.typography.headlineMedium)
        Text("Una ruta ordena varios cursos para que avances paso a paso.")
        FailureText(state.pathDetailFailure ?: state.pathsFailure)
        if (state.pathsFailure != null) BpmSecondaryButton(onClick = viewModel::loadPaths) { Text("Volver a intentar") }
        if (state.pathsLoaded && state.paths.isEmpty() && state.pathsFailure == null) EmptyState("Sin rutas", "Tu organización todavía no publicó rutas de aprendizaje.")
        BpmGridLayout(state.paths) { path, m ->
            BpmCard(m) {
                Text(path.title, style = MaterialTheme.typography.titleMedium)
                if (path.description.isNotBlank()) Text(path.description)
                path.progress?.let {
                    LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${it.completedCourses} de ${it.totalCourses} cursos · ${it.percent} %", style = MaterialTheme.typography.bodySmall)
                }
                BpmSecondaryButton(onClick = { viewModel.openPath(path.id) }, modifier = Modifier.fillMaxWidth()) { Text("Ver ruta") }
            }
        }
        if (state.pathsNext != null) BpmSecondaryButton(onClick = viewModel::morePaths, enabled = !state.pathsLoading) { Text("Ver más rutas") }
    }
}

@Composable
private fun ColumnScope.PathDetail(path: LearningPath, onOpenCourse: (String) -> Unit, onBack: () -> Unit) {
    Text(path.title, style = MaterialTheme.typography.headlineMedium)
    if (path.description.isNotBlank()) Text(path.description)
    path.progress?.let {
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
            Eyebrow("TU AVANCE")
            LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text("${it.completedCourses} de ${it.totalCourses} cursos completados (${it.percent} %)", style = BpmType.bodyStrong)
            if (it.nextCourseId == null && it.totalCourses > 0) Text("¡Terminaste esta ruta!")
        }
    }
    path.courses.forEach { course ->
        val next = path.progress?.nextCourseId?.equals(course.courseId, true) == true
        BpmCard(Modifier.fillMaxWidth(), tint = if (next) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
            Eyebrow("CURSO ${course.position}" + if (next) " · SIGUIENTE" else "")
            Text(course.title, style = MaterialTheme.typography.titleMedium)
            Text(course.progressStatus?.let(::progressLabel) ?: "Sin avance", style = MaterialTheme.typography.bodySmall)
            if (course.courseStatus == CourseStatus.PUBLISHED)
                BpmSecondaryButton(onClick = { onOpenCourse(course.courseId) }, modifier = Modifier.fillMaxWidth()) { Text(if (next) "Continuar" else "Abrir curso") }
            else Text("Este curso no está disponible por ahora.", style = MaterialTheme.typography.bodySmall)
        }
    }
    BpmTextButton(onClick = onBack) { Text("Volver a las rutas") }
}

// ---- Verificación pública de constancias -----------------------------------------------------

/** Pantalla pública (sin sesión): comprueba una constancia por su código. */
@Composable
fun CertificateVerificationScreen(viewModel: CertificateVerificationViewModel, onHome: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().widthIn(max = 640.dp).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Text("Verificar constancia", style = MaterialTheme.typography.headlineMedium)
        Text("Escribe el código de 24 caracteres que figura en la constancia de Academia BPM.")
        BpmTextField(state.input, viewModel::edit, "Código de verificación", enabled = !state.checking)
        RaisedButton("Verificar", viewModel::check, Modifier.widthIn(min = 160.dp), enabled = !state.checking && state.input.isNotBlank())
        if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (val outcome = state.outcome) {
            is VerificationOutcome.Found -> {
                val c = outcome.certificate
                val valid = c.status == CertificateStatus.VALID
                BpmCard(Modifier.fillMaxWidth(), tint = if (valid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow(if (valid) "CONSTANCIA VÁLIDA" else "CONSTANCIA REVOCADA")
                        Text(c.courseTitle, style = MaterialTheme.typography.titleLarge)
                        Text("Persona: ${c.holder}")
                        Text("Organización: ${c.organization}")
                        Text("Versión del curso: ${c.versionNumber}")
                        Text("Emitida el ${dateLabel(c.issuedAt)}")
                        if (!valid) Text("Esta constancia fue revocada y ya no tiene validez.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            VerificationOutcome.NotFound -> BpmCard(Modifier.fillMaxWidth()) {
                Text("No encontramos una constancia con ese código. Revisa que esté completo y vuelve a intentarlo.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            is VerificationOutcome.Failed -> FailureText(outcome.reason)
            null -> Unit
        }
        BpmTextButton(onClick = onHome) { Text("Ir a Academia BPM") }
    }
}
