package pe.bpm.features.research.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.research.domain.*

enum class SurveyPane { OVERVIEW, CONSENT, QUESTIONS, DONE }

/**
 * Estado de la encuesta del participante. [pending] es la entrega ya construida: mientras exista,
 * las respuestas no cambian y un reintento reenvía exactamente los mismos identificadores.
 * Nunca contiene puntajes ni respuestas correctas.
 */
data class SurveyState(
    val userId: String? = null,
    val organizationId: String? = null,
    val loading: Boolean = false,
    val failure: ResearchFailure? = null,
    val participation: Participation? = null,
    val screen: SurveyPane = SurveyPane.OVERVIEW,
    val moment: SurveyMoment? = null,
    val index: Int = 0,
    val answers: Map<String, Int> = emptyMap(),
    val pending: SurveySubmission? = null,
    val submitting: Boolean = false,
    val consentBusy: Boolean = false,
    val confirmingRevoke: Boolean = false,
    val showMissing: Boolean = false,
    val completedMoment: SurveyMoment? = null,
    /** La entrega se guardó sin conexión y el servidor la confirmará al sincronizar. */
    val completedQueued: Boolean = false,
    val notice: String? = null,
) {
    val survey: Survey? get() = participation?.survey
    val current: SurveyQuestion? get() = survey?.questions?.getOrNull(index)
    val answeredCount: Int get() = survey?.let { SurveyRules.answeredCount(it, answers) } ?: 0
    val complete: Boolean get() = survey?.let { SurveyRules.isComplete(it, answers) } == true
}

/**
 * Encuesta CAP del participante: consentimiento, preguntas una por pantalla y envío idempotente.
 * Cada operación captura la "generación" vigente; un cambio de usuario o empresa la incrementa y
 * los resultados tardíos se descartan.
 */
