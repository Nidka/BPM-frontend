package pe.bpm.features.research.data

import kotlinx.serialization.json.*
import pe.bpm.features.research.domain.*

/**
 * Lee el formato del cuestionario oficial: `code`, `name`, `description`, `isCap` y `questions`.
 * `source` y `generalData` se descartan; las respuestas correctas pueden venir en null.
 */
class JsonInstrumentImportParser : InstrumentImportParser {
    override fun parse(text: String): NewInstrument? {
        if (text.isBlank() || text.length > ResearchLimits.IMPORT_JSON_MAX) return null
        return try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
            val code = root.str("code").trim().uppercase()
            val name = root.str("name").trim()
            val description = root.strOrNull("description").orEmpty()
            val isCap = (root["isCap"] as? JsonPrimitive)?.booleanOrNull ?: false
            val questions = root.arr("questions").map { element ->
                val json = element.asObj()
                val type = QuestionType.entries.firstOrNull { it.name == json.str("type") } ?: return null
                val options = (json["options"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null } ?: emptyList()
                DraftQuestion(type, json.str("text").trim(), options.map { it.trim() }, if (type == QuestionType.MULTIPLE_CHOICE) json.intOrNull("correctOption") else null)
            }
            if (!ResearchLimits.codePattern.matches(code) || name.isEmpty() || questions.isEmpty()) null
            else NewInstrument(code, name, description, isCap, questions)
        } catch (_: Exception) {
            null
        }
    }
}
