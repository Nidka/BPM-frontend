package pe.bpm.features.training.presentation

import pe.bpm.features.training.domain.*

enum class TrainingPane { OVERVIEW, LESSON, STUDY, EVALUATION }

/** Estado de reproducción de la lección actual; la URL firmada solo vive aquí, en memoria. */
sealed interface VideoState {
    data object Idle : VideoState
    data object Loading : VideoState
    data class Ready(val ticket: VideoTicket) : VideoState
    data class Failed(val reason: TrainingFailure) : VideoState
}

/** Modo estudio: sin calificación, sin intento y sin puntaje en ningún campo. */
data class StudyState(
    val questions: List<TrainingQuestion>,
    val lessonId: String? = null,
    val index: Int = 0,
    val selected: Int? = null,
    val feedback: StudyFeedback? = null,
    val checking: Boolean = false,
    val failure: TrainingFailure? = null,
    /** Juego del personal: la pregunta fallada se añade una vez al final para repasarla. */
    val repeatMissed: Boolean = false,
    /** Preguntas de la práctica antes de añadir repeticiones; el porcentaje de aciertos se calcula sobre ellas. */
    val originalCount: Int = questions.size,
    /** Preguntas acertadas al primer intento (las repeticiones no suman). */
    val firstTryCorrect: Int = 0,
    /** Preguntas ya añadidas al final para repasar (cada una se repite una sola vez). */
    val repeated: Set<String> = emptySet(),
    /**
     * Cursos con banco oficial: orden en que se muestran las alternativas de cada pregunta (posición mostrada → índice
     * original). Se baraja en el cliente para no memorizar posiciones; al servidor siempre viaja el índice original.
     */
    val shuffles: Map<String, List<Int>> = emptyMap(),
    /** Repaso espaciado: la sesión recorre las preguntas pendientes de repasar, no una lección. */
    val review: Boolean = false,
    /** XP de práctica ganado en esta sesión (primer acierto de cada ítem). */
    val xpEarned: Int = 0,
) {
    val finished: Boolean get() = index >= questions.size
    val current: TrainingQuestion? get() = questions.getOrNull(index)

    /** Alternativas de [question] en el orden en que se muestran. */
    fun shownOptions(question: TrainingQuestion): List<String> =
        shuffles[question.id]?.takeIf { it.size == question.options.size }?.map { question.options[it] } ?: question.options

    /** Índice original de la alternativa mostrada en la posición [shown]. */
    fun originalOption(question: TrainingQuestion, shown: Int): Int = shuffles[question.id]?.getOrNull(shown) ?: shown

    /** Posición mostrada de la alternativa con índice original [original]. */
    fun shownPosition(question: TrainingQuestion, original: Int): Int = shuffles[question.id]?.indexOf(original)?.takeIf { it >= 0 } ?: original

    /** Porcentaje de aciertos al primer intento (0..100); nulo si la práctica no tiene preguntas. */
    val accuracyPercent: Int? get() = if (originalCount <= 0) null else (firstTryCorrect * 100) / originalCount
}

/**
 * Modo evaluación. [pending] es el intento ya construido: mientras exista, el cuerpo no cambia
 * y un reintento reenvía exactamente los mismos identificadores.
 */
data class EvaluationState(
    val type: AttemptType,
    val startedAt: String,
    /** Solo para [AttemptType.MODULE]: lección cuyas preguntas se evalúan. */
    val lessonId: String? = null,
    val answers: Map<String, Int> = emptyMap(),
    val pending: AttemptSubmission? = null,
    val submitting: Boolean = false,
    val failure: TrainingFailure? = null,
    val outcome: AttemptOutcome? = null,
    /** El intento quedó en el dispositivo: «Evaluación enviada: se calificará al conectarse». */
    val queued: Boolean = false,
    /** Pregunta abierta (índice desde 0): la evaluación se muestra una pregunta por pantalla. */
    val index: Int = 0,
    /** Pantalla de revisión previa al envío (todas las preguntas con su respuesta). */
    val reviewing: Boolean = false,
    /** Curso con banco oficial: evaluación sorteada por el servidor (alternativas ya barajadas); nula mientras se pide. */
    val drawn: DrawnEvaluation? = null,
    /** Se está pidiendo el sorteo al servidor. */
    val starting: Boolean = false,
    /** Evaluación del banco oficial: la evaluación no puede empezar sin el sorteo. */
    val bank: Boolean = false,
)

