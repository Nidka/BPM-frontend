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
    /** «Lo esencial»: hasta 5 ideas clave de la lección; vacío en servidores anteriores. */
    val keyPoints: List<String> = emptyList(),
    /** Subtítulos WebVTT del video (RF-45); vacío si la lección no tiene o el servidor es anterior. */
    val captionsVtt: String = "",
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
    /** Lecciones que la persona terminó (no solo abrió); vacío en servidores anteriores. */
    val completedLessonIds: List<String> = emptyList(),
    val completedLessons: Int = 0,
    /** 0 mientras el servidor no informe el total (catálogo sin conexión o servidor anterior). */
    val totalLessons: Int = 0,
    /** Avance del curso 0..100; 100 si el curso está completado. */
    val percent: Int = 0,
) {
    /**
     * Avance con [lessonId] marcada como terminada, para reflejar de inmediato lo que el servidor confirmará
     * (o lo que quedó en la cola sin conexión). Es idempotente.
     */
    fun withCompletedLesson(lessonId: String): TrainingProgress {
        if (lessonId in completedLessonIds) return this
        val ids = completedLessonIds + lessonId
        val total = maxOf(totalLessons, ids.size)
        val computed = if (status == ProgressStatus.COMPLETED) 100 else (ids.size * 100) / total
        return copy(completedLessonIds = ids, completedLessons = ids.size, totalLessons = total, percent = maxOf(percent, computed))
    }
}

data class TrainingCertificate(val id: String, val verificationCode: String, val issuedAt: String)

/** Fuente oficial de un ítem del banco: código (P01), etiqueta corta («NTS 142 (P01)»), título completo y enlace. */
data class SourceRef(val code: String, val label: String, val title: String = "", val url: String = "")

/** Texto de la línea «Fuente: …» de la retroalimentación; vacío si el ítem no trae fuentes. */
fun sourceLine(sources: List<SourceRef>): String =
    if (sources.isEmpty()) "" else "Fuente: " + sources.joinToString(" · ") { it.label.ifBlank { it.code } }

/** Banco oficial de preguntas del curso: versión, ítems por evaluación y tamaño del banco del módulo. */
data class QuestionBankInfo(val version: Int, val itemsPerAttempt: Int, val poolSize: Int)

/** Repaso espaciado: [dueCount] preguntas por repasar y un lote de [questionIds] (ids de `questions`). */
data class ReviewInfo(val dueCount: Int = 0, val questionIds: List<String> = emptyList())

data class TrainingCourse(
    val course: TrainingCourseInfo,
    val version: TrainingVersionInfo,
    val lessons: List<TrainingLesson>,
    val questions: List<TrainingQuestion>,
    val progress: TrainingProgress,
    val certificate: TrainingCertificate?,
    /** Instante (ms) en que se guardó esta copia local cuando no se pudo consultar al servidor; null si es reciente. */
    val cachedAt: Long? = null,
    /** Banco oficial de preguntas; nulo en un curso común o en un servidor anterior. */
    val bank: QuestionBankInfo? = null,
    /** Preguntas por repasar de quien consulta; vacío sin banco o sin membresía. */
    val review: ReviewInfo = ReviewInfo(),
) {
    /** Lección desde la cual se retoma: la última vista si sigue publicada o la primera (RF-11). */
    fun resumeLesson(): TrainingLesson? =
        lessons.firstOrNull { it.id == progress.lastLessonId } ?: lessons.minByOrNull { it.displayOrder }
}

/** URL firmada temporal de reproducción. Solo vive en memoria; nunca se guarda ni se registra. */
data class VideoTicket(val url: String, val mimeType: String, val expiresAt: String, val local: Boolean = false)

/** Resultado de practicar en modo estudio: informativo, sin puntaje ni persistencia. */
data class StudyFeedback(
    val correct: Boolean,
    val correctOption: Int,
    val explanation: String,
    /** Ítem del banco oficial (M01-03); nulo en una pregunta común. */
    val itemCode: String? = null,
    val sources: List<SourceRef> = emptyList(),
    /** puntos de práctica ganado con esta respuesta (primer acierto del ítem); 0 si no hubo. */
    val xpEarned: Int = 0,
)

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
    /** Datos del ítem del banco: con ellos el resultado se muestra sin buscar la pregunta en el curso. */
    val itemCode: String? = null,
    val bloom: String? = null,
    val questionText: String? = null,
    /** Alternativas en el orden en que se mostraron; [selectedOption] y [correctOption] son posiciones de esta lista. */
    val options: List<String> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
)

