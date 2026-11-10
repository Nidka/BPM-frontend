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
) {
    val finished: Boolean get() = index >= questions.size
    val current: TrainingQuestion? get() = questions.getOrNull(index)
}

/**
 * Modo evaluación. [pending] es el intento ya construido: mientras exista, el cuerpo no cambia
 * y un reintento reenvía exactamente los mismos identificadores.
 */
data class EvaluationState(
    val type: AttemptType,
    val startedAt: String,
    val answers: Map<String, Int> = emptyMap(),
    val pending: AttemptSubmission? = null,
    val submitting: Boolean = false,
    val failure: TrainingFailure? = null,
    val outcome: AttemptOutcome? = null,
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
) {
    val lesson: TrainingLesson? get() = course?.lessons?.firstOrNull { it.id == lessonId }
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
    TrainingFailure.UNAVAILABLE, TrainingFailure.INVALID_RESPONSE -> "No pudimos confirmar la operación. Inténtalo de nuevo."
}
