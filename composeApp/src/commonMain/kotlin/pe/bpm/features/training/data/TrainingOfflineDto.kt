package pe.bpm.features.training.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import pe.bpm.features.training.domain.*

/** Respuesta de `GET /training/me/offline-catalog`; cada curso se conserva como JSON para guardarlo tal cual. */
@Serializable
internal data class OfflineCatalogDto(val etag: String, val generatedAt: String = "", val courses: List<JsonElement> = emptyList())

@Serializable
internal data class OfflineVideoDto(val mediaId: String, val mimeType: String, val sizeBytes: Long, val sha256: String? = null) {
    fun domain(): LessonVideo {
        trainingId(mediaId)
        require(mimeType in TrainingLimits.videoMimeTypes && sizeBytes > 0)
        return LessonVideo(mediaId, mimeType, sizeBytes, sha256?.takeIf { Regex("[0-9a-fA-F]{64}").matches(it) }?.lowercase())
    }
}

@Serializable
internal data class OfflineLessonDto(
    val id: String,
    val title: String,
    val content: String = "",
    val displayOrder: Int,
    val video: OfflineVideoDto? = null,
    val externalVideo: ExternalVideoDto? = null,
    val offlineAvailable: Boolean = true,
    val keyPoints: List<String> = emptyList(),
)

/** Un curso del catálogo: igual que la vista de aprender pero sin progreso ni constancia. */
@Serializable
internal data class OfflineCourseDto(
    val course: CourseInfoDto,
    val version: VersionDto,
    val lessons: List<OfflineLessonDto>,
    val questions: List<QuestionDto>,
) {
    fun domain(): TrainingCourse {
        val info = course.domain()
        trainingId(version.id)
        require(version.versionNumber >= 1 && version.passingScore in 0..100)
        require(lessons.size <= TrainingLimits.MAX_LESSONS && questions.size <= TrainingLimits.MAX_QUESTIONS)
        val mappedLessons = lessons.map {
            require(it.video == null || it.externalVideo == null)
            val video = it.video?.domain()
            TrainingLesson(trainingId(it.id), it.title, it.content, it.displayOrder, video, it.externalVideo?.domain(),
                offlineAvailable = it.externalVideo == null && it.offlineAvailable && (video == null || video.sha256 != null),
                keyPoints = checkedKeyPoints(it.keyPoints))
        }
        require(mappedLessons.map { it.id }.distinct().size == mappedLessons.size)
        val lessonIds = mappedLessons.map { it.id }.toSet()
        val mappedQuestions = questions.map {
            trainingId(it.id)
            it.lessonId?.let { lesson -> require(lesson in lessonIds) }
            require(it.options.size in TrainingLimits.MIN_OPTIONS..TrainingLimits.MAX_OPTIONS && it.questionText.isNotBlank())
            TrainingQuestion(it.id, it.lessonId, it.questionText, it.options, it.displayOrder)
        }
        return TrainingCourse(info, TrainingVersionInfo(version.id, version.versionNumber, version.passingScore),
            mappedLessons.sortedBy { it.displayOrder }, mappedQuestions.sortedBy { it.displayOrder },
            TrainingProgress(ProgressStatus.NOT_STARTED, null, null, 0, null), null)
    }
}

/** Resultado de pedir el catálogo con `If-None-Match`. */
internal sealed interface CatalogFetch {
    data object NotModified : CatalogFetch
    data class Fresh(val etag: String, val generatedAt: String, val courses: List<Pair<String, String>>) : CatalogFetch
}

/** Retroalimentación del modo estudio guardada tras consultarla con red. */
@Serializable
internal data class CachedStudyFeedback(val correctOption: Int, val explanation: String = "")

/** Registro local de una evaluación; [score] nulo significa «aún sin calificar por el servidor». */
@Serializable
internal data class AttemptRecordDto(
    val attemptId: String,
    val courseId: String,
    val type: String,
    val queuedAt: Long,
    val score: Int? = null,
    val passed: Boolean? = null,
    val completedAt: String? = null,
    val xpEarned: Int? = null,
) {
    fun domain(): AttemptRecord {
        val kind = AttemptType.valueOf(type)
        val result = if (score != null && passed != null && completedAt != null) AttemptSummary(attemptId, kind, score, passed, completedAt, xpEarned) else null
        return AttemptRecord(attemptId, courseId, kind, queuedAt, result)
    }
}

@Serializable
internal data class CatalogMeta(val etag: String, val generatedAt: String = "", val courseIds: List<String> = emptyList(), val syncedAt: Long = 0)
