package pe.bpm.features.training.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.training.domain.*

data class LearnerState(
    val userId: String? = null,
    val organizationId: String? = null,
    /** Falso para SUPER_ADMIN sin membresía: `me/assignments` y `me/achievements` responden 403. */
    val member: Boolean = true,
    val assignments: List<MyAssignment> = emptyList(),
    val assignmentsLoaded: Boolean = false,
    val assignmentsLoading: Boolean = false,
    val assignmentsFailure: TrainingFailure? = null,
    val assignmentsNext: String? = null,
    val achievements: MyAchievements? = null,
    val achievementsLoading: Boolean = false,
    val achievementsFailure: TrainingFailure? = null,
    val paths: List<LearningPath> = emptyList(),
    val pathsLoaded: Boolean = false,
    val pathsLoading: Boolean = false,
    val pathsFailure: TrainingFailure? = null,
    val pathsNext: String? = null,
    val pathDetail: LearningPath? = null,
    val pathDetailLoading: Boolean = false,
    val pathDetailFailure: TrainingFailure? = null,
    /** Cursos asignados con sus lecciones y avance (camino de Aprender del personal), por id de curso. */
    val pathCourses: Map<String, TrainingCourse> = emptyMap(),
    /** Cursos asignados cuyo detalle no se pudo cargar (el camino ofrece abrirlos de otra forma). */
    val pathCoursesFailed: Set<String> = emptySet(),
    val pathCoursesLoading: Boolean = false,
)

