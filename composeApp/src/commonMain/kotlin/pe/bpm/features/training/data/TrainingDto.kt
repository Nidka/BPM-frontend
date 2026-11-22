package pe.bpm.features.training.data

import io.ktor.http.Url
import io.ktor.http.URLProtocol
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import pe.bpm.features.training.domain.*

private val idPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
internal fun trainingId(value: String): String { require(idPattern.matches(value)); return value }
private fun instantOrNull(value: String?): String? = value?.also { Instant.parse(it) }

@Serializable internal data class Envelope<T>(val data: T)
@Serializable internal data class ErrorsEnvelope(val errors: List<ErrorDto> = emptyList())
@Serializable internal data class ErrorDto(val code: String = "")

@Serializable
internal data class CourseInfoDto(
    val id: String,
    val code: String,
    val title: String,
    val description: String = "",
    val estimatedDurationMinutes: Int = 0,
    val status: String? = null,
) {
    fun domain(): TrainingCourseInfo {
        trainingId(id)
        require(code.isNotBlank() && code.length <= TrainingLimits.COURSE_CODE_MAX && title.isNotBlank() && title.length <= 400)
        require(estimatedDurationMinutes in 0..TrainingLimits.DURATION_MAX_MINUTES)
        return TrainingCourseInfo(id, code, title, description, estimatedDurationMinutes)
    }
}

@Serializable internal data class VersionDto(val id: String, val versionNumber: Int, val passingScore: Int, val rowVersion: String? = null)

@Serializable internal data class VideoDto(val mediaId: String, val mimeType: String, val sizeBytes: Long) {
    fun domain(): LessonVideo {
        trainingId(mediaId)
        require(mimeType in TrainingLimits.videoMimeTypes && sizeBytes > 0)
        return LessonVideo(mediaId, mimeType, sizeBytes)
    }
}

/** Video de YouTube: solo `provider` y `videoId`; el servidor nunca entrega una URL. */
@Serializable
internal data class ExternalVideoDto(val provider: String, val videoId: String) {
    fun domain(): ExternalVideo {
        require(provider == "YOUTUBE" && YouTubeLinks.isVideoId(videoId))
        return ExternalVideo(videoId)
    }
}

@Serializable
internal data class LessonDto(
    val id: String,
    val title: String,
    val content: String = "",
    val displayOrder: Int,
    val video: VideoDto? = null,
    val externalVideo: ExternalVideoDto? = null,
    val keyPoints: List<String> = emptyList(),
    val captionsVtt: String = "",
)

/** Subtítulos recibidos: se acotan al máximo del servidor; un servidor anterior no los envía. */
internal fun checkedCaptions(text: String): String { require(text.length <= WebVtt.MAX_LENGTH); return text }

/** Valida «Lo esencial» de una lección; un servidor anterior no lo envía y equivale a vacío. */
internal fun checkedKeyPoints(points: List<String>): List<String> {
    require(points.size <= TrainingLimits.KEY_POINTS_MAX * 4)
    return points
}

/** Vista de aprendizaje: este DTO no declara correctOption ni explanation, así que se ignoran si llegaran. */
@Serializable
internal data class QuestionDto(val id: String, val lessonId: String? = null, val questionText: String, val options: List<String>, val displayOrder: Int)

@Serializable
internal data class ProgressDto(
    val status: String,
    val lastLessonId: String? = null,
    val bestScore: Int? = null,
    val experiencePoints: Int = 0,
    val completedAt: String? = null,
    val completedLessonIds: List<String> = emptyList(),
    val completedLessons: Int = 0,
    val totalLessons: Int = 0,
    val percent: Int = 0,
) {
    fun domain(): TrainingProgress {
        lastLessonId?.let(::trainingId)
        require(bestScore == null || bestScore in 0..100)
        require(experiencePoints >= 0)
        require(completedLessonIds.size <= TrainingLimits.MAX_LESSONS && completedLessonIds.all { idPattern.matches(it) })
        require(completedLessons >= 0 && totalLessons >= 0 && percent in 0..100)
        return TrainingProgress(ProgressStatus.valueOf(status), lastLessonId, bestScore, experiencePoints, instantOrNull(completedAt),
            completedLessonIds, completedLessons, totalLessons, percent)
    }
}

@Serializable
internal data class CertificateDto(val id: String, val verificationCode: String, val issuedAt: String) {
    fun domain(): TrainingCertificate {
        trainingId(id)
        require(Regex("[A-Za-z0-9-]{16,64}").matches(verificationCode))
        Instant.parse(issuedAt)
        return TrainingCertificate(id, verificationCode, issuedAt)
    }
}

