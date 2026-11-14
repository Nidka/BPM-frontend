package pe.bpm.features.training.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.training.domain.*

// ======================================================================================
// Asignaciones
// ======================================================================================

enum class TargetKind { USER, ROLE, LOCATION }

/**
 * Formulario de alta. [submissionId] se fija en el primer envío y se conserva en los reintentos
 * idénticos; cualquier edición lo descarta para no reutilizar un `id` con otro contenido.
 */
data class AssignmentForm(
    val courseId: String? = null,
    val courseTitle: String = "",
    val kind: TargetKind = TargetKind.USER,
    val userId: String? = null,
    val userName: String = "",
    val role: String = "WORKER",
    val locationId: String? = null,
    val locationName: String = "",
    /** Fecha límite opcional `AAAA-MM-DD` (fin del día, hora universal). */
    val dueDate: String = "",
    val submissionId: String? = null,
    val showIssues: Boolean = false,
)

data class AssignmentAdminState(
    val organizationId: String? = null,
    val allowed: Boolean = false,
    val items: List<Assignment> = emptyList(),
    val next: String? = null,
    val loading: Boolean = false,
    val failure: TrainingFailure? = null,
    val status: AssignmentRecordStatus? = AssignmentRecordStatus.ACTIVE,
    val filterCourseId: String? = null,
    val publishedCourses: List<AdminCourse> = emptyList(),
    val form: AssignmentForm? = null,
    val saving: Boolean = false,
    val formFailure: TrainingFailure? = null,
    val cancellingId: String? = null,
    val notice: String? = null,
)

object AssignmentFormRules {
    /** Lima no usa horario de verano: su desfase con UTC es siempre -5 h. */
    private const val LIMA_UTC_OFFSET_HOURS = 5
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")

    /**
     * Convierte la fecha al instante ISO del contrato o `null` si está vacía; falla si es inválida o pasada.
     * El día elegido termina a las 23:59 de America/Lima (UTC-5 fijo, sin horario de verano), es decir,
     * a las 04:59 UTC del día siguiente; la aritmética sobre el instante resuelve fin de mes y de año.
     */
    fun dueAt(date: String, now: Instant): Result<String?> {
        val text = date.trim()
        if (text.isEmpty()) return Result.success(null)
        if (!datePattern.matches(text)) return Result.failure(IllegalArgumentException("Usa el formato AAAA-MM-DD."))
        val startOfDayUtc = runCatching { Instant.parse(text + "T00:00:00Z") }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("La fecha no existe."))
        val parsed = startOfDayUtc + (24 + LIMA_UTC_OFFSET_HOURS).hours - 1.minutes
        val instant = parsed.toString()
        if (parsed <= now) return Result.failure(IllegalArgumentException("La fecha límite debe ser futura."))
        // El contrato limita la fecha a 5 años.
        if (parsed > now + (5 * 365 * 24).hours) return Result.failure(IllegalArgumentException("La fecha límite admite hasta 5 años."))
        return Result.success(instant)
    }

    fun issues(form: AssignmentForm, now: Instant): Map<String, String> = buildMap {
        if (form.courseId == null) put("course", "Elige un curso publicado.")
        when (form.kind) {
            TargetKind.USER -> if (form.userId == null) put("target", "Elige a la persona.")
            TargetKind.LOCATION -> if (form.locationId == null) put("target", "Elige la sede.")
            TargetKind.ROLE -> Unit
        }
        dueAt(form.dueDate, now).exceptionOrNull()?.let { put("dueDate", it.message.orEmpty()) }
    }

    fun target(form: AssignmentForm): AssignmentTarget? = when (form.kind) {
        TargetKind.USER -> form.userId?.let { AssignmentTarget.User(it, form.userName) }
        TargetKind.ROLE -> AssignmentTarget.Role(form.role, form.locationId, form.locationName)
        TargetKind.LOCATION -> form.locationId?.let { AssignmentTarget.Location(it, form.locationName) }
    }
}

