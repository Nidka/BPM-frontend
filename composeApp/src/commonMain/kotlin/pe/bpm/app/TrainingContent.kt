package pe.bpm.app

import pe.bpm.core.designsystem.BpmFilterChip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import pe.bpm.core.designsystem.BpmChoiceOption
import pe.bpm.core.designsystem.BpmPagedChoiceSelector
import pe.bpm.features.workspace.domain.WorkspaceResource
import pe.bpm.features.workspace.presentation.WorkspaceState
import pe.bpm.features.workspace.presentation.WorkspaceViewModel
import androidx.compose.ui.unit.dp
import pe.bpm.features.training.presentation.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import pe.bpm.features.training.presentation.TrainingAdminScreen
import pe.bpm.features.training.presentation.TrainingAuthoringViewModel
import pe.bpm.features.training.presentation.TrainingScreen
import pe.bpm.features.training.presentation.TrainingViewModel

/**
 * La raíz entrega el contexto autorizado (persona, organización y curso) y conserva el estado
 * solo mientras la pantalla está abierta: al salir se descartan lecciones, URL de video y respuestas.
 */
@Composable
internal fun TrainingCourseContent(userId: String, organizationId: String, courseId: String, canPersist: Boolean, training: TrainingViewModel, modifier: Modifier = Modifier, onOpenCourse: ((String) -> Unit)? = null, worker: WorkerCourseContext? = null) {
    LaunchedEffect(userId, organizationId, courseId, canPersist) { training.open(userId, organizationId, courseId, canPersist) }
    DisposableEffect(training, userId, organizationId, courseId) { onDispose { training.reset() } }
    // Desde el camino de Aprender (WORKER) se llega con una intención: al cargar el curso abre la lección o la evaluación final.
    val loaded = training.state.collectAsStateWithLifecycle().value.course?.course?.id
    LaunchedEffect(loaded, worker?.intent) {
        val intent = worker?.intent ?: return@LaunchedEffect
        if (loaded == null || !loaded.equals(courseId, ignoreCase = true)) return@LaunchedEffect
        when (intent) {
            is CourseIntent.Lesson -> training.selectLesson(intent.lessonId)
            CourseIntent.FinalEvaluation -> training.startEvaluation(pe.bpm.features.training.domain.AttemptType.FINAL)
            CourseIntent.Review -> training.startReview()
            CourseIntent.Resume -> training.resume()
        }
        worker.onIntentHandled()
    }
    TrainingScreen(training, modifier, onOpenCourse, worker)
}

/** Autoría de Academia; el backend vuelve a validar el rol de cada operación. */
@Composable
internal fun TrainingAdminContent(userId: String, organizationId: String, canManage: Boolean, authoring: TrainingAuthoringViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(userId, organizationId, canManage) { authoring.open(userId, organizationId, canManage) }
    DisposableEffect(authoring, userId, organizationId) { onDispose { authoring.reset() } }
    pe.bpm.core.offline.ServerOnly { TrainingAdminScreen(authoring, modifier) }
}

/** Selector paginado de personas: adapta la página de miembros del workspace sin acoplar funcionalidades. */
@Composable
internal fun TrainingMemberSelector(workspace: WorkspaceState, actions: WorkspaceViewModel, selectedId: String?, selectedName: String, enabled: Boolean, onSelect: (String, String) -> Unit) {
    val members = workspace.workspace?.members.orEmpty()
    val page = workspace.pages[WorkspaceResource.Members]
    LaunchedEffect(workspace.workspace?.organization?.id) { if (page == null) actions.pageSearch(WorkspaceResource.Members, "") }
    BpmPagedChoiceSelector(
        members.map { BpmChoiceOption(it.id, "${it.name} · ${it.email}") }, selectedId, selectedName.ifBlank { if (selectedId != null) "Persona seleccionada" else "" },
        page?.request?.query.orEmpty(), (page?.previous?.size ?: 0) + 1, page?.loading == true, page?.errors?.isNotEmpty() == true,
        page?.previous?.isNotEmpty() == true, page?.nextToken?.isNotEmpty() == true, enabled, "Buscar persona", "No hay personas para esta búsqueda.",
        onSearch = { actions.pageSearch(WorkspaceResource.Members, it) }, onPrevious = { actions.pagePrevious(WorkspaceResource.Members) },
        onNext = { actions.pageNext(WorkspaceResource.Members) }, onRetry = { actions.pageRetry(WorkspaceResource.Members) },
        onSelect = { option -> members.firstOrNull { it.id == option.id }?.let { onSelect(it.id, it.name) } })
}