@Serializable
internal data class CourseDetailDto(
    val course: CourseInfoDto,
    val version: VersionDto,
    val lessons: List<LessonDto>,
    val questions: List<QuestionDto>,
    val progress: ProgressDto,
    val certificate: CertificateDto? = null,
) {
    fun domain(expectedCourseId: String): TrainingCourse {
        val info = course.domain()
        require(info.id.equals(expectedCourseId, ignoreCase = true))
        trainingId(version.id)
        require(version.versionNumber >= 1 && version.passingScore in 0..100)
        require(lessons.size <= TrainingLimits.MAX_LESSONS && questions.size <= TrainingLimits.MAX_QUESTIONS)
        val mappedLessons = lessons.map {
            require(it.video == null || it.externalVideo == null)
            TrainingLesson(trainingId(it.id), it.title, it.content, it.displayOrder, it.video?.domain(), it.externalVideo?.domain(),
                keyPoints = checkedKeyPoints(it.keyPoints), captionsVtt = checkedCaptions(it.captionsVtt))
        }
        require(mappedLessons.map { it.id }.distinct().size == mappedLessons.size)
        val lessonIds = mappedLessons.map { it.id }.toSet()
        val mappedQuestions = questions.map {
            trainingId(it.id)
            it.lessonId?.let { lesson -> require(lesson in lessonIds) }
            require(it.options.size in TrainingLimits.MIN_OPTIONS..TrainingLimits.MAX_OPTIONS && it.questionText.isNotBlank())
            TrainingQuestion(it.id, it.lessonId, it.questionText, it.options, it.displayOrder)
        }
        require(mappedQuestions.map { it.id }.distinct().size == mappedQuestions.size)
        return TrainingCourse(info, TrainingVersionInfo(version.id, version.versionNumber, version.passingScore),
            mappedLessons.sortedBy { it.displayOrder }, mappedQuestions.sortedBy { it.displayOrder }, progress.domain(), certificate?.domain())
    }
}

@Serializable internal data class VideoTicketDto(val url: String, val mimeType: String, val expiresAt: String) {
    fun domain(): VideoTicket {
        validateHttpsStorageUrl(url)
        require(mimeType in TrainingLimits.videoMimeTypes)
        require(Instant.parse(expiresAt) > Clock.System.now())
        return VideoTicket(url, mimeType, expiresAt)
    }
}

@Serializable internal data class ProgressEnvelope(val progress: ProgressDto)

@Serializable internal data class StudyDto(val correct: Boolean, val correctOption: Int, val explanation: String = "")

@Serializable
internal data class AttemptDto(
    val id: String,
    val attemptType: String,
    val score: Int,
    val passed: Boolean,
    val completedAt: String,
    val xpEarned: Int? = null,
)

/** Siguiente curso sugerido; fuera de una ruta el servidor no manda `pathId`/`pathTitle`. */
@Serializable
internal data class NextCourseDto(val pathId: String? = null, val pathTitle: String? = null, val courseId: String, val courseTitle: String) {
    fun domain(): NextCourse {
        trainingId(courseId)
        require(courseTitle.isNotBlank() && courseTitle.length <= 400 && (pathTitle?.length ?: 0) <= 400)
        return NextCourse(pathId?.takeIf { it.isNotBlank() }?.let(::trainingId), pathTitle?.takeIf { it.isNotBlank() }, courseId, courseTitle)
    }
}

@Serializable
internal data class ResultDto(val questionId: String, val selectedOption: Int, val correct: Boolean, val correctOption: Int, val explanation: String = "")

@Serializable
internal data class AttemptOutcomeDto(
    val attempt: AttemptDto,
    val results: List<ResultDto>,
    val progress: ProgressDto,
    val certificate: CertificateDto? = null,
    val achievements: List<AchievementDto> = emptyList(),
    val nextCourse: NextCourseDto? = null,
) {
    fun domain(sent: AttemptSubmission): AttemptOutcome {
        require(attempt.xpEarned == null || attempt.xpEarned in 0..1_000_000)
        require(attempt.id.equals(sent.id, ignoreCase = true) && AttemptType.valueOf(attempt.attemptType) == sent.attemptType)
        require(achievements.size <= 50)
        require(attempt.score in 0..100 && results.size == sent.answers.size)
        Instant.parse(attempt.completedAt)
        val mapped = results.map { result ->
            val answer = sent.answers.firstOrNull { it.questionId.equals(result.questionId, ignoreCase = true) }
            require(answer != null && answer.selectedOption == result.selectedOption)
            require(result.correct == (result.selectedOption == result.correctOption))
            QuestionResult(result.questionId, result.selectedOption, result.correct, result.correctOption, result.explanation)
        }
        require(mapped.map { it.questionId.lowercase() }.distinct().size == mapped.size)
        require(attempt.score == ((mapped.count { it.correct } * 100.0) / mapped.size + 0.5).toInt())
        return AttemptOutcome(AttemptSummary(attempt.id, sent.attemptType, attempt.score, attempt.passed, attempt.completedAt, attempt.xpEarned), mapped, progress.domain(),
            certificate?.domain(), achievements.map { it.domain() }, nextCourse?.domain())
    }
}

// ---- Autoría -------------------------------------------------------------------------------