/** Administración de asignaciones (ADMIN y SUPER_ADMIN): alta, listado con filtros y cancelación. */
class TrainingAssignmentAdminViewModel(
    private val listAssignments: ListTrainingAssignments,
    private val createAssignment: CreateTrainingAssignment,
    private val cancelAssignment: CancelTrainingAssignment,
    private val listCourses: ListAdminCourses,
    private val newId: () -> String,
    private val clock: () -> Instant = { Clock.System.now() },
) : ViewModel() {
    private val mutable = MutableStateFlow(AssignmentAdminState())
    val state: StateFlow<AssignmentAdminState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun reset() { generation++; job?.cancel(); mutable.value = AssignmentAdminState() }

    fun open(organizationId: String, allowed: Boolean) {
        val s = mutable.value
        if (s.organizationId == organizationId && s.allowed == allowed) return
        reset()
        mutable.value = AssignmentAdminState(organizationId, allowed)
        if (allowed) refresh()
    }

    fun refresh() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (!s.allowed) return
        val expected = ++generation
        job?.cancel()
        mutable.value = s.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            val result = listAssignments(org, s.status, s.filterCourseId)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(loading = false, items = result.value.items, next = result.value.nextToken)
                is TrainingResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun more() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val token = s.next ?: return
        if (s.loading) return
        val expected = generation
        mutable.value = s.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            val result = listAssignments(org, s.status, s.filterCourseId, token)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> {
                    val current = mutable.value
                    val known = current.items.map { it.id }.toSet()
                    current.copy(loading = false, items = current.items + result.value.items.filter { it.id !in known }, next = result.value.nextToken)
                }
                is TrainingResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun filterStatus(status: AssignmentRecordStatus?) { mutable.value = mutable.value.copy(status = status); refresh() }
    fun filterCourse(courseId: String?) { mutable.value = mutable.value.copy(filterCourseId = courseId); refresh() }

    /** Carga los cursos publicados para el selector (también sirve de filtro por curso). */
    fun loadCourses() {
        val org = mutable.value.organizationId ?: return
        if (mutable.value.publishedCourses.isNotEmpty()) return
        val expected = generation
        viewModelScope.launch {
            val result = listCourses(org, CourseStatus.PUBLISHED)
            if (expected != generation) return@launch
            if (result is TrainingResult.Success) mutable.value = mutable.value.copy(publishedCourses = result.value)
        }
    }

    // ---- Alta --------------------------------------------------------------------------------

    fun startCreate() { loadCourses(); mutable.value = mutable.value.copy(form = AssignmentForm(), formFailure = null, notice = null) }
    fun cancelCreate() { if (!mutable.value.saving) mutable.value = mutable.value.copy(form = null, formFailure = null) }

    fun edit(change: (AssignmentForm) -> AssignmentForm) {
        val s = mutable.value
        val form = s.form ?: return
        if (s.saving) return
        mutable.value = s.copy(form = change(form).copy(submissionId = null), formFailure = null)
    }

    fun create() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val form = s.form ?: return
        if (s.saving) return
        val now = clock()
        if (AssignmentFormRules.issues(form, now).isNotEmpty()) { mutable.value = s.copy(form = form.copy(showIssues = true)); return }
        val target = AssignmentFormRules.target(form) ?: return
        val dueAt = AssignmentFormRules.dueAt(form.dueDate, now).getOrNull()
        val id = form.submissionId ?: newId()
        mutable.value = s.copy(form = form.copy(submissionId = id), saving = true, formFailure = null)
        val expected = generation
        viewModelScope.launch {
            val result = createAssignment(org, NewAssignment(id, requireNotNull(form.courseId), target, dueAt))
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(form = null, saving = false, notice = "Asignación creada. Quienes la reciban la verán en «Mis capacitaciones».")
                is TrainingResult.Failure -> mutable.value.copy(saving = false, formFailure = result.reason)
            }
            if (result is TrainingResult.Success) refresh()
        }
    }

    // ---- Cancelación -------------------------------------------------------------------------

    fun cancel(assignmentId: String) {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (s.cancellingId != null) return
        mutable.value = s.copy(cancellingId = assignmentId, failure = null, notice = null)
        val expected = generation
        viewModelScope.launch {
            val result = cancelAssignment(org, assignmentId)
            if (expected != generation) return@launch
            val current = mutable.value
            mutable.value = when (result) {
                is TrainingResult.Success -> current.copy(cancellingId = null, notice = "Asignación cancelada.",
                    items = if (current.status == AssignmentRecordStatus.ACTIVE) current.items.filterNot { it.id == assignmentId } else current.items.map { if (it.id == assignmentId) result.value else it })
                is TrainingResult.Failure -> current.copy(cancellingId = null, failure = result.reason)
            }
        }
    }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    /** Devuelve `true` si cerró el formulario abierto. */
    fun back(): Boolean { if (mutable.value.form == null) return false; cancelCreate(); return true }

    override fun onCleared() { reset() }
}

