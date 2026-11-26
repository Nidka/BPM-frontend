package pe.bpm.features.training.domain

/** Página de un listado con cursor opaco; `nextToken` nulo indica que no hay más. */
data class TrainingPage<T>(val items: List<T>, val nextToken: String?)

// ---- YouTube ---------------------------------------------------------------------------------

/**
 * Reglas puras de los enlaces de YouTube. El servidor normaliza a `videoId`; el cliente repite la
 * misma comprobación para no enviar enlaces que el contrato rechazaría y para armar la dirección
 * de inserción sin cookies (`youtube-nocookie.com`).
 */
object YouTubeLinks {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com")

    fun isVideoId(value: String): Boolean = idPattern.matches(value)

    fun embedUrl(videoId: String): String {
        require(isVideoId(videoId))
        return "https://www.youtube-nocookie.com/embed/$videoId"
    }

    fun watchUrl(videoId: String): String {
        require(isVideoId(videoId))
        return "https://www.youtube.com/watch?v=$videoId"
    }

    /**
     * Extrae el identificador de las URL admitidas: `watch?v=`, `youtu.be/`, `shorts/` y
     * `youtube-nocookie.com/embed/`. Solo http/https, sin credenciales ni puerto; cualquier otro
     * dominio o formato devuelve `null`.
     */
    fun idFromUrl(text: String): String? {
        val raw = text.trim()
        val scheme = when {
            raw.startsWith("https://", ignoreCase = true) -> 8
            raw.startsWith("http://", ignoreCase = true) -> 7
            else -> return null
        }
        val rest = raw.substring(scheme)
        if (rest.any { it.isISOControl() || it.isWhitespace() }) return null
        val withoutFragment = rest.substringBefore('#')
        val authority = withoutFragment.substringBefore('/').substringBefore('?')
        if ('@' in authority || ':' in authority) return null
        val host = authority.lowercase()
        val afterHost = withoutFragment.removePrefix(authority)
        val path = afterHost.substringBefore('?')
        val query = if ('?' in afterHost) afterHost.substringAfter('?') else ""
        val candidate = when {
            host in hosts && path.trimEnd('/') == "/watch" ->
                query.split('&').map { it.split('=', limit = 2) }.firstOrNull { it.size == 2 && it[0] == "v" }?.get(1)
            host in hosts -> path.removePrefix("/shorts/").takeIf { path.startsWith("/shorts/") }?.trimEnd('/')
            host == "youtu.be" -> path.removePrefix("/").trimEnd('/').takeIf { path.startsWith("/") }
            host == "youtube-nocookie.com" || host == "www.youtube-nocookie.com" ->
                path.removePrefix("/embed/").takeIf { path.startsWith("/embed/") }?.trimEnd('/')
            else -> null
        }
        return candidate?.takeIf(::isVideoId)
    }
}

// ---- Asignaciones (RF-06) --------------------------------------------------------------------

/** Estado calculado por el servidor para quien aprende. */
enum class AssignmentProgress { PENDING, IN_PROGRESS, COMPLETED, OVERDUE }

data class MyAssignment(
    val assignmentId: String,
    val course: TrainingCourseInfo,
    val dueAt: String?,
    val assignedAt: String,
    val status: AssignmentProgress,
    val progressStatus: ProgressStatus,
    val bestScore: Int?,
    val completedAt: String?,
    /** Avance del curso 0..100; 0 en servidores anteriores. */
    val percent: Int = 0,
)

enum class AssignmentRecordStatus { ACTIVE, CANCELLED }

/** Destinatario de una asignación: persona, rol (con sede opcional) o sede completa. */
sealed interface AssignmentTarget {
    data class User(val userId: String, val userName: String = "") : AssignmentTarget
    data class Role(val role: String, val locationId: String? = null, val locationName: String = "") : AssignmentTarget
    data class Location(val locationId: String, val locationName: String = "") : AssignmentTarget
}

data class AssignmentCourse(val id: String, val code: String, val title: String)