data class TrainingState(
    val userId: String? = null,
    val organizationId: String? = null,
    val courseId: String? = null,
    /** Falso para SUPER_ADMIN sin membresía: el servidor responde 403 al guardar avance o enviar intentos. */
    val canPersist: Boolean = true,
    val loading: Boolean = false,
    val failure: TrainingFailure? = null,
    val course: TrainingCourse? = null,
    val screen: TrainingPane = TrainingPane.OVERVIEW,
    val lessonId: String? = null,
    val video: VideoState = VideoState.Idle,
    val progressNotSaved: Boolean = false,
    val study: StudyState? = null,
    val evaluation: EvaluationState? = null,
    val notice: String? = null,
    /** Descargas de video del dispositivo (espacio usado, límite y estado por video). */
    val downloads: DownloadsState = DownloadsState(),
    /** Evaluaciones de este curso enviadas desde el dispositivo, calificadas o por calificar. */
    val attempts: List<AttemptRecord> = emptyList(),
) {
    val lesson: TrainingLesson? get() = course?.lessons?.firstOrNull { it.id == lessonId }

    /** Preguntas de la evaluación en curso: las de la lección (módulo) o todas las del curso. */
    val evaluationQuestions: List<TrainingQuestion>
        get() {
            val c = course ?: return emptyList()
            evaluation?.drawn?.let { return it.questions }
            if (evaluation?.bank == true) return emptyList()
            val lesson = evaluation?.lessonId
            return if (lesson == null) c.questions else c.questions.filter { it.lessonId == lesson }
        }
}

fun trainingFailureMessage(failure: TrainingFailure): String = when (failure) {
    TrainingFailure.CONNECTION -> "No pudimos conectar con BPM. Revisa tu conexión e inténtalo de nuevo."
    TrainingFailure.SESSION -> "Tu sesión venció. Inicia sesión nuevamente."
    TrainingFailure.DENIED -> "Tu cuenta no tiene permiso para esta operación."
    TrainingFailure.NOT_FOUND -> "El curso o la lección ya no está disponible."
    TrainingFailure.VERSION_OUTDATED -> "El curso se actualizó. Cargamos la versión vigente; vuelve a responder."
    TrainingFailure.CONFLICT -> "El contenido cambió. Actualiza antes de continuar."
    TrainingFailure.DRAFT_CONFLICT -> "El borrador cambió en otro dispositivo. Recárgalo para no perder cambios ajenos."
    TrainingFailure.CODE_TAKEN -> "Ya existe un curso con ese código."
    TrainingFailure.ANSWERS_INVALID -> "Responde todas las preguntas una sola vez."
    TrainingFailure.DRAFT_INCOMPLETE -> "El curso necesita una lección, una pregunta y todos sus videos listos para publicarse."
    TrainingFailure.INVALID -> "Revisa los datos e inténtalo de nuevo."
    TrainingFailure.CONTENT_INVALID -> "El video no se pudo validar. Elige un MP4 o WebM válido."
    TrainingFailure.TOO_LARGE -> "El archivo supera el tamaño permitido."
    TrainingFailure.RATE_LIMITED -> "Espera un momento antes de reintentar."
    TrainingFailure.ASSIGNMENT_EXISTS -> "Ya hay una asignación activa de este curso para ese destinatario."
    TrainingFailure.PATH_CONFLICT -> "La ruta cambió en otro dispositivo. Recárgala para no perder cambios ajenos."
    TrainingFailure.PATH_ARCHIVED -> "La ruta está archivada y ya no admite cambios."
    TrainingFailure.COURSE_NOT_PUBLISHED -> "El curso debe estar publicado para esta operación."
    TrainingFailure.EVALUATION_NOT_STARTED -> "Esta evaluación ya no está activa. Sal e inicia una nueva."
    TrainingFailure.NO_QUESTION_BANK -> "Este curso no usa el banco oficial de preguntas."
    TrainingFailure.QUEUED -> "Guardado en este dispositivo. Se enviará al recuperar la conexión."
    TrainingFailure.UNAVAILABLE, TrainingFailure.INVALID_RESPONSE -> "No pudimos confirmar la operación. Inténtalo de nuevo."
}
