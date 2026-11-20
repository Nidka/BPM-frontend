package pe.bpm.features.training.data

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import pe.bpm.features.training.domain.*

private fun stamp(value: String): String = value.also { Instant.parse(it) }
private fun stampOrNull(value: String?): String? = value?.let(::stamp)
private val roles = setOf("ADMIN", "CHEF", "WORKER")

@Serializable internal data class PaginationDto(val nextPageToken: String = "")

/** Listado paginado: `{data:{items}, pagination:{nextPageToken}}`; la ausencia de paginación equivale a «sin más». */
@Serializable internal data class PagedEnvelope<T>(val data: ItemsDto<T>, val pagination: PaginationDto? = null) {
    fun <R> page(map: (T) -> R): TrainingPage<R> {
        val token = pagination?.nextPageToken.orEmpty()
        require(token.length <= 2048 && token.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '=' })
        return TrainingPage(data.items.map(map), token.ifEmpty { null })
    }
}

@Serializable internal data class ItemsDto<T>(val items: List<T>)

// ---- Asignaciones ----------------------------------------------------------------------------

@Serializable
internal data class MyAssignmentDto(
    val assignmentId: String,
    val course: CourseInfoDto,
    val dueAt: String? = null,
    val assignedAt: String,
    val status: String,
    val progressStatus: String,
    val bestScore: Int? = null,
    val completedAt: String? = null,
    val percent: Int = 0,
) {
    fun domain(): MyAssignment {
        require(bestScore == null || bestScore in 0..100)
        require(percent in 0..100)
        return MyAssignment(trainingId(assignmentId), course.domain(), stampOrNull(dueAt), stamp(assignedAt),
            AssignmentProgress.valueOf(status), ProgressStatus.valueOf(progressStatus), bestScore, stampOrNull(completedAt), percent)
    }
}

@Serializable internal data class AssignmentCourseDto(val id: String, val code: String, val title: String)

@Serializable
internal data class AssignmentTargetDto(
    val type: String,
    val userId: String? = null,
    val userName: String? = null,
    val role: String? = null,
    val locationId: String? = null,
    val locationName: String? = null,
) {
    fun domain(): AssignmentTarget = when (type) {
        "USER" -> AssignmentTarget.User(requireNotNull(userId).also { require(it.isNotBlank() && it.length <= 128) }, userName.orEmpty())
        "ROLE" -> AssignmentTarget.Role(requireNotNull(role).also { require(it in roles) }, locationId?.let(::trainingId), locationName.orEmpty())
        "LOCATION" -> AssignmentTarget.Location(trainingId(requireNotNull(locationId)), locationName.orEmpty())
        else -> throw IllegalArgumentException("destinatario")
    }
}

@Serializable
internal data class AssignmentDto(
    val id: String,
    val course: AssignmentCourseDto,
    val target: AssignmentTargetDto,
    val dueAt: String? = null,
    val status: String,
    val createdAt: String,
    val cancelledAt: String? = null,
) {
    fun domain(): Assignment {
        require(course.code.isNotBlank() && course.title.isNotBlank())
        return Assignment(trainingId(id), AssignmentCourse(trainingId(course.id), course.code, course.title), target.domain(), stampOrNull(dueAt),
            AssignmentRecordStatus.valueOf(status), stamp(createdAt), stampOrNull(cancelledAt))
    }
}

@Serializable internal data class AssignmentEnvelope(val assignment: AssignmentDto)

// ---- Insignias -------------------------------------------------------------------------------

@Serializable
internal data class AchievementDto(
    val id: String,
    val code: String,
    val name: String,
    val description: String = "",
    val points: Int,
    val criterionType: String,
    val criterionThreshold: Int,
    val active: Boolean = true,
    val awardedAt: String? = null,
) {
    fun domain(): Achievement {
        require(code.isNotBlank() && code.length <= 64 && name.isNotBlank() && points in 0..100_000 && criterionThreshold in 1..1000)
        return Achievement(trainingId(id), code, name, description, points, AchievementCriterion.valueOf(criterionType), criterionThreshold, active, stampOrNull(awardedAt))
    }
}