/** Panel de Academia para administración: cursos, asignaciones, rutas y logros en pestañas. */
@Composable
internal fun TrainingPlusAdminContent(
    organizationId: String,
    canManage: Boolean,
    workspace: WorkspaceState,
    workspaceActions: WorkspaceViewModel,
    assignments: TrainingAssignmentAdminViewModel,
    paths: LearningPathAdminViewModel,
    achievements: TrainingAchievementAdminViewModel,
    tabsLocked: Boolean,
    modifier: Modifier = Modifier,
    /** «Asignar curso» abre directamente la pestaña de asignaciones (un toque menos). */
    startOnAssignments: Boolean = false,
    courses: @Composable () -> Unit,
) {
    LaunchedEffect(organizationId, canManage) {
        assignments.open(organizationId, canManage); paths.open(organizationId, canManage); achievements.open(organizationId, canManage)
    }
    var tab by rememberSaveable(startOnAssignments) { mutableIntStateOf(if (startOnAssignments) 1 else 0) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canManage && !tabsLocked) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Cursos", "Asignaciones", "Rutas", "Logros").forEachIndexed { index, label ->
                BpmFilterChip(tab == index, { tab = index }, { Text(label) }, reserveCheck = true)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (if (canManage && !tabsLocked) tab else 0) {
                1 -> AssignmentAdminScreen(assignments,
                    memberSelector = { id, name, enabled, onSelect -> TrainingMemberSelector(workspace, workspaceActions, id, name, enabled, onSelect) },
                    locationSelector = { id, name, enabled, onSelect -> AlertScopeSelector(workspace, workspaceActions, id, name, "Sin sede", enabled) { it?.let { l -> onSelect(l.id, l.name) } } })
                2 -> LearningPathAdminScreen(paths)
                3 -> AchievementAdminScreen(achievements)
                else -> courses()
            }
        }
    }
}

/** «Mis capacitaciones»: entrada principal de la Academia para quienes aprenden. */
@Composable
internal fun TrainingLearnerHeader(userId: String, organizationId: String, member: Boolean, learner: TrainingLearnerViewModel, onOpenCourse: (String) -> Unit, onAchievements: () -> Unit, onPaths: () -> Unit) {
    LaunchedEffect(userId, organizationId, member) {
        learner.open(userId, organizationId, member)
        // El resumen de logros del panel de apoyo necesita los totales; sin membresía no hay logros que pedir.
        if (member) learner.loadAchievements()
    }
    MyAssignmentsPanel(learner, onOpenCourse, onAchievements, onPaths)
}

/** «Mis logros» y rutas: pantallas secundarias; cada una pide sus datos al abrirse. */
@Composable
internal fun TrainingLearnerContent(userId: String, organizationId: String, member: Boolean, paths: Boolean, learner: TrainingLearnerViewModel, onOpenCourse: (String) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(userId, organizationId, member, paths) {
        learner.open(userId, organizationId, member)
        if (paths) learner.loadPaths() else learner.loadAchievements()
    }
    if (paths) LearningPathsScreen(learner, onOpenCourse, modifier) else AchievementsScreen(learner, modifier)
}

/** Verificación pública: no depende de sesión, organización ni permisos. */
@Composable
internal fun CertificateVerificationContent(code: String, verification: CertificateVerificationViewModel, onHome: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(code) { verification.open(code) }
    DisposableEffect(verification) { onDispose { verification.reset() } }
    Box(modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) { CertificateVerificationScreen(verification, onHome) }
}
