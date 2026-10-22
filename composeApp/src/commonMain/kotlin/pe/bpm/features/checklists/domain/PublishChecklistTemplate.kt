package pe.bpm.features.checklists.domain

import pe.bpm.core.domain.DecimalInput

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
        if (!uuid.matches(draft.id) || !uuid.matches(draft.versionId) || draft.id == draft.versionId)
            fields["id"] = "No pudimos preparar la plantilla. Vuelve a abrir el editor."
        ChecklistInputRules.name.error(draft.name)?.let { fields["name"] = it }
        ChecklistInputRules.area.error(draft.area)?.let { fields["area"] = it }
        ChecklistInputRules.description.error(draft.description)?.let { fields["description"] = it }
        if (draft.items.size !in 1..100) fields["items"] = "Incluye entre 1 y 100 preguntas."
        val ids = mutableSetOf(draft.id, draft.versionId)
        draft.items.forEach { item ->
            if (!uuid.matches(item.id) || !ids.add(item.id)) fields["items"] = "Las preguntas necesitan identificadores únicos."
            ChecklistInputRules.section.error(item.sectionName)?.let { fields["items.${item.id}.sectionName"] = it }
            ChecklistInputRules.label.error(item.label)?.let { fields["items.${item.id}.label"] = it }
            ChecklistInputRules.unit.error(item.unit)?.let { fields["items.${item.id}.unit"] = it }
            val numeric = item.responseType in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE)
            if (numeric && (!DecimalInput.isValid(item.minimumValue) || !DecimalInput.isValid(item.maximumValue) || item.minimumValue!! > item.maximumValue!!))
                fields["items.${item.id}.range"] = "Indica mínimo y máximo válidos, en orden y con hasta dos decimales."
            if (!numeric && (item.minimumValue != null || item.maximumValue != null || item.unit.isNotEmpty()))
                fields["items.${item.id}.range"] = "Este tipo de pregunta no utiliza unidad ni rango."
            if (item.evidenceRequiredOnFailure) fields["items"] = "La evidencia obligatoria estará disponible con el módulo multimedia."
        }
        return fields
    }
    private companion object { val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") }
}
