package pe.bpm.features.research.data

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pe.bpm.features.research.domain.*

/**
 * Traducción de las respuestas JSON del contrato a modelos de dominio. Cada función valida formato,
 * rangos y coherencia: ante cualquier dato inesperado lanza [IllegalArgumentException], que el
 * repositorio convierte en un fallo público sin detalle técnico.
 */
@Serializable internal data class ResearchEnvelope<T>(val data: T)
@Serializable internal data class ResearchErrorsEnvelope(val errors: List<ResearchErrorDto> = emptyList())
@Serializable internal data class ResearchErrorDto(val code: String = "")

private val idPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
internal fun researchId(value: String): String { require(idPattern.matches(value)); return value }

internal fun JsonObject.str(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Falta $key")
internal fun JsonObject.strOrNull(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Texto inválido en $key")
    else -> throw IllegalArgumentException("Texto inválido en $key")
}
internal fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("Falta $key")
internal fun JsonObject.intOrNull(key: String): Int? = if (this[key] == null || this[key] == JsonNull) null else int(key)
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw IllegalArgumentException("Falta $key")
internal fun JsonObject.double(key: String): Double? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf { !it.isString }?.doubleOrNull ?: throw IllegalArgumentException("Número inválido en $key")
    else -> throw IllegalArgumentException("Número inválido en $key")
}
internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: throw IllegalArgumentException("Falta $key")
internal fun JsonObject.objOrNull(key: String): JsonObject? = if (this[key] == null || this[key] == JsonNull) null else obj(key)
internal fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: throw IllegalArgumentException("Falta $key")
internal fun JsonElement.asObj(): JsonObject = this as? JsonObject ?: throw IllegalArgumentException("Objeto esperado")
private fun JsonObject.stamp(key: String): String = str(key).also { Instant.parse(it) }
private fun JsonObject.stampOrNull(key: String): String? = strOrNull(key)?.also { Instant.parse(it) }

private inline fun <reified E : Enum<E>> enumOf(value: String): E = enumValues<E>().firstOrNull { it.name == value } ?: throw IllegalArgumentException("Valor desconocido")

// ---- Participante -----------------------------------------------------------------------------

private fun consentDocument(json: JsonObject) = ConsentDocument(json.str("version"), json.str("title"), json.str("text"), json.str("responsibleInvestigator"), json.stamp("publishedAt"))
    .also { require(ResearchLimits.versionPattern.matches(it.version)) }

private fun surveyQuestion(json: JsonObject): SurveyQuestion {
    val type = enumOf<QuestionType>(json.str("type"))
    val dimension = enumOf<Dimension>(json.str("dimension"))
    val options = json.arr("options").map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("Opción inválida") }
    val text = json.str("text")
    require(text.isNotBlank() && text.length <= ResearchLimits.QUESTION_MAX * 2)
    when (type) {
        QuestionType.MULTIPLE_CHOICE -> require(dimension == Dimension.KNOWLEDGE && options.size in 2..6)
        QuestionType.LIKERT -> require(dimension == Dimension.ATTITUDE && options.size == 5)
        QuestionType.FREQUENCY -> require(dimension == Dimension.PRACTICE && options.size == 5)
    }
    require(options.all { it.isNotBlank() })
    return SurveyQuestion(researchId(json.str("id")), json.int("order"), dimension, type, text, options)
}

internal fun participationOf(data: JsonObject): Participation {
    val participant = data.objOrNull("participant")?.let { Participant(it.str("code"), enumOf(it.str("status"))) }
    val document = data.objOrNull("consentDocument")?.let(::consentDocument)
    val consent = data.objOrNull("consent")?.let { ConsentRecord(it.str("documentVersion"), it.bool("accepted"), it.stamp("recordedAt"), it.strOrNull("responsibleInvestigator").orEmpty()) }
    val valid = data.bool("consentValid")
    val survey = data.objOrNull("survey")?.let { json ->
        val questions = json.arr("questions").map { surveyQuestion(it.asObj()) }.sortedBy { it.order }
        require(questions.size <= ResearchLimits.MAX_QUESTIONS && questions.map { it.id.lowercase() }.distinct().size == questions.size)
        Survey(json.str("instrumentCode"), json.str("name"), researchId(json.str("instrumentVersionId")), json.int("versionNumber"), questions)
    }
    // La encuesta solo existe con consentimiento vigente: cualquier otra combinación es inconsistente.
    require(survey == null || valid)
    val submissions = data.arr("submissions").map {
        val json = it.asObj()
        SubmissionRecord(enumOf(json.str("moment")), researchId(json.str("instrumentVersionId")), json.stamp("submittedAt"))
    }
    require(submissions.size <= 100)
    return Participation(participant, document, consent, valid, survey, submissions)
}

