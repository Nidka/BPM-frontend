package pe.bpm.features.checklists.domain

import kotlin.math.abs
import kotlin.math.round

/** Propuesta de primera versión; el backend confirma publicación, permisos y versión. */
data class ChecklistTemplateDraft(
    val id: String, val versionId: String, val name: String, val description: String,
    val area: String, val items: List<ChecklistItem>,
)

enum class PublicationFailure { SESSION_REQUIRED, ACCESS_DENIED, VALIDATION, CONFLICT, RATE_LIMITED, CONNECTION, UNAVAILABLE, INVALID_RESPONSE }
sealed interface TemplatePublicationResult {
    data class Published(val template: ChecklistTemplate) : TemplatePublicationResult
    data class Rejected(val reason: PublicationFailure, val fields: Map<String, String> = emptyMap()) : TemplatePublicationResult
}
interface ChecklistTemplatePublisher {
    suspend fun publish(organizationId: String, draft: ChecklistTemplateDraft): TemplatePublicationResult
}

class PublishChecklistTemplate(private val publisher: ChecklistTemplatePublisher) {
    suspend operator fun invoke(organizationId: String, draft: ChecklistTemplateDraft): TemplatePublicationResult {
        val normalized = draft.copy(name = draft.name.trim(), area = draft.area.trim(), description = draft.description.trim(),
            items = draft.items.mapIndexed { index, item -> item.copy(sectionName = item.sectionName.trim(), label = item.label.trim(),
                unit = item.unit.trim(), displayOrder = index + 1, minimumValue = item.minimumValue?.let { if (it == 0.0) 0.0 else it },
                maximumValue = item.maximumValue?.let { if (it == 0.0) 0.0 else it }) })
        val errors = validate(normalized)
        return if (errors.isNotEmpty()) TemplatePublicationResult.Rejected(PublicationFailure.VALIDATION, errors)
            else publisher.publish(organizationId, normalized)
    }
    fun validate(draft: ChecklistTemplateDraft): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        fun text(key: String, value: String, min: Int, max: Int) {
            val normalized = value.trim()
            if (normalized.count { !it.isLowSurrogate() } !in min..max || normalized.any { it.isISOControl() })
                fields[key] = "Usa entre $min y $max caracteres, sin caracteres de control."
        }
        if (!uuid.matches(draft.id) || !uuid.matches(draft.versionId) || draft.id == draft.versionId)
            fields["id"] = "No pudimos preparar la plantilla. Vuelve a abrir el editor."
        text("name", draft.name, 2, 180); text("area", draft.area, 2, 80); text("description", draft.description, 0, 500)
        if (draft.items.size !in 1..100) fields["items"] = "Incluye entre 1 y 100 preguntas."
        val ids = mutableSetOf(draft.id, draft.versionId)
        draft.items.forEach { item ->
            if (!uuid.matches(item.id) || !ids.add(item.id)) fields["items"] = "Las preguntas necesitan identificadores únicos."
            text("items.${item.id}.sectionName", item.sectionName, 2, 120)
            text("items.${item.id}.label", item.label, 2, 300)
            if (item.unit.any { it.isISOControl() } || item.unit.encodeToByteArray().size > 20)
                fields["items.${item.id}.unit"] = "Escribe una unidad breve, sin caracteres de control."
            val numeric = item.responseType in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE)
            if (numeric && (!validNumber(item.minimumValue) || !validNumber(item.maximumValue) || item.minimumValue!! > item.maximumValue!!))
                fields["items.${item.id}.range"] = "Indica mínimo y máximo válidos, en orden y con hasta dos decimales."
            if (!numeric && (item.minimumValue != null || item.maximumValue != null || item.unit.isNotEmpty()))
                fields["items.${item.id}.range"] = "Este tipo de pregunta no utiliza unidad ni rango."
            if (item.evidenceRequiredOnFailure) fields["items"] = "La evidencia obligatoria estará disponible con el módulo multimedia."
        }
        return fields
    }
    private fun validNumber(value: Double?) = value != null && value.isFinite() && abs(value) < 100000000 && abs(value * 100 - round(value * 100)) < 0.000001
    private companion object { val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }
}