// ======================================================================================
// Insignias
// ======================================================================================

data class AchievementEditor(val achievement: Achievement, val name: String, val description: String, val points: String, val threshold: String, val active: Boolean, val showIssues: Boolean = false) {
    fun toEdit() = AchievementEdit(name, description, points.toIntOrNull() ?: -1, threshold.toIntOrNull() ?: -1, active)
    val issues: List<String> get() = AchievementRules.validate(toEdit())
}

data class AchievementAdminState(
    val organizationId: String? = null,
    val allowed: Boolean = false,
    val items: List<Achievement> = emptyList(),
    val loading: Boolean = false,
    val failure: TrainingFailure? = null,
    val editor: AchievementEditor? = null,
    val saving: Boolean = false,
    val saveFailure: TrainingFailure? = null,
    val notice: String? = null,
)

/** Catálogo de insignias: se pueden editar nombre, descripción, puntos, umbral y vigencia. */
class TrainingAchievementAdminViewModel(
    private val listAchievements: ListTrainingAchievements,
    private val updateAchievement: UpdateTrainingAchievement,
) : ViewModel() {
    private val mutable = MutableStateFlow(AchievementAdminState())
    val state: StateFlow<AchievementAdminState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun reset() { generation++; job?.cancel(); mutable.value = AchievementAdminState() }

    fun open(organizationId: String, allowed: Boolean) {
        val s = mutable.value
        if (s.organizationId == organizationId && s.allowed == allowed) return
        reset()
        mutable.value = AchievementAdminState(organizationId, allowed)
        if (allowed) refresh()
    }

    fun refresh() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (!s.allowed) return
        val expected = ++generation
        job?.cancel()
        mutable.value = s.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            val result = listAchievements(org)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(loading = false, items = result.value)
                is TrainingResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun startEdit(achievementId: String) {
        val item = mutable.value.items.firstOrNull { it.id == achievementId } ?: return
        mutable.value = mutable.value.copy(editor = AchievementEditor(item, item.name, item.description, item.points.toString(), item.criterionThreshold.toString(), item.active), saveFailure = null, notice = null)
    }

    fun edit(change: (AchievementEditor) -> AchievementEditor) {
        val s = mutable.value
        val editor = s.editor ?: return
        if (s.saving) return
        mutable.value = s.copy(editor = change(editor), saveFailure = null)
    }

    fun cancelEdit() { if (!mutable.value.saving) mutable.value = mutable.value.copy(editor = null, saveFailure = null) }

    fun save() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val editor = s.editor ?: return
        if (s.saving) return
        if (editor.issues.isNotEmpty()) { mutable.value = s.copy(editor = editor.copy(showIssues = true)); return }
        mutable.value = s.copy(saving = true, saveFailure = null)
        val expected = generation
        viewModelScope.launch {
            val result = updateAchievement(org, editor.achievement.id, editor.toEdit())
            if (expected != generation) return@launch
            val current = mutable.value
            mutable.value = when (result) {
                is TrainingResult.Success -> current.copy(saving = false, editor = null, notice = "Insignia actualizada.",
                    items = current.items.map { if (it.id == result.value.id) result.value else it })
                is TrainingResult.Failure -> current.copy(saving = false, saveFailure = result.reason)
            }
        }
    }

    fun back(): Boolean { if (mutable.value.editor == null) return false; cancelEdit(); return true }

    override fun onCleared() { reset() }
}

// ======================================================================================
// Rutas de aprendizaje
// ======================================================================================