data class Assignment(
    val id: String,
    val course: AssignmentCourse,
    val target: AssignmentTarget,
    val dueAt: String?,
    val status: AssignmentRecordStatus,
    val createdAt: String,
    val cancelledAt: String?,
)

/** Alta de asignación: `id` lo genera el cliente, así repetir el envío no duplica. */
data class NewAssignment(val id: String, val courseId: String, val target: AssignmentTarget, val dueAt: String?)

// ---- Insignias, logros y puntos (RF-10) ----------------------------------------------------------

enum class AchievementCriterion { COURSES_COMPLETED, PERFECT_SCORE, STREAK_DAYS }

data class Achievement(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val points: Int,
    val criterion: AchievementCriterion,
    val criterionThreshold: Int,
    val active: Boolean,
    /** Nulo mientras la persona no la obtiene. */
    val awardedAt: String?,
)

data class ExperienceTotals(val experiencePoints: Int, val courseExperiencePoints: Int, val moduleExperiencePoints: Int, val achievementPoints: Int)

/**
 * Racha de días con aprendizaje, calculada por el servidor en la zona de la persona. [lastActivityDate] es una
 * fecha `yyyy-MM-dd`; [activeToday] indica si ya aprendió hoy (la racha sigue vigente si terminó ayer).
 */
data class LearningStreak(
    val currentDays: Int,
    val longestDays: Int,
    val lastActivityDate: String?,
    val activeToday: Boolean,
    val timezone: String,
)

data class MyAchievements(
    val totals: ExperienceTotals,
    val achievements: List<Achievement>,
    /** Nulo en servidores anteriores. */
    val streak: LearningStreak? = null,
) {
    val earned: List<Achievement> get() = achievements.filter { it.awardedAt != null }
    val pending: List<Achievement> get() = achievements.filter { it.awardedAt == null }
}

/** Campos editables de una insignia según el contrato; el código y el criterio no cambian. */
data class AchievementEdit(val name: String, val description: String, val points: Int, val criterionThreshold: Int, val active: Boolean)

object AchievementRules {
    const val NAME_MIN = 2
    // No usar NAME_MAX: choca con la macro de C en el encabezado ObjC de iOS.
    const val NAME_LENGTH_MAX = 120
    const val DESCRIPTION_MAX = 300
    const val POINTS_MAX = 1000
    const val THRESHOLD_MAX = 100

    /** Mensajes de validación en español; vacío significa válido. */
    fun validate(edit: AchievementEdit): List<String> = buildList {
        if (edit.name.trim().count { !it.isLowSurrogate() } !in NAME_MIN..NAME_LENGTH_MAX) add("El nombre debe tener entre $NAME_MIN y $NAME_LENGTH_MAX caracteres.")
        if (edit.description.count { !it.isLowSurrogate() } > DESCRIPTION_MAX) add("La descripción admite hasta $DESCRIPTION_MAX caracteres.")
        if (edit.points !in 0..POINTS_MAX) add("Los puntos van de 0 a $POINTS_MAX.")
        if (edit.criterionThreshold !in 1..THRESHOLD_MAX) add("El umbral va de 1 a $THRESHOLD_MAX.")
    }
}

// ---- Rutas de aprendizaje (RF-40) ------------------------------------------------------------

enum class PathStatus { DRAFT, PUBLISHED, ARCHIVED }

data class PathCourse(
    val courseId: String,
    val code: String,
    val title: String,
    val position: Int,
    val courseStatus: CourseStatus,
    /** Avance de quien consulta; nulo para SUPER_ADMIN. */
    val progressStatus: ProgressStatus?,
)

data class PathProgress(val status: ProgressStatus, val completedCourses: Int, val totalCourses: Int, val percent: Int, val nextCourseId: String?)

data class LearningPath(
    val id: String,
    val title: String,
    val description: String,
    val status: PathStatus,
    /** Token opaco de concurrencia; se reenvía tal cual al editar. */
    val rowVersion: String,
    val publishedAt: String?,
    val courses: List<PathCourse>,
    val progress: PathProgress?,
)

