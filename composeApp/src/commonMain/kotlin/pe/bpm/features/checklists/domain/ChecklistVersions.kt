package pe.bpm.features.checklists.domain

import pe.bpm.core.domain.DecimalInput
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest

enum class VersionStatus {
    PUBLISHED,
    ARCHIVED,
}

/** Resumen de una versión del historial; las preguntas se consultan aparte. */
data class ChecklistVersionSummary(
    val id: String,
    val templateId: String,
    val versionNumber: Int,
    val status: VersionStatus,
    val changeNote: String,
    val itemCount: Int,
    val publishedAt: String,
)

/**
 * Versión nueva de una plantilla ya publicada. `baseVersionNumber` es la versión sobre la que se
 * editó: si alguien publicó antes, el servidor responde VERSION_CONFLICT.
 */
data class ChecklistVersionDraft(
    val versionId: String,
    val baseVersionNumber: Int,
    val changeNote: String,
    val items: List<ChecklistItem>,
)

enum class VersionFailure {
    SESSION,
    DENIED,
    NOT_FOUND,
    VALIDATION,
    /** Otra persona publicó una versión más nueva: hay que recargar y rehacer los cambios. */
    VERSION_CONFLICT,
    CONFLICT,
    RATE_LIMITED,
    CONNECTION,
    UNAVAILABLE,
    INVALID_RESPONSE,
    INVALID_PAGE,
}

sealed interface VersionResult<out T> {
    data class Success<T>(val value: T) : VersionResult<T>

    data class Failure(
        val reason: VersionFailure,
        val fields: Map<String, String> = emptyMap(),
    ) : VersionResult<Nothing>
}

interface ChecklistVersionRepository {
    suspend fun versions(
        organizationId: String,
        templateId: String,
        page: PageRequest,
    ): VersionResult<Page<ChecklistVersionSummary>>

    suspend fun version(
        organizationId: String,
        templateId: String,
        versionId: String,
    ): VersionResult<ChecklistTemplate>

    suspend fun publish(
        organizationId: String,
        templateId: String,
        draft: ChecklistVersionDraft,
    ): VersionResult<ChecklistTemplate>
}

class LoadChecklistVersions(private val repository: ChecklistVersionRepository) {
    suspend operator fun invoke(org: String, templateId: String, page: PageRequest) =
        repository.versions(org, templateId, page)
}

class LoadChecklistVersion(private val repository: ChecklistVersionRepository) {
    suspend operator fun invoke(org: String, templateId: String, versionId: String) =
        repository.version(org, templateId, versionId)
}

/** Valida y publica una versión nueva con las mismas reglas de preguntas que la creación. */
class PublishChecklistVersion(private val repository: ChecklistVersionRepository) {
    suspend operator fun invoke(
        org: String,
        templateId: String,
        current: ChecklistTemplate,
        draft: ChecklistVersionDraft,
    ): VersionResult<ChecklistTemplate> {
        val normalized =
            draft.copy(
                changeNote = draft.changeNote.trim(),
                items =
                    draft.items.mapIndexed { index, item ->
                        item.copy(
                            sectionName = item.sectionName.trim(),
                            label = item.label.trim(),
                            unit = item.unit.trim(),
                            displayOrder = index + 1,
                        )
                    },
            )
        val errors = validate(normalized, current)
        return if (errors.isNotEmpty()) VersionResult.Failure(VersionFailure.VALIDATION, errors)
        else repository.publish(org, templateId, normalized)
    }

    fun validate(draft: ChecklistVersionDraft, current: ChecklistTemplate): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        if (!uuid.matches(draft.versionId) || draft.baseVersionNumber < 1)
            fields["versionId"] = "No pudimos preparar la versión. Vuelve a abrir el editor."
        if (draft.baseVersionNumber != current.versionNumber)
            fields["baseVersionNumber"] = "La plantilla cambió. Recarga antes de publicar."
        ChecklistVersionRules.note.error(draft.changeNote)?.let { fields["changeNote"] = it }
        if (draft.items.size !in 1..100) fields["items"] = "Incluye entre 1 y 100 preguntas."
        val used = mutableSetOf(draft.versionId)
        draft.items.forEach { item ->
            if (!uuid.matches(item.id) || !used.add(item.id))
                fields["items"] = "Las preguntas necesitan identificadores únicos."
            if (current.items.any { it.id == item.id })
                fields["items"] = "Cada pregunta de una versión nueva necesita un identificador nuevo."
            ChecklistInputRules.section.error(item.sectionName)?.let { fields["items.${item.id}.sectionName"] = it }
            ChecklistInputRules.label.error(item.label)?.let { fields["items.${item.id}.label"] = it }
            ChecklistInputRules.unit.error(item.unit)?.let { fields["items.${item.id}.unit"] = it }
            val numeric = item.responseType in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE)
            if (numeric && (!DecimalInput.isValid(item.minimumValue) || !DecimalInput.isValid(item.maximumValue) || item.minimumValue!! > item.maximumValue!!))
                fields["items.${item.id}.range"] = "Indica mínimo y máximo válidos, en orden y con hasta dos decimales."
            if (!numeric && (item.minimumValue != null || item.maximumValue != null || item.unit.isNotEmpty()))
                fields["items.${item.id}.range"] = "Este tipo de pregunta no utiliza unidad ni rango."
            if (item.evidenceRequiredOnFailure && item.responseType == ChecklistResponseType.TEXT)
                fields["items"] = "La foto obligatoria requiere una pregunta de cumplimiento o medición."
        }
        if (fields.isEmpty() && !ChecklistVersionRules.changed(current.items, draft.items))
            fields["items"] = "No hay cambios respecto de la versión vigente."
        return fields
    }

    private companion object {
        val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

object ChecklistVersionRules {
    val note = pe.bpm.core.domain.InputRules.text(0, 500)

    /** Compara el contenido de las preguntas ignorando sus identificadores. */
    fun changed(before: List<ChecklistItem>, after: List<ChecklistItem>): Boolean {
        fun shape(items: List<ChecklistItem>) =
            items.sortedBy { it.displayOrder }.map { it.copy(id = "", displayOrder = 0) }
        return shape(before) != shape(after)
    }

    /** Preguntas de la versión vigente con un identificador nuevo, listas para editarse. */
    fun editable(items: List<ChecklistItem>, newId: () -> String): List<TemplateQuestionInput> =
        items.sortedBy { it.displayOrder }.map {
            TemplateQuestionInput(
                newId(),
                it.sectionName,
                it.label,
                it.responseType,
                it.required,
                it.unit,
                it.minimumValue?.let(DecimalInput::format).orEmpty(),
                it.maximumValue?.let(DecimalInput::format).orEmpty(),
                it.evidenceRequiredOnFailure,
            )
        }
}
