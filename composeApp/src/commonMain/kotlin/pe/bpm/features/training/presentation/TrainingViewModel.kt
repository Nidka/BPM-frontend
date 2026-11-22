package pe.bpm.features.training.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.training.domain.*

/**
 * Aprender: detalle del curso, visor de lección, modo estudio y modo evaluación.
 *
 * Cada operación asíncrona captura la "generación" vigente; un cambio de usuario, empresa o
 * curso la incrementa y los resultados tardíos se descartan.
 */
class TrainingViewModel(
    private val loadCourse: LoadTrainingCourse,
    private val requestVideo: RequestLessonVideo,
    private val saveProgress: SaveLessonProgress,
    private val checkAnswer: CheckStudyAnswer,
    private val submitAttempt: SubmitTrainingAttempt,
    private val newId: () -> String,
    private val now: () -> String,
    private val clock: () -> Instant = { Clock.System.now() },
    /** Videos descargados: si el archivo local existe, el reproductor lo usa en lugar de pedir una URL firmada. */
    private val downloads: TrainingDownloads = NoTrainingDownloads,
    /** Evaluaciones enviadas sin conexión y su calificación cuando el servidor la devuelve. */
    private val attempts: TrainingAttemptLedger = NoTrainingAttemptLedger,
    /** Sincroniza el catálogo offline en segundo plano al abrir un curso (ETag/304). */
    private val syncCatalog: (suspend (String) -> Unit)? = null,
) : ViewModel() {
    private val mutable = MutableStateFlow(TrainingState())
    val state: StateFlow<TrainingState> = mutable.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null
    private var videoJob: Job? = null
    private var progressJob: Job? = null
    private var studyJob: Job? = null
    private var submitJob: Job? = null
    private var attemptsJob: Job? = null
    private var downloadsJob: Job? = null

    private fun cancelAll() {
        generation++
        listOf(loadJob, videoJob, progressJob, studyJob, submitJob, attemptsJob).forEach { it?.cancel() }
    }

    fun reset() {
        cancelAll()
        mutable.value = TrainingState()
        // Los videos descargados quedan en el dispositivo, pero dejan de asociarse a esta cuenta.
        viewModelScope.launch { downloads.unbind() }
    }

    /** [canPersist] es falso para SUPER_ADMIN sin membresía: puede leer y practicar, pero no guardar avance ni evaluar. */
    fun open(userId: String, organizationId: String, courseId: String, canPersist: Boolean = true) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.courseId == courseId && s.canPersist == canPersist) return
        cancelAll()
        mutable.value = TrainingState(userId, organizationId, courseId, canPersist = canPersist, downloads = downloads.state.value)
        load()
        val expected = generation
        attemptsJob = viewModelScope.launch {
            attempts.observe(courseId).collect { list -> if (expected == generation) mutable.value = mutable.value.copy(attempts = list) }
        }
        viewModelScope.launch {
            // Primero con el catálogo ya guardado (sirve sin red) y de nuevo si la sincronización lo actualizó.
            downloads.bind(organizationId); downloads.refresh()
            syncCatalog?.let { sync ->
                try { sync(organizationId) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* la copia local sigue vigente */ }
                downloads.bind(organizationId)
            }
        }
        if (downloadsJob == null) downloadsJob = viewModelScope.launch { downloads.state.collect { d -> mutable.value = mutable.value.copy(downloads = d) } }
    }

    fun refresh() = load()

    private fun load(notice: String? = null, keepEvaluation: Boolean = false) {
        val s = mutable.value
        val org = s.organizationId ?: return
        val courseId = s.courseId ?: return
        loadJob?.cancel()
        val expected = ++generation
        mutable.value = s.copy(loading = true, failure = null)
        loadJob = viewModelScope.launch {
            val result = loadCourse(org, courseId)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> {
                    val current = mutable.value
                    val course = result.value
                    val lesson = current.lessonId?.takeIf { id -> course.lessons.any { it.id == id } }
                    val evaluation = current.evaluation?.takeIf { keepEvaluation }
                        ?.let { EvaluationState(it.type, now(), it.lessonId) }
                    // Las preguntas pudieron cambiar: la práctica se reinicia y la evaluación solo continúa si se pidió.
                    val screen = when {
                        lesson == null && current.screen == TrainingPane.LESSON -> TrainingPane.OVERVIEW
                        current.screen == TrainingPane.STUDY -> TrainingPane.OVERVIEW
                        current.screen == TrainingPane.EVALUATION && evaluation == null -> TrainingPane.OVERVIEW
                        else -> current.screen
                    }
                    current.copy(loading = false, failure = null, course = course, lessonId = lesson, screen = screen,
                        study = null, evaluation = evaluation, notice = notice ?: current.notice)
                }
                is TrainingResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    // ---- Lecciones ---------------------------------------------------------------------------

    /** Retoma desde la última lección vista (RF-11) o desde la primera. */
    fun resume() { mutable.value.course?.resumeLesson()?.let { selectLesson(it.id) } }

    /**
     * Abre [lessonId]. Si [completedLessonId] no es nulo se envía junto con la lección abierta: la persona terminó
     * esa lección (p. ej. al pasar a la siguiente) y el servidor la cuenta como completada.
     */
    fun selectLesson(lessonId: String, completedLessonId: String? = null) {
        val s = mutable.value
        val course = s.course ?: return
        val org = s.organizationId ?: return
        if (course.lessons.none { it.id == lessonId }) return
        videoJob?.cancel()
        mutable.value = s.copy(screen = TrainingPane.LESSON, lessonId = lessonId, video = VideoState.Idle, progressNotSaved = false)
        if (s.canPersist) saveLastLesson(org, course.course.id, lessonId, completedLessonId?.takeIf { id -> course.lessons.any { it.id == id } })
    }

    private fun saveLastLesson(org: String, courseId: String, lessonId: String, completedLessonId: String? = null) {
        progressJob?.cancel()
        val expected = generation
        progressJob = viewModelScope.launch {
            val result = saveProgress(org, courseId, lessonId, completedLessonId)
            if (expected != generation) return@launch
            val current = mutable.value
            mutable.value = when (result) {
                is TrainingResult.Success -> {
                    // Un servidor anterior no devuelve las lecciones terminadas: se refleja la marca localmente.
                    val progress = result.value.let { p -> if (completedLessonId != null && completedLessonId !in p.completedLessonIds) p.withCompletedLesson(completedLessonId) else p }
                    current.copy(course = current.course?.copy(progress = progress), progressNotSaved = false)
                }
                // Leer la lección no debe bloquearse porque el avance no se guardó; se avisa sin interrumpir.
                is TrainingResult.Failure -> current.copy(progressNotSaved = true)
            }
        }
    }

    /**
     * Marca [lessonId] como terminada sin cambiar de pantalla (fin de lección del juego del personal: la celebración
     * se muestra mientras el avance se guarda). Sin membresía no guarda nada.
     */
    fun completeLesson(lessonId: String) {
        val s = mutable.value
        val course = s.course ?: return
        val org = s.organizationId ?: return
        if (!s.canPersist || course.lessons.none { it.id == lessonId }) return
        saveLastLesson(org, course.course.id, lessonId, completedLessonId = lessonId)
    }

    /** «Siguiente lección»: marca la actual como terminada y abre la siguiente en la misma petición de avance. */
    fun nextLesson() = moveLesson(1, completeCurrent = true)
    fun previousLesson() = moveLesson(-1)

    /**
     * «Terminar lecciones»: marca la lección actual como terminada y vuelve al temario. Devuelve `true` si resolvió
     * dentro del curso (igual que [back]).
     */
    fun finishLessons(): Boolean {
        val s = mutable.value
        val course = s.course
        val org = s.organizationId
        val lessonId = s.lessonId
        if (s.screen == TrainingPane.LESSON && s.canPersist && course != null && org != null && lessonId != null && course.lessons.any { it.id == lessonId }) {
            saveLastLesson(org, course.course.id, lessonId, completedLessonId = lessonId)
        }
        return back()
    }

    private fun moveLesson(step: Int, completeCurrent: Boolean = false) {
        val s = mutable.value
        val lessons = s.course?.lessons ?: return
        val index = lessons.indexOfFirst { it.id == s.lessonId }
        lessons.getOrNull(index + step)?.let { selectLesson(it.id, if (completeCurrent) s.lessonId else null) }
    }

    // ---- Video -------------------------------------------------------------------------------

    /** Obtiene una URL nueva justo antes de reproducir; nunca reutiliza una anterior. */
    fun loadVideo() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val course = s.course ?: return
        val lesson = s.lesson?.takeIf { it.video != null } ?: return
        videoJob?.cancel()
        val expected = generation
        mutable.value = s.copy(video = VideoState.Loading)
        videoJob = viewModelScope.launch {
            // Un video descargado y verificado se reproduce desde el archivo local, sin red.
            lesson.video?.let { video -> downloads.localVideo(video) }?.let { local ->
                if (expected == generation && mutable.value.lessonId == lesson.id) mutable.value = mutable.value.copy(video = VideoState.Ready(local))
                return@launch
            }
            val result = requestVideo(org, course.course.id, lesson.id)
            if (expected != generation || mutable.value.lessonId != lesson.id) return@launch
            mutable.value = mutable.value.copy(video = when (result) {
                is TrainingResult.Success -> VideoState.Ready(result.value)
                is TrainingResult.Failure -> VideoState.Failed(result.reason)
            })
        }
    }

    /** El reproductor falló: si la URL venció se pide otra; en otro caso se ofrece reintentar. */
    fun onVideoError() {
        val video = mutable.value.video as? VideoState.Ready ?: return
        if (video.ticket.local) {
            // El archivo guardado no se pudo reproducir: se elimina y se vuelve a pedir el video en línea.
            val mediaId = mutable.value.lesson?.video?.mediaId
            viewModelScope.launch { mediaId?.let { downloads.delete(it) }; loadVideo() }
            return
        }
        val expiry = runCatching { Instant.parse(video.ticket.expiresAt) }.getOrNull()
        if (expiry == null || clock() >= expiry) loadVideo()
        else mutable.value = mutable.value.copy(video = VideoState.Failed(TrainingFailure.UNAVAILABLE))
    }

    // ---- Descargas para ver sin conexión -------------------------------------------------------

    /** Descarga los videos privados del curso; los de YouTube siguen requiriendo conexión. */
    fun downloadCourse() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val course = s.course ?: return
        downloads.download(org, course)
    }

    fun cancelDownload() = downloads.cancel()

    fun deleteCourseDownloads() {
        val course = mutable.value.course ?: return
        viewModelScope.launch { downloads.deleteCourse(course) }
    }

    fun deleteAllDownloads() { viewModelScope.launch { downloads.deleteAll() } }

    fun setDownloadLimit(bytes: Long) { viewModelScope.launch { downloads.setLimit(bytes) } }

    fun closeVideo() { videoJob?.cancel(); mutable.value = mutable.value.copy(video = VideoState.Idle) }

    // ---- Modo estudio ------------------------------------------------------------------------

    /**
     * Practica las preguntas de la lección indicada o, sin ella, todas las del curso. Con [repeatMissed] (juego del
     * personal) cada pregunta fallada se añade una vez al final para repasarla.
     */
    fun startStudy(lessonId: String? = null, repeatMissed: Boolean = false) {
        val course = mutable.value.course ?: return
        val scoped = lessonId?.let { id -> course.questions.filter { it.lessonId == id } }.orEmpty()
        val questions = scoped.ifEmpty { course.questions }
        if (questions.isEmpty()) {
            mutable.value = mutable.value.copy(notice = "Este curso todavía no tiene preguntas para practicar.")
            return
        }
        studyJob?.cancel()
        mutable.value = mutable.value.copy(screen = TrainingPane.STUDY, study = StudyState(questions, lessonId.takeIf { scoped.isNotEmpty() }, repeatMissed = repeatMissed), video = VideoState.Idle)
    }

    /** Responde y muestra de inmediato si fue correcta y por qué; no suma puntos ni crea intentos. */
    fun answerStudy(option: Int) {
        val s = mutable.value
        val org = s.organizationId ?: return
        val course = s.course ?: return
        val study = s.study ?: return
        val question = study.current ?: return
        if (study.checking || study.feedback != null || option !in question.options.indices) return
        mutable.value = s.copy(study = study.copy(selected = option, checking = true, failure = null))
        val expected = generation
        studyJob?.cancel()
        studyJob = viewModelScope.launch {
            val result = checkAnswer(org, course, question.id, option)
            if (expected != generation) return@launch
            val current = mutable.value
            val active = current.study?.takeIf { it.current?.id == question.id } ?: return@launch
            mutable.value = current.copy(study = when (result) {
                is TrainingResult.Success -> {
                    val feedback = result.value
                    val repeat = !feedback.correct && active.repeatMissed && question.id !in active.repeated
                    active.copy(checking = false, feedback = feedback,
                        firstTryCorrect = active.firstTryCorrect + if (feedback.correct && active.index < active.originalCount) 1 else 0,
                        questions = if (repeat) active.questions + question else active.questions,
                        repeated = if (repeat) active.repeated + question.id else active.repeated)
                }
                is TrainingResult.Failure -> active.copy(checking = false, selected = null, failure = result.reason)
            })
        }
    }

    /** Sin conexión y sin retroalimentación guardada: deja pasar la pregunta para seguir leyendo o practicando. */
    fun skipStudyQuestion() {
        val study = mutable.value.study ?: return
        if (study.checking) return
        mutable.value = mutable.value.copy(study = study.copy(index = study.index + 1, selected = null, feedback = null, failure = null))
    }

    /** Permite volver a intentar la misma pregunta. */
    fun retryStudyQuestion() {
        val study = mutable.value.study ?: return
        if (study.checking) return
        mutable.value = mutable.value.copy(study = study.copy(selected = null, feedback = null, failure = null))
    }

    fun nextStudyQuestion() {
        val study = mutable.value.study ?: return
        if (study.checking || study.feedback == null) return
        mutable.value = mutable.value.copy(study = study.copy(index = study.index + 1, selected = null, feedback = null, failure = null))
    }

    fun restartStudy() { val study = mutable.value.study; startStudy(study?.lessonId, study?.repeatMissed == true) }

    // ---- Modo evaluación ---------------------------------------------------------------------

    /** Evalúa solo las preguntas de una lección; no completa el curso ni emite constancia. */
    fun startModuleEvaluation(lessonId: String) {
        val course = mutable.value.course ?: return
        if (!mutable.value.canPersist || course.lessons.none { it.id == lessonId }) return
        if (course.questions.none { it.lessonId == lessonId }) {
            mutable.value = mutable.value.copy(notice = "Esta lección todavía no tiene preguntas para evaluar.")
            return
        }
        mutable.value = mutable.value.copy(screen = TrainingPane.EVALUATION, evaluation = EvaluationState(AttemptType.MODULE, now(), lessonId), video = VideoState.Idle)
    }

    fun startEvaluation(type: AttemptType) {
        val course = mutable.value.course ?: return
        if (!mutable.value.canPersist || type == AttemptType.MODULE) return
        if (course.questions.isEmpty()) {
            mutable.value = mutable.value.copy(notice = "Este curso todavía no tiene preguntas para evaluar.")
            return
        }
        mutable.value = mutable.value.copy(screen = TrainingPane.EVALUATION, evaluation = EvaluationState(type, now()), video = VideoState.Idle)
    }

    /** Las respuestas se bloquean mientras exista un envío pendiente o ya hay resultado. */
    fun answerEvaluation(questionId: String, option: Int) {
        val s = mutable.value
        val evaluation = s.evaluation ?: return
        val question = s.evaluationQuestions.firstOrNull { it.id == questionId } ?: return
        if (evaluation.pending != null || evaluation.submitting || evaluation.outcome != null || evaluation.queued || option !in question.options.indices) return
        mutable.value = s.copy(evaluation = evaluation.copy(answers = evaluation.answers + (questionId to option), failure = null))
    }

    /** Hay navegación entre preguntas solo mientras se responde: no durante el envío, en cola ni con resultado. */
    private fun navigableEvaluation(): EvaluationState? =
        mutable.value.evaluation?.takeIf { !it.submitting && !it.queued && it.outcome == null }

    /** Abre la pregunta [index] (acotada al rango válido) y sale de la revisión. No cambia las respuestas. */
    fun goToQuestion(index: Int) {
        val evaluation = navigableEvaluation() ?: return
        val last = mutable.value.evaluationQuestions.lastIndex
        if (last < 0) return
        mutable.value = mutable.value.copy(evaluation = evaluation.copy(index = index.coerceIn(0, last), reviewing = false))
    }

    /** Avanza una pregunta; en la última se queda (la pantalla ofrece «Revisar respuestas»). */
    fun nextQuestion() { navigableEvaluation()?.let { goToQuestion(it.index + 1) } }

    fun previousQuestion() { navigableEvaluation()?.let { goToQuestion(it.index - 1) } }

    fun openReview() {
        val evaluation = navigableEvaluation() ?: return
        mutable.value = mutable.value.copy(evaluation = evaluation.copy(reviewing = true))
    }

    fun closeReview() {
        val evaluation = navigableEvaluation() ?: return
        mutable.value = mutable.value.copy(evaluation = evaluation.copy(reviewing = false))
    }

    fun canSubmitEvaluation(): Boolean {
        val s = mutable.value
        val evaluation = s.evaluation ?: return false
        val total = s.evaluationQuestions.size
        return evaluation.outcome == null && !evaluation.queued && !evaluation.submitting && (evaluation.pending != null || total > 0 && evaluation.answers.size == total)
    }

    /** Envía el intento. Si ya existe uno pendiente (fallo de red previo), reenvía el MISMO cuerpo. */
    fun submitEvaluation() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val course = s.course ?: return
        val evaluation = s.evaluation ?: return
        if (evaluation.submitting || evaluation.outcome != null || evaluation.queued) return
        val attempt = evaluation.pending ?: submitAttempt.prepare(course, evaluation.type, evaluation.answers, newId, evaluation.startedAt, now(), evaluation.lessonId)
        if (attempt == null) {
            mutable.value = s.copy(evaluation = evaluation.copy(failure = TrainingFailure.ANSWERS_INVALID))
            return
        }
        mutable.value = s.copy(evaluation = evaluation.copy(pending = attempt, submitting = true, failure = null))
        val expected = generation
        submitJob?.cancel()
        submitJob = viewModelScope.launch {
            val result = submitAttempt(org, course.course.id, attempt)
            if (expected != generation) return@launch
            val current = mutable.value
            val active = current.evaluation?.takeIf { it.pending?.id == attempt.id } ?: return@launch
            when (result) {
                is TrainingResult.Success -> mutable.value = current.copy(
                    course = current.course?.copy(progress = result.value.progress, certificate = result.value.certificate ?: current.course.certificate),
                    evaluation = active.copy(pending = null, submitting = false, outcome = result.value))
                is TrainingResult.Failure -> when {
                    // Sin conexión: el intento quedó guardado y el servidor lo calificará al sincronizar.
                    result.reason == TrainingFailure.QUEUED -> mutable.value = current.copy(evaluation = active.copy(pending = null, submitting = false, queued = true, failure = null))
                    // La versión cambió: se recarga el curso y se reinicia el intento con preguntas vigentes.
                    result.reason == TrainingFailure.VERSION_OUTDATED -> {
                        mutable.value = current.copy(evaluation = active.copy(pending = null, submitting = false, failure = result.reason))
                        load(notice = trainingFailureMessage(TrainingFailure.VERSION_OUTDATED), keepEvaluation = true)
                    }
                    // Fallo incierto: se conserva el intento para reintentarlo sin duplicar.
                    result.reason.retryable -> mutable.value = current.copy(evaluation = active.copy(submitting = false, failure = result.reason))
                    // Rechazo definitivo: el servidor no registró nada; se puede corregir y enviar otro.
                    else -> mutable.value = current.copy(evaluation = active.copy(pending = null, submitting = false, failure = result.reason))
                }
            }
        }
    }

    /** Cierra el resultado y vuelve al detalle del curso. */
    fun closeEvaluation() {
        if (mutable.value.evaluation?.submitting == true) return
        mutable.value = mutable.value.copy(screen = TrainingPane.OVERVIEW, evaluation = null)
    }

    // ---- Navegación interna ------------------------------------------------------------------

    /** Devuelve `true` si el regreso se resolvió dentro del curso; `false` para salir de la feature. */
    fun back(): Boolean {
        val s = mutable.value
        return when (s.screen) {
            TrainingPane.OVERVIEW -> false
            TrainingPane.EVALUATION -> { if (s.evaluation?.submitting != true) closeEvaluation(); true }
            else -> { videoJob?.cancel(); mutable.value = s.copy(screen = TrainingPane.OVERVIEW, video = VideoState.Idle, study = null); true }
        }
    }

    override fun onCleared() { cancelAll() }
}
