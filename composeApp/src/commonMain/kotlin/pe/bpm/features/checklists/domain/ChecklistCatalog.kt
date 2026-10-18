package pe.bpm.features.checklists.domain

import pe.bpm.core.domain.PageRequest

/** Versión publicada de una plantilla; consultarla no inicia una ejecución. */
data class ChecklistTemplate(
    val id: String,
    val name: String,
    val description: String,
    val area: String,
    val versionId: String,
    val versionNumber: Int,
    val items: List<ChecklistItem>,
)

data class ChecklistItem(
    val id: String,
    val sectionName: String,
    val label: String,
    val responseType: ChecklistResponseType,
    val displayOrder: Int,
    val required: Boolean,
    val unit: String,
    val minimumValue: Double?,
    val maximumValue: Double?,
    val evidenceRequiredOnFailure: Boolean,
)

enum class ChecklistResponseType { COMPLIANCE, TEMPERATURE, TEXT, NUMBER }

enum class ChecklistFailure {
    SESSION_REQUIRED, ACCESS_DENIED, NOT_FOUND, RATE_LIMITED, CONNECTION, UNAVAILABLE, INVALID_RESPONSE, INVALID_PAGE,
}

sealed interface ChecklistCatalogResult {
    data class Success(val templates: List<ChecklistTemplate>, val nextToken: String = "") : ChecklistCatalogResult
    data class Failure(val reason: ChecklistFailure) : ChecklistCatalogResult
}

interface ChecklistCatalogRepository {
    suspend fun catalog(organizationId: String, request: PageRequest = PageRequest()): ChecklistCatalogResult
    suspend fun template(organizationId: String, templateId: String): ChecklistTemplateResult
}

class LoadChecklistCatalog(private val repository: ChecklistCatalogRepository) {
    suspend operator fun invoke(organizationId: String, request: PageRequest = PageRequest()): ChecklistCatalogResult = repository.catalog(organizationId, request)
}

sealed interface ChecklistTemplateResult {
    data class Success(val template: ChecklistTemplate) : ChecklistTemplateResult
    data class Failure(val reason: ChecklistFailure) : ChecklistTemplateResult
}
class LoadChecklistTemplate(private val repository: ChecklistCatalogRepository) {
    suspend operator fun invoke(organizationId: String, templateId: String) = repository.template(organizationId, templateId)
}
