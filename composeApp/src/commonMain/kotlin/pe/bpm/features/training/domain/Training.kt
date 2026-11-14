package pe.bpm.features.training.domain

/** Información pública del curso, común a las vistas de aprendizaje y administración. */
data class TrainingCourseInfo(
    val id: String,
    val code: String,
    val title: String,
    val description: String,
    val estimatedDurationMinutes: Int,
)

data class TrainingVersionInfo(val id: String, val versionNumber: Int, val passingScore: Int)

/** [sha256] solo viene en el catálogo descargable; con él se verifica el archivo guardado. */
data class LessonVideo(val mediaId: String, val mimeType: String, val sizeBytes: Long, val sha256: String? = null)

/** Video externo de YouTube: el contrato solo entrega el identificador, nunca una URL. */
data class ExternalVideo(val videoId: String) {
    /** Dirección de inserción sin cookies de seguimiento; requiere conexión a internet. */
    val embedUrl: String get() = YouTubeLinks.embedUrl(videoId)
    val watchUrl: String get() = YouTubeLinks.watchUrl(videoId)
}

data class TrainingLesson(
    val id: String,
    val title: String,
    /** Texto plano con saltos de línea; también sirve de transcripción del video. */
    val content: String,
    val displayOrder: Int,
    val video: LessonVideo?,
    /** Alternativa en línea al video privado; una lección tiene como máximo uno de los dos. */
    val externalVideo: ExternalVideo? = null,
    /** false para videos de YouTube: solo se ven con conexión. */
    val offlineAvailable: Boolean = true,
)

/** Pregunta de la vista de aprendizaje: por contrato nunca trae la respuesta ni la explicación. */
data class TrainingQuestion(
    val id: String,
    val lessonId: String?,
    val questionText: String,
    val options: List<String>,
    val displayOrder: Int,
)

enum class ProgressStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }

data class TrainingProgress(
    val status: ProgressStatus,
    val lastLessonId: String?,
    val bestScore: Int?,
    val experiencePoints: Int,
    val completedAt: String?,
)

data class TrainingCertificate(val id: String, val verificationCode: String, val issuedAt: String)

data class TrainingCourse(
    val course: TrainingCourseInfo,
    val version: TrainingVersionInfo,
    val lessons: List<TrainingLesson>,
    val questions: List<TrainingQuestion>,
    val progress: TrainingProgress,
    val certificate: TrainingCertificate?,
    /** Instante (ms) en que se guardó esta copia local cuando no se pudo consultar al servidor; null si es reciente. */
    val cachedAt: Long? = null,
) {
    /** Lección desde la cual se retoma: la última vista si sigue publicada o la primera (RF-11). */
    fun resumeLesson(): TrainingLesson? =
        lessons.firstOrNull { it.id == progress.lastLessonId } ?: lessons.minByOrNull { it.displayOrder }
}

/** URL firmada temporal de reproducción. Solo vive en memoria; nunca se guarda ni se registra. */
data class VideoTicket(val url: String, val mimeType: String, val expiresAt: String, val local: Boolean = false)

/** Resultado de practicar en modo estudio: informativo, sin puntaje ni persistencia. */
data class StudyFeedback(val correct: Boolean, val correctOption: Int, val explanation: String)

/** [MODULE] evalúa solo las preguntas de una lección: no completa el curso ni emite constancia. */
enum class AttemptType { DIAGNOSTIC, FINAL, MODULE }

data class AttemptAnswer(val questionId: String, val selectedOption: Int)

/**
 * Intento listo para enviar. Todos los identificadores y marcas de tiempo se fijan al crearlo:
 * reenviar el mismo objeto tras un fallo de red es idempotente en el servidor.
 */
data class AttemptSubmission(
    val id: String,
    val clientOperationId: String,
    val versionId: String,
    val attemptType: AttemptType,
    val startedAt: String,
    val clientCreatedAt: String,
    val answers: List<AttemptAnswer>,
    /** Obligatoria y exclusiva de [AttemptType.MODULE]. */
    val lessonId: String? = null,
)

data class QuestionResult(
    val questionId: String,
    val selectedOption: Int,
    val correct: Boolean,
    val correctOption: Int,
    val explanation: String,
)

data class AttemptSummary(val id: String, val attemptType: AttemptType, val score: Int, val passed: Boolean, val completedAt: String)

data class AttemptOutcome(
    val attempt: AttemptSummary,
    val results: List<QuestionResult>,
    val progress: TrainingProgress,
    val certificate: TrainingCertificate?,
    /** Insignias otorgadas por este intento (también en la repetición idéntica). */
    val achievements: List<Achievement> = emptyList(),
)

/** Motivos públicos y sanitizados; el detalle técnico no llega a la interfaz. */
enum class TrainingFailure {
    CONNECTION, SESSION, DENIED, NOT_FOUND, VERSION_OUTDATED, CONFLICT, DRAFT_CONFLICT, CODE_TAKEN,
    ANSWERS_INVALID, DRAFT_INCOMPLETE, INVALID, CONTENT_INVALID, TOO_LARGE, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE,
    ASSIGNMENT_EXISTS, PATH_CONFLICT, PATH_ARCHIVED, COURSE_NOT_PUBLISHED,
    /** La operación quedó guardada en este dispositivo y se enviará al recuperar la conexión (éxito local pendiente). */
    QUEUED;

    /** Un reintento con el mismo cuerpo puede resolver el fallo sin riesgo de duplicar. */
    val retryable: Boolean
        get() = this in setOf(CONNECTION, UNAVAILABLE, RATE_LIMITED, INVALID_RESPONSE)
}

sealed interface TrainingResult<out T> {
    data class Success<T>(val value: T) : TrainingResult<T>
    data class Failure(val reason: TrainingFailure) : TrainingResult<Nothing>
}

/** Consulta y práctica: disponible para cualquier miembro activo. */
interface TrainingRepository {
    suspend fun course(org: String, courseId: String): TrainingResult<TrainingCourse>
    suspend fun videoTicket(org: String, courseId: String, lessonId: String): TrainingResult<VideoTicket>
    suspend fun saveProgress(org: String, courseId: String, lessonId: String): TrainingResult<TrainingProgress>
    suspend fun checkStudyAnswer(org: String, courseId: String, questionId: String, selected: Int): TrainingResult<StudyFeedback>
    suspend fun submitAttempt(org: String, courseId: String, attempt: AttemptSubmission): TrainingResult<AttemptOutcome>
}
