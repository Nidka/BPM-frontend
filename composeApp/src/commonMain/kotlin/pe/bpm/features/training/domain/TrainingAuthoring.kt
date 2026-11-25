package pe.bpm.features.training.domain

enum class CourseStatus { DRAFT, PUBLISHED, ARCHIVED }

data class AdminCourse(
    val id: String,
    val code: String,
    val title: String,
    val status: CourseStatus,
    val publishedVersionNumber: Int?,
    val hasDraft: Boolean,
)

data class NewCourse(val code: String, val title: String, val description: String, val estimatedDurationMinutes: Int)

data class DraftLesson(
    val id: String,
    val title: String,
    val content: String,
    val displayOrder: Int,
    val videoMediaId: String?,
    /** Identificador de un video de YouTube; excluyente con [videoMediaId]. */
    val externalVideoId: String? = null,
    /** «Lo esencial» de la lección (hasta 5 ideas de 1..200 caracteres); siempre se envía al guardar. */
    val keyPoints: List<String> = emptyList(),
    /** Subtítulos WebVTT del video (RF-45); vacío = sin subtítulos. Siempre se envía al guardar. */
    val captionsVtt: String = "",
)

data class DraftQuestion(
    val id: String,
    val lessonId: String?,
    val questionText: String,
    val options: List<String>,
    val correctOption: Int,
    val explanation: String,
    val displayOrder: Int,
    /** Código del ítem del banco oficial (M01-03): un ítem oficial es de solo lectura; nulo en una pregunta común. */
    val itemCode: String? = null,
) {
    val official: Boolean get() = itemCode != null
}

/** Borrador editable: sí incluye respuesta correcta y explicación; solo lo ve la administración. */
data class TrainingDraft(
    val course: TrainingCourseInfo,
    val versionId: String,
    val versionNumber: Int,
    val passingScore: Int,
    /** Token opaco de concurrencia que el servidor entregó con esta versión del borrador. */
    val rowVersion: String,
    val lessons: List<DraftLesson>,
    val questions: List<DraftQuestion>,
    /**
     * El servidor ya conoce los subtítulos (V16): los incluyó en el borrador. Un servidor anterior rechaza campos
     * desconocidos, así que solo entonces se envían y se ofrece editarlos.
     */
    val captionsSupported: Boolean = false,
)

/** Datos de una reserva de video; los bytes y la firma nunca salen de la operación de subida. */
data class VideoFile(val id: String, val originalName: String, val mimeType: String, val sizeBytes: Long)

/** Archivo de video elegido por la persona, mantenido solo en memoria. */
class PickedVideo(val name: String, val mimeType: String, val bytes: ByteArray)

sealed interface VideoSelection {
    data class Selected(val video: PickedVideo) : VideoSelection
    data class Failed(val reason: VideoSelectionFailure) : VideoSelection
    data object Cancelled : VideoSelection
}

enum class VideoSelectionFailure { TOO_LARGE, UNSUPPORTED, READ_FAILED, UNAVAILABLE }

/** Administración de cursos (ADMIN y SUPER_ADMIN; el backend vuelve a autorizar). */
interface TrainingAuthoringRepository {
    suspend fun courses(org: String, status: CourseStatus?): TrainingResult<List<AdminCourse>>
    suspend fun createCourse(org: String, course: NewCourse): TrainingResult<TrainingCourseInfo>
    suspend fun draft(org: String, courseId: String): TrainingResult<TrainingDraft>
    suspend fun saveDraft(org: String, courseId: String, draft: TrainingDraft): TrainingResult<TrainingDraft>
    suspend fun publish(org: String, courseId: String): TrainingResult<Unit>

    /** Banco oficial del módulo (solo lectura); `NO_QUESTION_BANK` si el curso no lo usa. */
    suspend fun questionBank(org: String, courseId: String): TrainingResult<QuestionBank> = TrainingResult.Failure(TrainingFailure.UNAVAILABLE)

    /**
     * Reserva, sube directo a R2 y confirma. [mediaId] es estable entre reintentos: repetir la
     * operación con el mismo id reutiliza la reserva y comprueba la confirmación antes de otro PUT.
     */
    suspend fun uploadVideo(org: String, mediaId: String, video: PickedVideo, onProgress: (Float) -> Unit): TrainingResult<VideoFile>
}