@Serializable
internal data class AdminCourseDto(val id: String, val code: String, val title: String, val status: String, val publishedVersionNumber: Int? = null, val hasDraft: Boolean = false) {
    fun domain(): AdminCourse {
        trainingId(id)
        return AdminCourse(id, code, title, CourseStatus.valueOf(status), publishedVersionNumber, hasDraft)
    }
}

@Serializable internal data class AdminListDto(val items: List<AdminCourseDto>)

@Serializable internal data class DraftLessonDto(
    val id: String,
    val title: String,
    val content: String = "",
    val displayOrder: Int,
    val videoMediaId: String? = null,
    val externalVideo: ExternalVideoDto? = null,
    val keyPoints: List<String> = emptyList(),
    val captionsVtt: String = "",
)

@Serializable
internal data class DraftQuestionDto(
    val id: String,
    val lessonId: String? = null,
    val questionText: String,
    val options: List<String>,
    val correctOption: Int,
    val explanation: String = "",
    val displayOrder: Int,
)

@Serializable
internal data class DraftDto(
    val course: CourseInfoDto? = null,
    val version: VersionDto,
    val lessons: List<DraftLessonDto>,
    val questions: List<DraftQuestionDto>,
) {
    fun domain(fallback: TrainingCourseInfo?, expectedCourseId: String): TrainingDraft {
        val info = course?.domain() ?: fallback ?: error("curso")
        require(info.id.equals(expectedCourseId, ignoreCase = true))
        trainingId(version.id)
        val rowVersion = requireNotNull(version.rowVersion) { "rowVersion" }
        require(rowVersion.isNotEmpty() && rowVersion.length <= 64)
        require(lessons.size <= TrainingLimits.MAX_LESSONS && questions.size <= TrainingLimits.MAX_QUESTIONS)
        return TrainingDraft(info, version.id, version.versionNumber, version.passingScore, rowVersion,
            lessons.map {
                require(it.videoMediaId == null || it.externalVideo == null)
                DraftLesson(trainingId(it.id), it.title, it.content, it.displayOrder, it.videoMediaId?.let(::trainingId), it.externalVideo?.domain()?.videoId,
                    checkedKeyPoints(it.keyPoints), checkedCaptions(it.captionsVtt))
            }.sortedBy { it.displayOrder },
            questions.map {
                trainingId(it.id)
                require(it.options.size in TrainingLimits.MIN_OPTIONS..TrainingLimits.MAX_OPTIONS && it.correctOption in it.options.indices)
                DraftQuestion(it.id, it.lessonId?.let(::trainingId), it.questionText, it.options, it.correctOption, it.explanation, it.displayOrder)
            }.sortedBy { it.displayOrder })
    }
}

@Serializable internal data class MediaFileDto(val id: String, val originalName: String, val mimeType: String, val sizeBytes: Long, val status: String) {
    fun domain(): VideoFile {
        trainingId(id)
        require(mimeType in TrainingLimits.videoMimeTypes && sizeBytes > 0)
        return VideoFile(id, originalName, mimeType, sizeBytes)
    }
}

@Serializable internal data class UploadTicketDto(
    val method: String = "PUT",
    val url: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val expiresAt: String,
) {
    /** Solo se firma contra R2 por HTTPS y con las cabeceras esperadas para el contenido exacto. */
    fun validate(mimeType: String, size: Int, sha256: String) {
        require(method == "PUT")
        validateHttpsStorageUrl(url)
        require(Instant.parse(expiresAt) > Clock.System.now())
        val normalized = headers.mapKeys { it.key.lowercase() }
        require(normalized.size == headers.size && normalized.size <= 10)
        val allowed = setOf("content-type", "if-none-match", "cache-control", "content-disposition", "x-amz-meta-sha256", "host",
            "content-length", "x-amz-checksum-sha256", "x-amz-content-sha256")
        require(normalized.keys.all { it in allowed })
        require(normalized.values.all { values -> values.size in 1..4 && values.all { it.length <= 4096 && it.none(Char::isISOControl) } })
        require(normalized["content-type"] == listOf(mimeType))
        normalized["if-none-match"]?.let { require(it == listOf("*")) }
        normalized["x-amz-meta-sha256"]?.let { require(it == listOf(sha256)) }
        normalized["host"]?.let { require(it == listOf(Url(url).host)) }
        normalized["content-length"]?.let { require(it == listOf(size.toString())) }
    }
}

@Serializable internal data class UploadReservationDto(val file: MediaFileDto, val upload: UploadTicketDto? = null)

internal fun validateHttpsStorageUrl(url: String) {
    val target = Url(url)
    require(target.protocol == URLProtocol.HTTPS && target.port == 443 && target.user.isNullOrEmpty() && target.password.isNullOrEmpty() && target.fragment.isEmpty())
    require(target.host.endsWith(".r2.cloudflarestorage.com") &&
        target.host.removeSuffix(".r2.cloudflarestorage.com").matches(Regex("[A-Za-z0-9.-]{1,150}")))
}