/** Alta o edición de ruta: [rowVersion] es nulo al crear y obligatorio al editar. */
data class PathInput(val id: String, val title: String, val description: String, val courseIds: List<String>, val rowVersion: String? = null)

object PathRules {
    const val TITLE_MIN = 2
    const val TITLE_MAX = 200
    const val DESCRIPTION_MAX = 2000
    const val MAX_COURSES = 30

    fun validate(input: PathInput): List<String> = buildList {
        if (input.title.trim().count { !it.isLowSurrogate() } !in TITLE_MIN..TITLE_MAX) add("El título debe tener entre $TITLE_MIN y $TITLE_MAX caracteres.")
        if (input.description.count { !it.isLowSurrogate() } > DESCRIPTION_MAX) add("La descripción admite hasta $DESCRIPTION_MAX caracteres.")
        if (input.courseIds.size > MAX_COURSES) add("Una ruta admite hasta $MAX_COURSES cursos.")
        if (input.courseIds.map { it.lowercase() }.toSet().size != input.courseIds.size) add("Un curso no puede repetirse en la ruta.")
    }
}

// ---- Verificación pública --------------------------------------------------------------------

enum class CertificateStatus { VALID, REVOKED }

/** Resultado público: nunca incluye identificadores, correo, puntaje ni el código. */
data class CertificateVerification(
    val status: CertificateStatus,
    val courseTitle: String,
    val versionNumber: Int,
    val issuedAt: String,
    val organization: String,
    val holder: String,
)

object CertificateCodeRules {
    private val pattern = Regex("[A-Z2-7]{24}")

    /** Quita espacios y guiones de lectura y pasa a mayúsculas. */
    fun normalize(raw: String): String = raw.filter { !it.isWhitespace() && it != '-' }.uppercase()
    fun isValid(code: String): Boolean = pattern.matches(code)
}

// ---- Repositorios ----------------------------------------------------------------------------

/** Lecturas de quien aprende (ADMIN, CHEF y WORKER con membresía; las rutas también para SUPER_ADMIN). */
interface TrainingLearnerRepository {
    suspend fun myAssignments(org: String, pageToken: String?): TrainingResult<TrainingPage<MyAssignment>>
    suspend fun myAchievements(org: String): TrainingResult<MyAchievements>
    suspend fun paths(org: String, pageToken: String?): TrainingResult<TrainingPage<LearningPath>>
    suspend fun path(org: String, pathId: String): TrainingResult<LearningPath>
}

/** Administración de asignaciones, insignias y rutas (ADMIN y SUPER_ADMIN; el backend reautoriza). */
interface TrainingPlusAdminRepository {
    suspend fun createAssignment(org: String, input: NewAssignment): TrainingResult<Assignment>
    suspend fun assignments(org: String, status: AssignmentRecordStatus?, courseId: String?, pageToken: String?): TrainingResult<TrainingPage<Assignment>>
    suspend fun cancelAssignment(org: String, assignmentId: String): TrainingResult<Assignment>

    suspend fun achievements(org: String): TrainingResult<List<Achievement>>
    suspend fun updateAchievement(org: String, achievementId: String, edit: AchievementEdit): TrainingResult<Achievement>

    suspend fun adminPaths(org: String, status: PathStatus?, pageToken: String?): TrainingResult<TrainingPage<LearningPath>>
    suspend fun adminPath(org: String, pathId: String): TrainingResult<LearningPath>
    suspend fun createPath(org: String, input: PathInput): TrainingResult<LearningPath>
    suspend fun updatePath(org: String, input: PathInput): TrainingResult<LearningPath>
    suspend fun publishPath(org: String, pathId: String): TrainingResult<LearningPath>
    suspend fun archivePath(org: String, pathId: String): TrainingResult<LearningPath>
}