class SurveyViewModel(
    private val loadParticipation: LoadParticipation,
    private val recordConsent: RecordConsent,
    private val submitSurvey: SubmitSurvey,
    private val newId: () -> String,
    private val now: () -> String,
    private val localFirst: pe.bpm.core.offline.LocalFirstReader = pe.bpm.core.offline.LocalFirstReader.Direct,
) : ViewModel() {
    private val mutable = MutableStateFlow(SurveyState())
    val state: StateFlow<SurveyState> = mutable.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    private fun cancelAll() {
        generation++
        loadJob?.cancel(); actionJob?.cancel()
    }

    fun reset() {
        cancelAll()
        mutable.value = SurveyState()
    }

    fun open(userId: String, organizationId: String) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId) return
        cancelAll()
        mutable.value = SurveyState(userId, organizationId)
        load()
    }

    fun refresh() = load()

    private fun load() {
        val org = mutable.value.organizationId ?: return
        loadJob?.cancel()
        val expected = ++generation
        mutable.value = mutable.value.copy(loading = true, failure = null)
        loadJob = viewModelScope.launch {
            // Lectura local primero: el consentimiento y el instrumento guardados se muestran de inmediato.
            val result = localFirst.read(
                isUsable = { it is ResearchResult.Success },
                onLocal = { local ->
                    if (expected == generation && mutable.value.participation == null)
                        mutable.value = withParticipation(mutable.value.copy(loading = false, failure = null), (local as ResearchResult.Success).value)
                },
                load = { loadParticipation(org) },
            )
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> withParticipation(mutable.value.copy(loading = false, failure = null), result.value)
                is ResearchResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    /** Aplica una participación nueva y descarta respuestas que ya no corresponden a la encuesta vigente. */
    private fun withParticipation(base: SurveyState, participation: Participation): SurveyState {
        val survey = participation.survey
        val keep = survey != null && participation.consentValid && base.moment != null && base.moment !in participation.completedMoments() &&
            base.pending?.instrumentVersionId?.equals(survey.instrumentVersionId, ignoreCase = true) != false &&
            base.answers.keys.all { id -> survey.questions.any { it.id == id } }
        val inQuestions = base.screen == SurveyPane.QUESTIONS
        return if (keep) base.copy(participation = participation)
        else base.copy(participation = participation, moment = null, index = 0, answers = emptyMap(), pending = null, submitting = false, showMissing = false,
            screen = if (inQuestions) SurveyPane.OVERVIEW else base.screen)
    }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    // ---- Consentimiento ----------------------------------------------------------------------

    fun showConsent() {
        val s = mutable.value
        if (s.participation?.consentDocument != null && !s.submitting) mutable.value = s.copy(screen = SurveyPane.CONSENT, confirmingRevoke = false, failure = null)
    }

    /** Acepta el documento vigente. Repetir la acción es seguro: el servidor no crea otro registro. */
    fun acceptConsent() {
        val s = mutable.value
        val document = s.participation?.consentDocument ?: return
        changeConsent(s, document.version, true, "Gracias por aceptar. Ya puedes responder la encuesta.")
    }

    fun askRevoke() { if (!mutable.value.consentBusy) mutable.value = mutable.value.copy(confirmingRevoke = true) }

    fun cancelRevoke() { mutable.value = mutable.value.copy(confirmingRevoke = false) }

    /** Retira el consentimiento: se descartan respuestas en memoria y el participante queda fuera del estudio. */
    fun revokeConsent() {
        val s = mutable.value
        val version = s.participation?.consent?.documentVersion ?: s.participation?.consentDocument?.version ?: return
        changeConsent(s, version, false, "Retiraste tu consentimiento. Tus respuestas dejan de usarse en el estudio.")
    }

    private fun changeConsent(s: SurveyState, version: String, accepted: Boolean, message: String) {
        val org = s.organizationId ?: return
        if (s.consentBusy || s.submitting) return
        actionJob?.cancel()
        val expected = ++generation
        mutable.value = s.copy(consentBusy = true, failure = null, confirmingRevoke = false)
        actionJob = viewModelScope.launch {
            val result = recordConsent(org, version, accepted)
            if (expected != generation) return@launch
            val current = mutable.value
            mutable.value = when (result) {
                is ResearchResult.Success -> withParticipation(current.copy(consentBusy = false), result.value)
                    .let { next -> if (accepted) next else next.copy(moment = null, answers = emptyMap(), pending = null, index = 0) }
                    .copy(screen = SurveyPane.OVERVIEW, notice = message)
                is ResearchResult.Failure -> current.copy(consentBusy = false, failure = result.reason)
            }
            // Un documento nuevo exige volver a leer: se recarga para mostrar la versión vigente.
            if (result is ResearchResult.Failure && result.reason in setOf(ResearchFailure.CONSENT_OUTDATED, ResearchFailure.CONSENT_UNAVAILABLE)) load()
        }
    }

    // ---- Preguntas ---------------------------------------------------------------------------

    /** Inicia (o retoma) un momento de la encuesta; solo con consentimiento vigente y si no está completado. */
    fun start(moment: SurveyMoment) {
        val s = mutable.value
        val participation = s.participation ?: return
        if (participation.survey == null || !participation.consentValid || moment in participation.completedMoments() || s.submitting) return
        mutable.value = if (s.moment == moment && s.answers.isNotEmpty())
            s.copy(screen = SurveyPane.QUESTIONS, failure = null, notice = null)
        else s.copy(screen = SurveyPane.QUESTIONS, moment = moment, index = 0, answers = emptyMap(), pending = null, showMissing = false, failure = null, notice = null)
    }

    /** Registra la respuesta; se ignora si es inválida o si el envío ya está en curso o pendiente. */
    fun answer(questionId: String, value: Int) {
        val s = mutable.value
        val question = s.survey?.questions?.firstOrNull { it.id == questionId } ?: return
        if (s.pending != null || s.submitting || !SurveyRules.isValidValue(question, value)) return
        mutable.value = s.copy(answers = s.answers + (questionId to value), failure = null)
    }

    fun next() {
        val s = mutable.value
        val last = (s.survey?.questions?.size ?: return) - 1
        if (s.index < last) mutable.value = s.copy(index = s.index + 1)
    }

    fun previous() {
        val s = mutable.value
        if (s.index > 0) mutable.value = s.copy(index = s.index - 1)
    }

    fun goToFirstMissing() {
        val s = mutable.value
        val survey = s.survey ?: return
        val index = survey.questions.indexOfFirst { s.answers[it.id] == null }
        if (index >= 0) mutable.value = s.copy(index = index, showMissing = false)
    }

    /**
     * Envía la encuesta. La entrega se construye una sola vez: si el envío falla por red, el
     * reintento reutiliza `id` y `clientOperationId`, de modo que un envío ya recibido no se duplica.
     */
    fun submit() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val participation = s.participation ?: return
        val moment = s.moment ?: return
        if (s.submitting) return
        val submission = s.pending ?: submitSurvey.prepare(participation, moment, s.answers, newId, now())
        if (submission == null) {
            mutable.value = s.copy(showMissing = true)
            return
        }
        actionJob?.cancel()
        val expected = ++generation
        mutable.value = s.copy(submitting = true, pending = submission, failure = null, showMissing = false)
        actionJob = viewModelScope.launch {
            val result = submitSurvey(org, submission)
            if (expected != generation) return@launch
            val current = mutable.value
            when (result) {
                is ResearchResult.Success -> {
                    val record = SubmissionRecord(result.value.moment, result.value.instrumentVersionId, result.value.submittedAt)
                    val updated = current.participation?.let { it.copy(submissions = it.submissions + record) }
                    mutable.value = current.copy(submitting = false, pending = null, answers = emptyMap(), moment = null, index = 0,
                        participation = updated, screen = SurveyPane.DONE, completedMoment = result.value.moment, completedQueued = false, failure = null)
                }
                // Sin conexión: la entrega queda en la Outbox con el mismo UUID y se envía al sincronizar.
                is ResearchResult.Failure -> if (result.reason == ResearchFailure.QUEUED) {
                    mutable.value = current.copy(submitting = false, pending = null, answers = emptyMap(), moment = null, index = 0,
                        screen = SurveyPane.DONE, completedMoment = submission.moment, completedQueued = true, failure = null)
                } else onSubmitFailure(current, result.reason)
            }
        }
    }

    private fun onSubmitFailure(current: SurveyState, reason: ResearchFailure) {
        // Fallos de red o del servicio conservan la entrega para reintentar sin duplicar.
        if (reason.retryable || reason == ResearchFailure.SESSION || reason == ResearchFailure.DENIED) {
            mutable.value = current.copy(submitting = false, failure = reason)
            return
        }
        val note = when (reason) {
            ResearchFailure.ALREADY_SUBMITTED -> "Ya habíamos registrado esta encuesta. Gracias por participar."
            ResearchFailure.CONSENT_REQUIRED, ResearchFailure.CONSENT_OUTDATED -> "Necesitamos tu consentimiento vigente antes de recibir tus respuestas."
            ResearchFailure.VERSION_OUTDATED, ResearchFailure.SURVEY_UNAVAILABLE, ResearchFailure.ANSWERS_INVALID -> "La encuesta cambió. Cargamos la versión vigente; vuelve a responder."
            else -> null
        }
        if (note == null) {
            // Cualquier otro rechazo definitivo: se libera la entrega para poder corregir y reenviar con identificadores nuevos.
            mutable.value = current.copy(submitting = false, pending = null, failure = reason)
            return
        }
        mutable.value = current.copy(submitting = false, pending = null, answers = emptyMap(), moment = null, index = 0, screen = SurveyPane.OVERVIEW, notice = note, failure = null)
        load()
    }

    fun dismissDone() { mutable.value = mutable.value.copy(screen = SurveyPane.OVERVIEW, completedMoment = null, completedQueued = false) }

    /** Devuelve `true` si el regreso se resolvió dentro de la encuesta; `false` para salir de la sección. */
    fun back(): Boolean {
        val s = mutable.value
        return when (s.screen) {
            SurveyPane.OVERVIEW -> false
            SurveyPane.QUESTIONS -> { if (!s.submitting) mutable.value = s.copy(screen = SurveyPane.OVERVIEW, failure = null); true }
            else -> { mutable.value = s.copy(screen = SurveyPane.OVERVIEW, confirmingRevoke = false, completedMoment = null, failure = null); true }
        }
    }

    override fun onCleared() { cancelAll() }
}

