package pe.bpm.features.research.domain

import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest

// ---- Resultado y motivos públicos -------------------------------------------------------------

/** Motivos públicos y sanitizados; el mensaje del servidor nunca llega a la interfaz. */
enum class ResearchFailure {
    CONNECTION, SESSION, DENIED, RESEARCH_DENIED, NOT_FOUND, CONSENT_REQUIRED, CONSENT_UNAVAILABLE, CONSENT_OUTDATED,
    SURVEY_UNAVAILABLE, VERSION_OUTDATED, ALREADY_SUBMITTED, CONFLICT, ANSWERS_INVALID, INVALID, INSTRUMENT_INCOMPLETE,
    DRAFT_CONFLICT, DRAFT_EXISTS, DRAFT_MISSING, CODE_TAKEN, VERSION_TAKEN, CAP_EXISTS, USER_NOT_FOUND, EXPORT_TOO_LARGE,
    TOO_LARGE, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE,
    /** La entrega quedó guardada en este dispositivo y se enviará al recuperar la conexión (éxito local pendiente). */
    QUEUED;

    /** Un reintento con el mismo cuerpo puede resolver el fallo sin riesgo de duplicar. */
    val retryable: Boolean
        get() = this in setOf(CONNECTION, UNAVAILABLE, RATE_LIMITED, INVALID_RESPONSE)
}

sealed interface ResearchResult<out T> {
    data class Success<T>(val value: T) : ResearchResult<T>
    data class Failure(val reason: ResearchFailure) : ResearchResult<Nothing>
}

// ---- Participante -----------------------------------------------------------------------------

enum class ParticipantStatus { ACTIVE, WITHDRAWN }

data class Participant(val code: String, val status: ParticipantStatus)

data class ConsentDocument(val version: String, val title: String, val text: String, val responsibleInvestigator: String, val publishedAt: String)

data class ConsentRecord(val documentVersion: String, val accepted: Boolean, val recordedAt: String, val responsibleInvestigator: String)

enum class QuestionType { MULTIPLE_CHOICE, LIKERT, FREQUENCY }

enum class Dimension { KNOWLEDGE, ATTITUDE, PRACTICE }

/** Pregunta de la encuesta: por contrato nunca incluye la respuesta correcta ni puntajes. */
data class SurveyQuestion(val id: String, val order: Int, val dimension: Dimension, val type: QuestionType, val text: String, val options: List<String>)

data class Survey(val instrumentCode: String, val name: String, val instrumentVersionId: String, val versionNumber: Int, val questions: List<SurveyQuestion>)

enum class SurveyMoment { PRETEST, POSTTEST }

data class SubmissionRecord(val moment: SurveyMoment, val instrumentVersionId: String, val submittedAt: String)

data class Participation(
    val participant: Participant?,
    val consentDocument: ConsentDocument?,
    val consent: ConsentRecord?,
    val consentValid: Boolean,
    val survey: Survey?,
    val submissions: List<SubmissionRecord>,
) {
    /** Momentos ya entregados para la versión vigente de la encuesta. */
    fun completedMoments(): Set<SurveyMoment> {
        val version = survey?.instrumentVersionId ?: return emptySet()
        return submissions.filter { it.instrumentVersionId.equals(version, ignoreCase = true) }.map { it.moment }.toSet()
    }
}

data class SurveyAnswer(val questionId: String, val value: Int)

/**
 * Entrega lista para enviar. Los identificadores y la marca de tiempo se fijan al crearla: reenviar
 * el mismo objeto tras un fallo de red es idempotente en el servidor.
 */
data class SurveySubmission(
    val id: String,
    val clientOperationId: String,
    val instrumentVersionId: String,
    val moment: SurveyMoment,
    val clientCreatedAt: String,
    val answers: List<SurveyAnswer>,
)