/** Consulta pública sin sesión: no envía credenciales. */
interface CertificateVerificationRepository {
    suspend fun verify(code: String): TrainingResult<CertificateVerification>
}

// ---- Casos de uso ----------------------------------------------------------------------------

class LoadMyAssignments(private val repository: TrainingLearnerRepository) {
    suspend operator fun invoke(org: String, pageToken: String? = null) = repository.myAssignments(org, pageToken)
}

class LoadMyAchievements(private val repository: TrainingLearnerRepository) {
    suspend operator fun invoke(org: String) = repository.myAchievements(org)
}

class LoadLearningPaths(private val repository: TrainingLearnerRepository) {
    suspend operator fun invoke(org: String, pageToken: String? = null) = repository.paths(org, pageToken)
    suspend fun detail(org: String, pathId: String) = repository.path(org, pathId)
}

class CreateTrainingAssignment(private val repository: TrainingPlusAdminRepository) {
    suspend operator fun invoke(org: String, input: NewAssignment): TrainingResult<Assignment> {
        val target = input.target
        val valid = when (target) {
            is AssignmentTarget.User -> target.userId.isNotBlank()
            is AssignmentTarget.Role -> target.role in setOf("ADMIN", "CHEF", "WORKER")
            is AssignmentTarget.Location -> target.locationId.isNotBlank()
        }
        if (!valid) return TrainingResult.Failure(TrainingFailure.INVALID)
        return repository.createAssignment(org, input)
    }
}

class ListTrainingAssignments(private val repository: TrainingPlusAdminRepository) {
    suspend operator fun invoke(org: String, status: AssignmentRecordStatus?, courseId: String?, pageToken: String? = null) =
        repository.assignments(org, status, courseId, pageToken)
}

class CancelTrainingAssignment(private val repository: TrainingPlusAdminRepository) {
    suspend operator fun invoke(org: String, assignmentId: String) = repository.cancelAssignment(org, assignmentId)
}

class ListTrainingAchievements(private val repository: TrainingPlusAdminRepository) {
    suspend operator fun invoke(org: String) = repository.achievements(org)
}

class UpdateTrainingAchievement(private val repository: TrainingPlusAdminRepository) {
    suspend operator fun invoke(org: String, achievementId: String, edit: AchievementEdit): TrainingResult<Achievement> {
        if (AchievementRules.validate(edit).isNotEmpty()) return TrainingResult.Failure(TrainingFailure.INVALID)
        return repository.updateAchievement(org, achievementId, edit.copy(name = edit.name.trim()))
    }
}

/** Gestión de rutas: valida los límites del contrato antes de enviar. */
class ManageLearningPaths(private val repository: TrainingPlusAdminRepository) {
    suspend fun list(org: String, status: PathStatus?, pageToken: String? = null) = repository.adminPaths(org, status, pageToken)
    suspend fun detail(org: String, pathId: String) = repository.adminPath(org, pathId)
    suspend fun save(org: String, input: PathInput): TrainingResult<LearningPath> {
        if (PathRules.validate(input).isNotEmpty()) return TrainingResult.Failure(TrainingFailure.INVALID)
        val clean = input.copy(title = input.title.trim())
        return if (input.rowVersion == null) repository.createPath(org, clean) else repository.updatePath(org, clean)
    }
    suspend fun publish(org: String, pathId: String) = repository.publishPath(org, pathId)
    suspend fun archive(org: String, pathId: String) = repository.archivePath(org, pathId)
}

/** Verifica un código sin llamar a la red cuando su formato es imposible (equivale a «no existe»). */
class VerifyTrainingCertificate(private val repository: CertificateVerificationRepository) {
    suspend operator fun invoke(rawCode: String): TrainingResult<CertificateVerification> {
        val code = CertificateCodeRules.normalize(rawCode)
        if (!CertificateCodeRules.isValid(code)) return TrainingResult.Failure(TrainingFailure.NOT_FOUND)
        return repository.verify(code)
    }
}
