package pe.bpm.features.training.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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

// ---- Aprender: hub del alumno ------------------------------------------------------------------

/**
 * Hub de Aprender: tarjeta «Continuar» (asignación en curso), «Asignadas a ti» con estado y vencimiento, acceso a
 * las rutas de aprendizaje y, en el panel de apoyo, el resumen de logros. Solo muestra datos reales: el avance
 * (anillo en «Continuar», barra en las tarjetas) aparece solo si el servidor informa el porcentaje; si no, un icono de reproducir. Conserva la carga,
 * el reintento, el estado vacío y «Ver más capacitaciones».
 */
@Composable
fun MyAssignmentsPanel(
    viewModel: TrainingLearnerViewModel,
    onOpenCourse: (String) -> Unit,
    onAchievements: () -> Unit,
    onPaths: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val achievements = state.achievements
    val singleColumn = supportPaneWidth(currentWindowClass) == null
    BpmSupportPaneLayout(
        support = {
            // Sin panel de apoyo (compacta y media) el resumen es una franja compacta; el detalle solo en el panel.
            if (state.member && !singleColumn) {
                if (achievements != null) BpmRewardSummary(
                    experiencePoints = achievements.totals.experiencePoints,
                    earnedBadges = achievements.earned.size,
                    totalBadges = achievements.achievements.size,
                    nextBadgeTitle = achievements.pending.firstOrNull()?.name,
                    nextBadgeHint = achievements.pending.firstOrNull()?.let { criterionLabel(it) },
                    onOpen = onAchievements,
                ) else BpmChunkyButton("Mis logros", onAchievements, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
        },
        main = {
            if (state.member) {
                if (state.assignmentsLoading && state.assignments.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.assignmentsFailure?.let {
                    FailureText(it)
                    BpmChunkyButton("Volver a intentar", viewModel::refreshAssignments, tone = BpmChunkyTone.White)
                }
                if (state.assignmentsLoaded && state.assignments.isEmpty() && state.assignmentsFailure == null)
                    EmptyState("Sin capacitaciones asignadas", "Cuando tu administrador te asigne un curso aparecerá aquí con su fecha límite.")
                continueAssignment(state.assignments)?.let { ContinueCard(it, onOpenCourse) }
                if (state.assignments.isNotEmpty()) AssignedCourses(state, onOpenCourse, viewModel::moreAssignments)
            }
            BpmTaskRow("Rutas de aprendizaje", BpmSymbol.Route, detail = "Sigue un camino de cursos paso a paso", onClick = onPaths)
            if (state.member && singleColumn) {
                if (achievements != null) BpmSummaryStrip(learnerSummary(achievements), onClick = onAchievements)
                else BpmChunkyButton("Mis logros", onAchievements, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
        },
    )
}

/** Tarjeta «Continuar»: la capacitación en curso con su vencimiento y un solo botón principal (auto-ancho desde la media). */
@Composable
private fun ContinueCard(assignment: MyAssignment, onOpenCourse: (String) -> Unit) {
    val (chip, tone) = assignmentChip(assignment)
    BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Featured) {
        Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            val progress = assignmentProgress(assignment)
            if (progress != null) {
                val percent = assignment.percent.coerceIn(0, 100)
                BpmProgressRing(progress, "$percent %", "Avance del curso: $percent %", size = 56.dp, strokeWidth = 6.dp)
            } else Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                BpmIcon(BpmSymbol.Play, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Eyebrow("CONTINUAR")
                Text(assignment.course.title, style = MaterialTheme.typography.titleLarge)
                Text("${assignment.course.code} · ${assignment.course.estimatedDurationMinutes} min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.padding(top = BpmSpacing.xs)) { BpmStatusChip(chip, tone) }
            }
        }
        BpmInlineActions { BpmChunkyButton("Continuar", { onOpenCourse(assignment.course.id) }, Modifier.fillMaxWidth()) }
    }
}

/**
 * «Asignadas a ti»: filas de curso (título, meta, avance y chip de vencimiento) en una sola columna en todo ancho.
 * No repite el curso que ya está en «Continuar».
 */
@Composable
private fun AssignedCourses(state: LearnerState, onOpenCourse: (String) -> Unit, onMore: () -> Unit) {
    val items = assignedWithoutContinue(state.assignments)
    if (items.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Asignadas a ti", style = MaterialTheme.typography.titleLarge)
            // Con más páginas por cargar la cuenta sería parcial: no se muestra.
            if (state.assignmentsNext == null) Text(if (items.size == 1) "1 curso" else "${items.size} cursos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BpmRowGrid(items, columns = 1) { item, modifier ->
            val (chip, tone) = assignmentChip(item)
            BpmCourseRow(item.course.title, "${item.course.code} · ${assignmentMeta(item)}", BpmSymbol.Book, { onOpenCourse(item.course.id) }, modifier,
                progress = assignmentProgress(item), status = { BpmStatusChip(chip, tone) })
        }
    }
    if (state.assignmentsNext != null) BpmChunkyButton("Ver más capacitaciones", onMore, tone = BpmChunkyTone.White, enabled = !state.assignmentsLoading)
}

// ---- Mis logros ------------------------------------------------------------------------------

@Composable
fun AchievementsScreen(viewModel: TrainingLearnerViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().widthIn(max = BpmPatternRules.readingWidth).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Text("Mis logros", style = MaterialTheme.typography.headlineMedium)
        if (state.achievementsLoading && state.achievements == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.achievementsFailure?.let {
            FailureText(it)
            BpmChunkyButton("Volver a intentar", viewModel::loadAchievements, tone = BpmChunkyTone.White)
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
    Column(modifier.fillMaxSize().widthIn(max = BpmPatternRules.readingWidth).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        val detail = state.pathDetail
        if (state.pathDetailLoading || state.pathsLoading && state.paths.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (detail != null) { PathDetail(detail, onOpenCourse, viewModel::closePath); return@Column }
        Text("Rutas de aprendizaje", style = MaterialTheme.typography.headlineMedium)
        Text("Una ruta ordena varios cursos para que avances paso a paso.")
        FailureText(state.pathDetailFailure ?: state.pathsFailure)
        if (state.pathsFailure != null) BpmChunkyButton("Volver a intentar", viewModel::loadPaths, tone = BpmChunkyTone.White)
        if (state.pathsLoaded && state.paths.isEmpty() && state.pathsFailure == null) EmptyState("Sin rutas", "Tu organización todavía no publicó rutas de aprendizaje.")
        BpmGridLayout(state.paths) { path, m ->
            BpmCard(m, onClick = { viewModel.openPath(path.id) }, onClickLabel = "Ver ruta") {
                Text(path.title, style = MaterialTheme.typography.titleMedium)
                if (path.description.isNotBlank()) Text(path.description)
                path.progress?.let {
                    LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${it.completedCourses} de ${it.totalCourses} cursos · ${it.percent} %", style = MaterialTheme.typography.bodySmall)
                }
                BpmChunkyButton("Ver ruta", { viewModel.openPath(path.id) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
        }
        if (state.pathsNext != null) BpmChunkyButton("Ver más rutas", viewModel::morePaths, tone = BpmChunkyTone.White, enabled = !state.pathsLoading)
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
                BpmChunkyButton(if (next) "Continuar" else "Abrir curso", { onOpenCourse(course.courseId) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
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
    Column(modifier.fillMaxSize().widthIn(max = BpmPatternRules.readingWidth).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Text("Verificar constancia", style = MaterialTheme.typography.headlineMedium)
        Text("Escribe el código de 24 caracteres que figura en la constancia de Academia BPM.")
        BpmTextField(state.input, viewModel::edit, "Código de verificación", enabled = !state.checking)
        BpmChunkyButton("Verificar", viewModel::check, Modifier.bpmPrimaryButtonWidth(), enabled = !state.checking && state.input.isNotBlank())
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
            VerificationOutcome.NotFound -> BpmInlineNotice(warning = true, message = "No encontramos una constancia con ese código. Revisa que esté completo y vuelve a intentarlo.")
            is VerificationOutcome.Failed -> FailureText(outcome.reason)
            null -> Unit
        }
        BpmTextButton(onClick = onHome) { Text("Ir a Academia BPM") }
    }
}