/** Confirmación de recepción. El servidor calcula puntajes, pero el participante no los recibe. */
data class SubmissionReceipt(val id: String, val moment: SurveyMoment, val instrumentVersionId: String, val submittedAt: String)

/** Reglas puras de respuesta del instrumento. */
object SurveyRules {
    const val SCALE_MIN = 1
    const val SCALE_MAX = 5

    fun isValidValue(question: SurveyQuestion, value: Int): Boolean = when (question.type) {
        QuestionType.MULTIPLE_CHOICE -> value in question.options.indices
        QuestionType.LIKERT, QuestionType.FREQUENCY -> value in SCALE_MIN..SCALE_MAX
    }

    /** Valor que se envía al elegir la opción en la posición [index] de la lista. */
    fun valueForOption(question: SurveyQuestion, index: Int): Int =
        if (question.type == QuestionType.MULTIPLE_CHOICE) index else index + 1

    /** Posición de la opción elegida a partir del valor enviado; null si no hay respuesta. */
    fun optionIndex(question: SurveyQuestion, value: Int?): Int? =
        value?.let { if (question.type == QuestionType.MULTIPLE_CHOICE) it else it - 1 }

    fun isComplete(survey: Survey, answers: Map<String, Int>): Boolean =
        survey.questions.isNotEmpty() && answers.keys == survey.questions.map { it.id }.toSet() &&
            survey.questions.all { isValidValue(it, answers.getValue(it.id)) }

    fun answeredCount(survey: Survey, answers: Map<String, Int>): Int =
        survey.questions.count { answers[it.id]?.let { value -> isValidValue(it, value) } == true }
}

/** Nombre de cada momento para la interfaz. */
fun SurveyMoment.label(): String = when (this) {
    SurveyMoment.PRETEST -> "Encuesta inicial"
    SurveyMoment.POSTTEST -> "Encuesta final"
}

/**
 * Respuestas a medias de un momento, guardadas en este dispositivo para retomarlas (solo ids de pregunta y valores:
 * nada que identifique a la persona). Se borran al enviar o al retirar el consentimiento.
 */
interface SurveyDraftStore {
    suspend fun read(moment: SurveyMoment, instrumentVersionId: String): Map<String, Int>?
    suspend fun write(moment: SurveyMoment, instrumentVersionId: String, answers: Map<String, Int>?)
}

interface ParticipationRepository {
    suspend fun participation(org: String): ResearchResult<Participation>
    suspend fun recordConsent(org: String, documentVersion: String, accepted: Boolean): ResearchResult<Participation>
    suspend fun submit(org: String, submission: SurveySubmission): ResearchResult<SubmissionReceipt>
}

// ---- Investigador: acceso y consentimiento ----------------------------------------------------

data class ResearchAccess(val canResearch: Boolean, val canManageGrants: Boolean)

data class AccessGrant(val userId: String, val email: String, val displayName: String, val grantedAt: String, val revokedAt: String?, val active: Boolean)

enum class ConsentStatus { ACTIVE, ARCHIVED }

data class ConsentDocumentItem(val version: String, val title: String, val text: String, val responsibleInvestigator: String, val status: ConsentStatus, val publishedAt: String)

data class NewConsentDocument(val version: String, val title: String, val text: String, val responsibleInvestigator: String)

object ResearchLimits {
    const val VERSION_MAX = 40
    const val TITLE_MAX = 200
    const val CONSENT_TEXT_MAX = 20000
    const val INVESTIGATOR_MAX = 200
    const val CODE_MAX = 50
    // No usar NAME_MAX: choca con la macro de C en el encabezado ObjC de iOS.
    const val INSTRUMENT_NAME_MAX = 200
    const val DESCRIPTION_MAX = 2000
    const val QUESTION_MAX = 500
    const val OPTION_MAX = 300
    const val MAX_QUESTIONS = 60
    const val NEED_DESCRIPTION_MAX = 2000
    const val IMPORT_JSON_MAX = 200_000
    const val CAP_KNOWLEDGE = 8
    const val CAP_ATTITUDE = 8
    const val CAP_PRACTICE = 10
    val versionPattern = Regex("[A-Za-z0-9._-]{1,40}")
    val codePattern = Regex("[A-Za-z0-9_-]{1,50}")
}