@Serializable
internal data class TotalsDto(val experiencePoints: Int, val courseExperiencePoints: Int = 0, val moduleExperiencePoints: Int = 0, val achievementPoints: Int = 0) {
    fun domain(): ExperienceTotals {
        require(listOf(experiencePoints, courseExperiencePoints, moduleExperiencePoints, achievementPoints).all { it >= 0 })
        return ExperienceTotals(experiencePoints, courseExperiencePoints, moduleExperiencePoints, achievementPoints)
    }
}

@Serializable
internal data class MyAchievementsDto(val totals: TotalsDto, val achievements: List<AchievementDto>, val streak: StreakDto? = null) {
    fun domain(): MyAchievements {
        require(achievements.size <= 200)
        return MyAchievements(totals.domain(), achievements.map { it.domain() }, streak?.domain())
    }
}

@Serializable
internal data class StreakDto(
    val currentDays: Int = 0,
    val longestDays: Int = 0,
    val lastActivityDate: String? = null,
    val activeToday: Boolean = false,
    val timezone: String = "",
) {
    fun domain(): LearningStreak {
        require(currentDays in 0..100_000 && longestDays in 0..100_000 && timezone.length <= 80)
        require(lastActivityDate == null || Regex("\\d{4}-\\d{2}-\\d{2}").matches(lastActivityDate))
        return LearningStreak(currentDays, longestDays, lastActivityDate, activeToday, timezone)
    }
}

@Serializable internal data class AchievementEnvelope(val achievement: AchievementDto)

// ---- Rutas -----------------------------------------------------------------------------------

@Serializable
internal data class PathCourseDto(
    val courseId: String,
    val code: String,
    val title: String,
    val position: Int,
    val courseStatus: String,
    val progressStatus: String? = null,
)

@Serializable
internal data class PathProgressDto(val status: String, val completedCourses: Int, val totalCourses: Int, val percent: Int, val nextCourseId: String? = null)

@Serializable
internal data class PathDto(
    val id: String,
    val title: String,
    val description: String = "",
    val status: String,
    val rowVersion: String,
    val publishedAt: String? = null,
    val courses: List<PathCourseDto> = emptyList(),
    val progress: PathProgressDto? = null,
) {
    fun domain(): LearningPath {
        require(courses.size <= PathRules.MAX_COURSES && rowVersion.isNotEmpty() && rowVersion.length <= 64 && title.isNotBlank())
        val mapped = courses.map {
            require(it.position >= 1 && it.code.isNotBlank())
            PathCourse(trainingId(it.courseId), it.code, it.title, it.position, CourseStatus.valueOf(it.courseStatus), it.progressStatus?.let(ProgressStatus::valueOf))
        }
        require(mapped.map { it.courseId.lowercase() }.distinct().size == mapped.size)
        val summary = progress?.let {
            require(it.totalCourses >= 0 && it.completedCourses in 0..it.totalCourses && it.percent in 0..100)
            PathProgress(ProgressStatus.valueOf(it.status), it.completedCourses, it.totalCourses, it.percent, it.nextCourseId?.let(::trainingId))
        }
        return LearningPath(trainingId(id), title, description, PathStatus.valueOf(status), rowVersion, stampOrNull(publishedAt), mapped.sortedBy { it.position }, summary)
    }
}

@Serializable internal data class PathEnvelope(val path: PathDto)

// ---- Verificación pública --------------------------------------------------------------------

@Serializable
internal data class CertificateVerificationDto(
    val status: String,
    val courseTitle: String,
    val versionNumber: Int,
    val issuedAt: String,
    val organization: String,
    val holder: String,
) {
    fun domain(): CertificateVerification {
        require(versionNumber >= 1 && courseTitle.isNotBlank() && organization.isNotBlank() && holder.isNotBlank())
        return CertificateVerification(CertificateStatus.valueOf(status), courseTitle, versionNumber, stamp(issuedAt), organization, holder)
    }
}