/** Ruta en edición; [rowVersion] nulo significa que todavía no existe en el servidor. */
data class PathEditor(
    val id: String,
    val status: PathStatus,
    val rowVersion: String?,
    val title: String,
    val description: String,
    val courses: List<PathEditorCourse>,
    val dirty: Boolean = false,
    val showIssues: Boolean = false,
) {
    val isNew: Boolean get() = rowVersion == null
    fun toInput() = PathInput(id, title, description, courses.map { it.courseId }, rowVersion)
    val issues: List<String> get() = PathRules.validate(toInput())
}

data class PathEditorCourse(val courseId: String, val code: String, val title: String)

data class PathAdminState(
    val organizationId: String? = null,
    val allowed: Boolean = false,
    val items: List<LearningPath> = emptyList(),
    val next: String? = null,
    val loading: Boolean = false,
    val failure: TrainingFailure? = null,
    val status: PathStatus? = null,
    val allCourses: List<AdminCourse> = emptyList(),
    val editor: PathEditor? = null,
    val busy: Boolean = false,
    val editorFailure: TrainingFailure? = null,
    val notice: String? = null,
)

/** Gestión de rutas con control de concurrencia (`rowVersion`, 409 = recargar), publicación y archivo. */
class LearningPathAdminViewModel(
    private val manage: ManageLearningPaths,
    private val listCourses: ListAdminCourses,
    private val newId: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(PathAdminState())
    val state: StateFlow<PathAdminState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun reset() { generation++; job?.cancel(); mutable.value = PathAdminState() }

    fun open(organizationId: String, allowed: Boolean) {
        val s = mutable.value
        if (s.organizationId == organizationId && s.allowed == allowed) return
        reset()
        mutable.value = PathAdminState(organizationId, allowed)
        if (allowed) refresh()
    }

    fun refresh() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (!s.allowed) return
        val expected = ++generation
        job?.cancel()
        mutable.value = s.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            val result = manage.list(org, s.status)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(loading = false, items = result.value.items, next = result.value.nextToken)
                is TrainingResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun more() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val token = s.next ?: return
        if (s.loading) return
        val expected = generation
        mutable.value = s.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            val result = manage.list(org, s.status, token)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> {
                    val current = mutable.value
                    val known = current.items.map { it.id }.toSet()
                    current.copy(loading = false, items = current.items + result.value.items.filter { it.id !in known }, next = result.value.nextToken)
                }
                is TrainingResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun filter(status: PathStatus?) { mutable.value = mutable.value.copy(status = status); refresh() }

    private fun loadCourses() {
        val org = mutable.value.organizationId ?: return
        if (mutable.value.allCourses.isNotEmpty()) return
        val expected = generation
        viewModelScope.launch {
            val result = listCourses(org, null)
            if (expected != generation) return@launch
            if (result is TrainingResult.Success) mutable.value = mutable.value.copy(allCourses = result.value)
        }
    }

    // ---- Edición -----------------------------------------------------------------------------

    fun startCreate() {
        loadCourses()
        mutable.value = mutable.value.copy(editor = PathEditor(newId(), PathStatus.DRAFT, null, "", "", emptyList()), editorFailure = null, notice = null)
    }

    fun openPath(path: LearningPath) {
        loadCourses()
        mutable.value = mutable.value.copy(editor = path.toEditor(), editorFailure = null, notice = null)
    }

    private fun LearningPath.toEditor() = PathEditor(id, status, rowVersion, title, description, courses.map { PathEditorCourse(it.courseId, it.code, it.title) })

    fun edit(change: (PathEditor) -> PathEditor) {
        val s = mutable.value
        val editor = s.editor ?: return
        if (s.busy || editor.status == PathStatus.ARCHIVED) return
        mutable.value = s.copy(editor = change(editor).copy(dirty = true), editorFailure = null)
    }

    fun addCourse(course: AdminCourse) = edit { e ->
        if (e.courses.size >= PathRules.MAX_COURSES || e.courses.any { it.courseId.equals(course.id, true) }) e
        else e.copy(courses = e.courses + PathEditorCourse(course.id, course.code, course.title))
    }

    fun removeCourse(courseId: String) = edit { e -> e.copy(courses = e.courses.filterNot { it.courseId == courseId }) }

    fun moveCourse(courseId: String, step: Int) = edit { e ->
        val from = e.courses.indexOfFirst { it.courseId == courseId }
        val to = from + step
        if (from < 0 || to !in e.courses.indices) e else e.copy(courses = e.courses.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun closeEditor() { if (!mutable.value.busy) { mutable.value = mutable.value.copy(editor = null, editorFailure = null); refresh() } }

    fun save() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val editor = s.editor ?: return
        if (s.busy) return
        if (editor.issues.isNotEmpty()) { mutable.value = s.copy(editor = editor.copy(showIssues = true)); return }
        run(editor, "Ruta guardada.") { manage.save(org, editor.toInput()) }
    }

    fun publish() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val editor = s.editor ?: return
        if (s.busy || editor.isNew || editor.dirty) return
        run(editor, "Ruta publicada: ya la ven quienes aprenden.") { manage.publish(org, editor.id) }
    }

    fun archive() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val editor = s.editor ?: return
        if (s.busy || editor.isNew) return
        run(editor, "Ruta archivada.") { manage.archive(org, editor.id) }
    }

    private fun run(editor: PathEditor, notice: String, call: suspend () -> TrainingResult<LearningPath>) {
        mutable.value = mutable.value.copy(busy = true, editorFailure = null)
        val expected = generation
        viewModelScope.launch {
            val result = call()
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(busy = false, editor = result.value.toEditor(), notice = notice)
                // 409: se conserva la copia local para que la persona decida si recarga la ruta del servidor.
                is TrainingResult.Failure -> mutable.value.copy(busy = false, editor = editor, editorFailure = result.reason)
            }
        }
    }

    /** Descarta la copia local y vuelve a pedir la ruta vigente (tras `TRAINING_PATH_CONFLICT`). */
    fun reloadEditor() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val editor = s.editor ?: return
        if (s.busy || editor.isNew) return
        mutable.value = s.copy(busy = true, editorFailure = null)
        val expected = generation
        viewModelScope.launch {
            val result = manage.detail(org, editor.id)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(busy = false, editor = result.value.toEditor(), notice = "Ruta recargada.")
                is TrainingResult.Failure -> mutable.value.copy(busy = false, editorFailure = result.reason)
            }
        }
    }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    fun back(): Boolean { if (mutable.value.editor == null) return false; closeEditor(); return true }

    override fun onCleared() { reset() }
}