/** «Mis capacitaciones», «Mis logros» y rutas de aprendizaje para quien aprende. */
class TrainingLearnerViewModel(
    private val loadAssignments: LoadMyAssignments,
    private val loadAchievements: LoadMyAchievements,
    private val loadPaths: LoadLearningPaths,
    /** Descarga el catálogo offline (ETag/304) al abrir «Mis capacitaciones» para poder estudiar sin conexión. */
    private val syncCatalog: (suspend (String) -> Unit)? = null,
    private val localFirst: pe.bpm.core.offline.LocalFirstReader = pe.bpm.core.offline.LocalFirstReader.Direct,
    /** Carga un curso completo (lecciones y avance) para dibujar el camino de Aprender; sin él no hay camino. */
    private val loadCourse: LoadTrainingCourse? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(LearnerState())
    val state: StateFlow<LearnerState> = mutable.asStateFlow()
    private var generation = 0L
    private val jobs = mutableMapOf<String, Job>()

    private fun launchJob(key: String, block: suspend (Long) -> Unit) {
        jobs[key]?.cancel()
        val expected = generation
        jobs[key] = viewModelScope.launch { block(expected) }
    }

    fun reset() {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        mutable.value = LearnerState()
    }

    /** Un contexto nuevo (persona u organización) reinicia todo; el mismo contexto solo refresca las asignaciones. */
    fun open(userId: String, organizationId: String, member: Boolean) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.member == member) { refreshAssignments(); return }
        reset()
        mutable.value = LearnerState(userId, organizationId, member)
        refreshAssignments()
        syncCatalog?.let { sync ->
            viewModelScope.launch { try { sync(organizationId) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* la copia local sigue vigente */ } }
        }
    }

    // ---- Mis capacitaciones ------------------------------------------------------------------

    fun refreshAssignments() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (!s.member) return
        mutable.value = s.copy(assignmentsLoading = true, assignmentsFailure = null)
        launchJob("assignments") { expected ->
            // Lectura local primero: se muestra lo guardado de inmediato y luego se actualiza desde el servidor.
            val result = localFirst.read(
                isUsable = { it is TrainingResult.Success },
                onLocal = { local ->
                    if (expected == generation && !mutable.value.assignmentsLoaded) {
                        val page = (local as TrainingResult.Success).value
                        mutable.value = mutable.value.copy(assignments = page.items, assignmentsLoaded = true, assignmentsNext = page.nextToken)
                    }
                },
                load = { loadAssignments(org) },
            )
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(assignmentsLoading = false, assignmentsLoaded = true, assignments = result.value.items, assignmentsNext = result.value.nextToken)
                is TrainingResult.Failure -> mutable.value.copy(assignmentsLoading = false, assignmentsFailure = result.reason)
            }
        }
    }

    fun moreAssignments() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val token = s.assignmentsNext ?: return
        if (s.assignmentsLoading) return
        mutable.value = s.copy(assignmentsLoading = true, assignmentsFailure = null)
        launchJob("assignments") { expected ->
            val result = loadAssignments(org, token)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is TrainingResult.Success -> {
                    val current = mutable.value
                    val known = current.assignments.map { it.course.id.lowercase() }.toSet()
                    current.copy(assignmentsLoading = false, assignments = current.assignments + result.value.items.filter { it.course.id.lowercase() !in known }, assignmentsNext = result.value.nextToken)
                }
                is TrainingResult.Failure -> mutable.value.copy(assignmentsLoading = false, assignmentsFailure = result.reason)
            }
        }
    }

    /**
     * Carga, una tras otra, las lecciones y el avance de los cursos asignados para dibujar el camino. Cada curso que
     * llega se publica al momento; el que falla queda en [LearnerState.pathCoursesFailed]. No hace nada sin cargador,
     * sin membresía o sin asignaciones.
     */
    fun loadPathCourses() {
        val loader = loadCourse ?: return
        val s = mutable.value
        val org = s.organizationId ?: return
        if (!s.member) return
        val ids = s.assignments.map { it.course.id }.distinct()
        if (ids.isEmpty()) return
        mutable.value = s.copy(pathCoursesLoading = true)
        launchJob("pathCourses") { expected ->
            for (id in ids) {
                val result = loader(org, id)
                if (expected != generation) return@launchJob
                val current = mutable.value
                mutable.value = when (result) {
                    is TrainingResult.Success -> current.copy(pathCourses = current.pathCourses + (id to result.value), pathCoursesFailed = current.pathCoursesFailed - id)
                    is TrainingResult.Failure -> current.copy(pathCoursesFailed = current.pathCoursesFailed + id)
                }
            }
            mutable.value = mutable.value.copy(pathCoursesLoading = false)
        }
    }

    // ---- Mis logros --------------------------------------------------------------------------

    fun loadAchievements() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (!s.member) return
        mutable.value = s.copy(achievementsLoading = true, achievementsFailure = null)
        launchJob("achievements") { expected ->
            val result = loadAchievements(org)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(achievementsLoading = false, achievements = result.value)
                is TrainingResult.Failure -> mutable.value.copy(achievementsLoading = false, achievementsFailure = result.reason)
            }
        }
    }

    // ---- Rutas -------------------------------------------------------------------------------

    fun loadPaths() {
        val s = mutable.value
        val org = s.organizationId ?: return
        mutable.value = s.copy(pathsLoading = true, pathsFailure = null)
        launchJob("paths") { expected ->
            val result = loadPaths(org)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(pathsLoading = false, pathsLoaded = true, paths = result.value.items, pathsNext = result.value.nextToken)
                is TrainingResult.Failure -> mutable.value.copy(pathsLoading = false, pathsFailure = result.reason)
            }
        }
    }

    fun morePaths() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val token = s.pathsNext ?: return
        if (s.pathsLoading) return
        mutable.value = s.copy(pathsLoading = true, pathsFailure = null)
        launchJob("paths") { expected ->
            val result = loadPaths(org, token)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is TrainingResult.Success -> {
                    val current = mutable.value
                    val known = current.paths.map { it.id.lowercase() }.toSet()
                    current.copy(pathsLoading = false, paths = current.paths + result.value.items.filter { it.id.lowercase() !in known }, pathsNext = result.value.nextToken)
                }
                is TrainingResult.Failure -> mutable.value.copy(pathsLoading = false, pathsFailure = result.reason)
            }
        }
    }

    fun openPath(pathId: String) {
        val s = mutable.value
        val org = s.organizationId ?: return
        mutable.value = s.copy(pathDetail = s.paths.firstOrNull { it.id == pathId }, pathDetailLoading = true, pathDetailFailure = null)
        launchJob("pathDetail") { expected ->
            val result = loadPaths.detail(org, pathId)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(pathDetailLoading = false, pathDetail = result.value)
                is TrainingResult.Failure -> mutable.value.copy(pathDetailLoading = false, pathDetail = null, pathDetailFailure = result.reason)
            }
        }
    }

    fun closePath() {
        jobs["pathDetail"]?.cancel()
        mutable.value = mutable.value.copy(pathDetail = null, pathDetailLoading = false, pathDetailFailure = null)
    }

    /** Devuelve `true` si cerró el detalle de una ruta; `false` para salir de la pantalla. */
    fun back(): Boolean {
        val s = mutable.value
        if (s.pathDetail == null && !s.pathDetailLoading && s.pathDetailFailure == null) return false
        closePath()
        return true
    }

    override fun onCleared() { reset() }
}