internal fun receiptOf(data: JsonObject, sent: SurveySubmission): SubmissionReceipt {
    val receipt = SubmissionReceipt(researchId(data.str("id")), enumOf(data.str("moment")), researchId(data.str("instrumentVersionId")), data.stamp("submittedAt"))
    require(receipt.id.equals(sent.id, ignoreCase = true) && receipt.moment == sent.moment && receipt.instrumentVersionId.equals(sent.instrumentVersionId, ignoreCase = true))
    return receipt
}

// ---- Investigador -----------------------------------------------------------------------------

internal fun accessOf(data: JsonObject) = ResearchAccess(data.bool("canResearch"), data.bool("canManageGrants"))

internal fun grantOf(json: JsonObject): AccessGrant {
    val grant = AccessGrant(researchId(json.str("userId")), json.str("email"), json.strOrNull("displayName").orEmpty(), json.stamp("grantedAt"), json.stampOrNull("revokedAt"), json.bool("active"))
    require(grant.email.isNotBlank() && grant.email.length <= 254)
    return grant
}

internal fun consentItemOf(json: JsonObject): ConsentDocumentItem {
    val doc = consentDocument(json)
    return ConsentDocumentItem(doc.version, doc.title, doc.text, doc.responsibleInvestigator, enumOf(json.str("status")), doc.publishedAt)
}

internal fun instrumentOf(json: JsonObject) = InstrumentSummary(
    researchId(json.str("id")), json.str("code"), json.str("name"), json.strOrNull("description").orEmpty(), json.bool("isCap"),
    json.str("status"), json.stamp("createdAt"), json.intOrNull("publishedVersionNumber"), json["hasDraft"]?.let { json.bool("hasDraft") } ?: false,
)

private fun rowVersionOf(json: JsonObject): String {
    val primitive = json["rowVersion"] as? JsonPrimitive ?: throw IllegalArgumentException("Falta rowVersion")
    val text = primitive.content
    require(Regex("[\\x21-\\x7e]{1,64}").matches(text))
    return text
}

internal fun versionOf(json: JsonObject) = InstrumentVersion(researchId(json.str("id")), json.int("versionNumber"), json.str("status"), rowVersionOf(json), json.stampOrNull("publishedAt"))

internal fun detailOf(data: JsonObject) = InstrumentDetail(instrumentOf(data.obj("instrument")), data.arr("versions").map { versionOf(it.asObj()) })

internal fun draftOf(data: JsonObject): InstrumentDraft {
    val instrument = data.obj("instrument")
    val version = data.obj("version")
    val questions = data.arr("questions").map {
        val json = it.asObj()
        val type = enumOf<QuestionType>(json.str("type"))
        val options = json.arr("options").map { option -> (option as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("Opción inválida") }
        val correct = json.intOrNull("correctOption")
        require(correct == null || correct in options.indices)
        DraftQuestion(type, json.str("text"), options, correct)
    }
    require(questions.size <= ResearchLimits.MAX_QUESTIONS)
    return InstrumentDraft(researchId(instrument.str("id")), instrument.str("code"), instrument.str("name"), instrument.bool("isCap"),
        researchId(version.str("id")), version.int("versionNumber"), rowVersionOf(version), questions)
}

internal fun organizationOf(json: JsonObject) = ResearchOrganization(researchId(json.str("id")), json.str("name"))

private fun momentOf(json: JsonObject): MomentStats {
    val stats = MomentStats(json.int("n"), json.double("knowledgeMean"), json.double("attitudeMean"), json.double("practiceMean"))
    require(stats.n >= 0)
    return stats
}

private fun pairedOf(json: JsonObject): PairedStats {
    val stats = PairedStats(json.int("n"), json.double("knowledgeDelta"), json.double("attitudeDelta"), json.double("practiceDelta"))
    require(stats.n >= 0)
    return stats
}

internal fun resultOf(json: JsonObject): OrganizationResult {
    val result = OrganizationResult(researchId(json.str("organizationId")), json.str("organizationName"), json.int("participants"),
        momentOf(json.obj("pretest")), momentOf(json.obj("posttest")), pairedOf(json.obj("paired")))
    require(result.participants >= 0)
    return result
}

internal fun needOf(json: JsonObject) = DetectedNeed(
    researchId(json.str("id")), researchId(json.str("organizationId")), json.str("organizationName"), enumOf(json.str("category")),
    json.str("description"), json.stamp("detectedAt"), json.strOrNull("investigatorName").orEmpty(), json.stamp("createdAt"),
)

// ---- Cuerpos de salida ------------------------------------------------------------------------

internal fun questionBody(question: DraftQuestion): JsonObject = buildJsonObject {
    put("type", question.type.name)
    put("text", question.text)
    putJsonArray("options") { question.options.forEach { add(it) } }
    put("correctOption", if (question.type == QuestionType.MULTIPLE_CHOICE) question.correctOption?.let { JsonPrimitive(it) } ?: JsonNull else JsonNull)
}

internal fun rowVersionBody(value: String): JsonPrimitive = value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