// ======================================================================================
// Verificación pública
// ======================================================================================

sealed interface VerificationOutcome {
    data class Found(val certificate: CertificateVerification) : VerificationOutcome
    data object NotFound : VerificationOutcome
    data class Failed(val reason: TrainingFailure) : VerificationOutcome
}

data class VerificationState(val input: String = "", val checking: Boolean = false, val outcome: VerificationOutcome? = null)

/** Consulta pública de constancias: no requiere sesión ni organización. */
class CertificateVerificationViewModel(private val verify: VerifyTrainingCertificate) : ViewModel() {
    private val mutable = MutableStateFlow(VerificationState())
    val state: StateFlow<VerificationState> = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    fun edit(text: String) { mutable.value = mutable.value.copy(input = text.take(64)) }

    /** Abre con un código recibido en la dirección; no repite una consulta ya resuelta. */
    fun open(code: String) {
        if (code.isBlank() || CertificateCodeRules.normalize(code) == CertificateCodeRules.normalize(mutable.value.input) && mutable.value.outcome != null) return
        mutable.value = VerificationState(input = code.take(64))
        check()
    }

    fun check() {
        val s = mutable.value
        if (s.checking || s.input.isBlank()) return
        val expected = ++generation
        mutable.value = s.copy(checking = true, outcome = null)
        job?.cancel()
        job = viewModelScope.launch {
            val result = verify(s.input)
            if (expected != generation) return@launch
            mutable.value = mutable.value.copy(checking = false, outcome = when (result) {
                is TrainingResult.Success -> VerificationOutcome.Found(result.value)
                is TrainingResult.Failure -> if (result.reason == TrainingFailure.NOT_FOUND) VerificationOutcome.NotFound else VerificationOutcome.Failed(result.reason)
            })
        }
    }

    fun reset() { generation++; job?.cancel(); mutable.value = VerificationState() }

    override fun onCleared() { reset() }
}