/**
 * Evaluación sorteada por el servidor para un curso con banco oficial: 5 preguntas con las alternativas ya barajadas
 * y sin respuesta. Su [id] será el del intento; las respuestas se envían como posición mostrada.
 */
data class DrawnEvaluation(
    val id: String,
    val versionId: String,
    val type: AttemptType,
    val passingScore: Int,
    val bankVersion: Int,
    val attemptNumber: Int,
    val questions: List<TrainingQuestion>,
)

/** Ítem del banco oficial tal como lo ve la administración (con clave y justificación). */
data class BankItem(
    val code: String,
    val bloom: String,
    val questionText: String,
    val options: List<String>,
    val correctOption: Int,
    val key: String,
    val justification: String,
    val sources: List<SourceRef>,
    val recommended: Boolean,
    val lessonTitle: String,
)

/** Banco de un módulo para administración, solo lectura. */
data class QuestionBank(val courseTitle: String, val module: String, val bankVersion: Int, val passingScore: Int, val items: List<BankItem>)

data class AttemptSummary(
    val id: String,
    val attemptType: AttemptType,
    val score: Int,
    val passed: Boolean,
    val completedAt: String,
    /** puntos que ganó con este intento (curso o módulo más insignias); nulo en intentos históricos o servidores anteriores. */
    val xpEarned: Int? = null,
)

/** Siguiente curso sugerido tras aprobar la evaluación final; [pathId] y [pathTitle] son nulos si no viene de una ruta. */
data class NextCourse(val pathId: String?, val pathTitle: String?, val courseId: String, val courseTitle: String)

data class AttemptOutcome(
    val attempt: AttemptSummary,
    val results: List<QuestionResult>,
    val progress: TrainingProgress,
    val certificate: TrainingCertificate?,
    /** Insignias otorgadas por este intento (también en la repetición idéntica). */
    val achievements: List<Achievement> = emptyList(),
    /** Solo tras aprobar la evaluación final; nulo si no hay otro curso pendiente o el servidor es anterior. */
    val nextCourse: NextCourse? = null,
)

/** Motivos públicos y sanitizados; el detalle técnico no llega a la interfaz. */
enum class TrainingFailure {
    CONNECTION, SESSION, DENIED, NOT_FOUND, VERSION_OUTDATED, CONFLICT, DRAFT_CONFLICT, CODE_TAKEN,
    ANSWERS_INVALID, DRAFT_INCOMPLETE, INVALID, CONTENT_INVALID, TOO_LARGE, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE,
    ASSIGNMENT_EXISTS, PATH_CONFLICT, PATH_ARCHIVED, COURSE_NOT_PUBLISHED,
    /** Se envió un intento de un curso con banco sin haber iniciado (sorteado) la evaluación. */
    EVALUATION_NOT_STARTED,
    /** El curso no usa el banco oficial de preguntas. */
    NO_QUESTION_BANK,
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
    /** [lessonId] es la lección abierta; [completedLessonId] marca además una lección como terminada. */
    suspend fun saveProgress(org: String, courseId: String, lessonId: String, completedLessonId: String? = null): TrainingResult<TrainingProgress>
    suspend fun checkStudyAnswer(org: String, courseId: String, questionId: String, selected: Int): TrainingResult<StudyFeedback>
    suspend fun submitAttempt(org: String, courseId: String, attempt: AttemptSubmission): TrainingResult<AttemptOutcome>

    /**
     * Pide al servidor el sorteo de una evaluación del banco oficial; [id] será el id del intento. Repetir la misma
     * solicitud devuelve el mismo sorteo. Sin conexión no hay sorteo: nunca se inventa uno.
     */
    suspend fun startEvaluation(org: String, courseId: String, id: String, type: AttemptType): TrainingResult<DrawnEvaluation> =
        TrainingResult.Failure(TrainingFailure.UNAVAILABLE)
}