fun researchFailureMessage(failure: ResearchFailure): String = when (failure) {
    ResearchFailure.CONNECTION -> "No pudimos conectar con BPM. Revisa tu conexión e inténtalo de nuevo."
    ResearchFailure.SESSION -> "Tu sesión venció. Inicia sesión nuevamente."
    ResearchFailure.DENIED -> "Tu cuenta no tiene permiso para esta operación."
    ResearchFailure.RESEARCH_DENIED -> "Tu cuenta no tiene acceso a la sección de investigación."
    ResearchFailure.NOT_FOUND -> "El recurso ya no está disponible."
    ResearchFailure.CONSENT_REQUIRED -> "Necesitas aceptar el consentimiento informado vigente para responder."
    ResearchFailure.CONSENT_UNAVAILABLE -> "Todavía no hay un consentimiento publicado. Inténtalo más tarde."
    ResearchFailure.CONSENT_OUTDATED -> "El consentimiento se actualizó. Léelo de nuevo antes de aceptar."
    ResearchFailure.SURVEY_UNAVAILABLE -> "No hay una encuesta publicada por ahora."
    ResearchFailure.VERSION_OUTDATED -> "La encuesta se actualizó. Cargamos la versión vigente."
    ResearchFailure.ALREADY_SUBMITTED -> "Ya registramos esa encuesta."
    ResearchFailure.CONFLICT -> "La información cambió. Actualiza antes de continuar."
    ResearchFailure.ANSWERS_INVALID -> "Responde todas las preguntas una sola vez."
    ResearchFailure.INVALID -> "Revisa los datos e inténtalo de nuevo."
    ResearchFailure.INSTRUMENT_INCOMPLETE -> "El cuestionario está incompleto: revisa las respuestas correctas y la cantidad de preguntas."
    ResearchFailure.DRAFT_CONFLICT -> "El borrador cambió en otro dispositivo. Recárgalo para no perder cambios ajenos."
    ResearchFailure.DRAFT_EXISTS -> "Ya existe un borrador de este cuestionario."
    ResearchFailure.DRAFT_MISSING -> "Este cuestionario no tiene un borrador abierto."
    ResearchFailure.CODE_TAKEN -> "Ya existe un cuestionario con ese código."
    ResearchFailure.VERSION_TAKEN -> "Ya existe un consentimiento con esa versión."
    ResearchFailure.CAP_EXISTS -> "Ya existe un cuestionario CAP. Crea una nueva versión del existente."
    ResearchFailure.USER_NOT_FOUND -> "No hay una cuenta BPM con ese correo."
    ResearchFailure.EXPORT_TOO_LARGE -> "Hay demasiadas respuestas para exportar de una vez. Filtra por empresa."
    ResearchFailure.TOO_LARGE -> "El archivo supera el tamaño permitido."
    ResearchFailure.RATE_LIMITED -> "Espera un momento antes de reintentar."
    ResearchFailure.QUEUED -> "Guardado en este dispositivo. Se enviará al recuperar la conexión."
    ResearchFailure.UNAVAILABLE, ResearchFailure.INVALID_RESPONSE -> "No pudimos confirmar la operación. Inténtalo de nuevo."
}