interface ResearchAccessRepository {
    suspend fun access(): ResearchResult<ResearchAccess>
    suspend fun grants(): ResearchResult<List<AccessGrant>>
    suspend fun grant(email: String): ResearchResult<AccessGrant>
    suspend fun revoke(userId: String): ResearchResult<Unit>
}

// ---- Investigador: instrumentos ---------------------------------------------------------------

data class InstrumentSummary(
    val id: String, val code: String, val name: String, val description: String, val isCap: Boolean,
    val status: String, val createdAt: String, val publishedVersionNumber: Int?, val hasDraft: Boolean,
)

data class InstrumentVersion(val id: String, val versionNumber: Int, val status: String, val rowVersion: String, val publishedAt: String?)

data class InstrumentDetail(val instrument: InstrumentSummary, val versions: List<InstrumentVersion>)

/** Pregunta del borrador, con respuesta correcta solo para opción múltiple. */
data class DraftQuestion(val type: QuestionType, val text: String, val options: List<String>, val correctOption: Int?) {
    val dimension: Dimension
        get() = when (type) {
            QuestionType.MULTIPLE_CHOICE -> Dimension.KNOWLEDGE
            QuestionType.LIKERT -> Dimension.ATTITUDE
            QuestionType.FREQUENCY -> Dimension.PRACTICE
        }
}

data class InstrumentDraft(
    val instrumentId: String, val code: String, val name: String, val isCap: Boolean,
    val versionId: String, val versionNumber: Int, val rowVersion: String, val questions: List<DraftQuestion>,
)

/** Datos para crear o importar un instrumento; las respuestas correctas pueden faltar hasta publicar. */
data class NewInstrument(val code: String, val name: String, val description: String, val isCap: Boolean, val questions: List<DraftQuestion>)

/** Reglas puras de edición y validación previa del borrador (el backend vuelve a validar). */
object InstrumentRules {
    /** Problemas que impiden publicar; vacío significa que el borrador parece listo. */
    fun publishProblems(isCap: Boolean, questions: List<DraftQuestion>): List<String> {
        val problems = mutableListOf<String>()
        if (questions.isEmpty()) problems += "Agrega al menos una pregunta."
        if (isCap) {
            val k = questions.count { it.type == QuestionType.MULTIPLE_CHOICE }
            val a = questions.count { it.type == QuestionType.LIKERT }
            val p = questions.count { it.type == QuestionType.FREQUENCY }
            if (k != ResearchLimits.CAP_KNOWLEDGE || a != ResearchLimits.CAP_ATTITUDE || p != ResearchLimits.CAP_PRACTICE)
                problems += "El cuestionario debe tener ${ResearchLimits.CAP_KNOWLEDGE} preguntas de opción múltiple, ${ResearchLimits.CAP_ATTITUDE} de actitud y ${ResearchLimits.CAP_PRACTICE} de frecuencia (hay $k, $a y $p)."
        }
        questions.forEachIndexed { index, question ->
            if (question.text.isBlank()) problems += "La pregunta ${index + 1} no tiene texto."
            if (question.type == QuestionType.MULTIPLE_CHOICE) {
                if (question.options.size !in 2..6 || question.options.any { it.isBlank() } || question.options.map { it.trim() }.distinct().size != question.options.size)
                    problems += "La pregunta ${index + 1} necesita entre 2 y 6 opciones distintas."
                else if (question.correctOption == null || question.correctOption !in question.options.indices)
                    problems += "Elige la respuesta correcta de la pregunta ${index + 1}."
            }
        }
        return problems
    }

    /** Problemas de formato que el servidor rechazaría al guardar o crear. */
    fun saveProblems(questions: List<DraftQuestion>): List<String> {
        val problems = mutableListOf<String>()
        if (questions.size > ResearchLimits.MAX_QUESTIONS) problems += "Máximo ${ResearchLimits.MAX_QUESTIONS} preguntas."
        questions.forEachIndexed { index, q ->
            if (q.text.length > ResearchLimits.QUESTION_MAX) problems += "La pregunta ${index + 1} supera ${ResearchLimits.QUESTION_MAX} caracteres."
            if (q.options.any { it.length > ResearchLimits.OPTION_MAX }) problems += "Una opción de la pregunta ${index + 1} es demasiado larga."
        }
        return problems
    }
}

interface ResearchCatalogRepository {
    suspend fun consentDocuments(): ResearchResult<List<ConsentDocumentItem>>
    suspend fun publishConsent(document: NewConsentDocument): ResearchResult<ConsentDocumentItem>
    suspend fun instruments(): ResearchResult<List<InstrumentSummary>>
    suspend fun createInstrument(instrument: NewInstrument, imported: Boolean): ResearchResult<InstrumentDraft>
    suspend fun instrument(id: String): ResearchResult<InstrumentDetail>
    suspend fun draft(id: String): ResearchResult<InstrumentDraft>
    suspend fun saveDraft(id: String, rowVersion: String, questions: List<DraftQuestion>): ResearchResult<InstrumentDraft>
    suspend fun newVersion(id: String): ResearchResult<InstrumentDraft>
    suspend fun publish(id: String): ResearchResult<InstrumentVersion>
}

// ---- Investigador: resultados, exportación y necesidades --------------------------------------

data class ResearchOrganization(val id: String, val name: String)

data class MomentStats(val n: Int, val knowledgeMean: Double?, val attitudeMean: Double?, val practiceMean: Double?)

data class PairedStats(val n: Int, val knowledgeDelta: Double?, val attitudeDelta: Double?, val practiceDelta: Double?)

data class OrganizationResult(
    val organizationId: String, val organizationName: String, val participants: Int,
    val pretest: MomentStats, val posttest: MomentStats, val paired: PairedStats,
)

data class ResultsVersion(val id: String, val versionNumber: Int)

data class ResearchResults(val version: ResultsVersion, val page: Page<OrganizationResult>)

/** Archivo CSV ya descargado y acotado; solo vive en memoria hasta guardarlo o compartirlo. */
class CsvExport(val fileName: String, val bytes: ByteArray)

enum class NeedCategory { TRAINING, PROCESS, INFRASTRUCTURE, DOCUMENTATION, TECHNOLOGY, SUPPLIERS, OTHER }

data class DetectedNeed(
    val id: String, val organizationId: String, val organizationName: String, val category: NeedCategory,
    val description: String, val detectedAt: String, val investigatorName: String, val createdAt: String,
)

data class NewNeed(val organizationId: String, val category: NeedCategory, val description: String, val detectedAt: String?)

object ResearchExportLimits { const val MAX_BYTES = 16 * 1024 * 1024 }

interface ResearchAnalysisRepository {
    suspend fun organizations(): ResearchResult<List<ResearchOrganization>>
    suspend fun results(versionId: String?, request: PageRequest): ResearchResult<ResearchResults>
    suspend fun export(versionId: String?, organizationId: String?): ResearchResult<CsvExport>
    suspend fun needs(organizationId: String?, request: PageRequest): ResearchResult<Page<DetectedNeed>>
    suspend fun createNeed(need: NewNeed): ResearchResult<DetectedNeed>
}

/** Puerto para convertir el JSON del cuestionario (formato `cap-instrument.json`) en un instrumento nuevo. */
interface InstrumentImportParser {
    /** Devuelve null si el texto no tiene el formato esperado; ignora campos informativos como `source` y `generalData`. */
    fun parse(text: String): NewInstrument?
}
