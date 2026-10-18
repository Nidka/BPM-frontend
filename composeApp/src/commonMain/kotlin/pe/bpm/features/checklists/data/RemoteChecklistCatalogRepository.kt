package pe.bpm.features.checklists.data

import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.PageMetadata
import pe.bpm.core.network.pageParameters
import pe.bpm.core.network.checkedPage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.checklists.domain.*

/** Adaptador de lectura: comparte sesión por inyección, sin depender de identity. */
class RemoteChecklistCatalogRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : ChecklistCatalogRepository {
    override suspend fun catalog(organizationId: String, request: PageRequest): ChecklistCatalogResult = read(organizationId, null, request)

    override suspend fun template(organizationId: String, templateId: String): ChecklistTemplateResult {
        if (!templateId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return ChecklistTemplateResult.Failure(ChecklistFailure.NOT_FOUND)
        return when (val result = read(organizationId, templateId, PageRequest())) {
            is ChecklistCatalogResult.Success -> ChecklistTemplateResult.Success(result.templates.single())
            is ChecklistCatalogResult.Failure -> ChecklistTemplateResult.Failure(result.reason)
        }
    }
    private suspend fun read(organizationId: String, templateId: String?, request: PageRequest): ChecklistCatalogResult {
        if (!organizationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            return ChecklistCatalogResult.Failure(ChecklistFailure.NOT_FOUND)
        return try {
            val accessToken = token()?.takeIf(String::isNotBlank)
                ?: return ChecklistCatalogResult.Failure(ChecklistFailure.SESSION_REQUIRED)
            val response = client.get("${config.apiBaseUrl}/api/v1/organizations/$organizationId/checklists" + (templateId?.let { "/$it" } ?: "")) {
                bearerAuth(accessToken)
                if (templateId == null) pageParameters(request)
            }
            onSessionResponse(response)
            if (response.status.isSuccess()) {
                val payload = if (templateId == null) response.body<CatalogDto>() else CatalogDto(listOf(response.body<TemplateEnvelopeDto>().data))
                val templates = payload.data
                require(templates.size <= 100)
                require(templates.map { it.id }.distinct().size == templates.size)
                checkedPage(templates.map(TemplateDto::domain), payload.pagination, request).let { ChecklistCatalogResult.Success(it.items, it.nextToken) }
            } else {
                if (response.status.value == 401) onSessionRejected(accessToken)
                ChecklistCatalogResult.Failure(when (response.status.value) {
                    400 -> ChecklistFailure.INVALID_PAGE
                    401 -> ChecklistFailure.SESSION_REQUIRED
                    403 -> ChecklistFailure.ACCESS_DENIED
                    404 -> ChecklistFailure.NOT_FOUND
                    429 -> ChecklistFailure.RATE_LIMITED
                    502 -> ChecklistFailure.INVALID_RESPONSE
                    else -> ChecklistFailure.UNAVAILABLE
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionAccessException) {
            ChecklistCatalogResult.Failure(if (e.expired) ChecklistFailure.SESSION_REQUIRED else ChecklistFailure.CONNECTION)
        } catch (_: SerializationException) {
            ChecklistCatalogResult.Failure(ChecklistFailure.INVALID_RESPONSE)
        } catch (_: JsonConvertException) {
            ChecklistCatalogResult.Failure(ChecklistFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            ChecklistCatalogResult.Failure(ChecklistFailure.INVALID_RESPONSE)
        } catch (_: Throwable) {
            // En Wasm los fallos del navegador también pueden extender Throwable directamente.
            ChecklistCatalogResult.Failure(ChecklistFailure.CONNECTION)
        }
    }
}

@Serializable private data class CatalogDto(val data: List<TemplateDto>, val pagination: PageMetadata = PageMetadata())
@Serializable private data class TemplateEnvelopeDto(val data: TemplateDto)

@Serializable private data class TemplateDto(
    val id: String,
    val name: String,
    val description: String,
    val area: String,
    val versionId: String,
    val versionNumber: Int,
    val items: List<ItemDto>,
) {
    fun domain(): ChecklistTemplate {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && versionId.isNotBlank() && name.isNotBlank() && area.isNotBlank())
        require(versionNumber > 0 && items.size in 1..100)
        require(items.map { it.id }.distinct().size == items.size)
        require(items.map { it.displayOrder }.distinct().size == items.size)
        return ChecklistTemplate(id, name, description, area, versionId, versionNumber,
            items.sortedBy { it.displayOrder }.map(ItemDto::domain))
    }
}

@Serializable private data class ItemDto(
    val id: String,
    val sectionName: String,
    val label: String,
    val responseType: String,
    val displayOrder: Int,
    val required: Boolean,
    val unit: String = "",
    val minimumValue: Double? = null,
    val maximumValue: Double? = null,
    val evidenceRequiredOnFailure: Boolean = false,
) {
    fun domain(): ChecklistItem {
        val type = ChecklistResponseType.valueOf(responseType)
        require(id.isNotBlank() && sectionName.isNotBlank() && label.isNotBlank() && displayOrder > 0)
        if (type == ChecklistResponseType.TEMPERATURE || type == ChecklistResponseType.NUMBER) {
            require(minimumValue != null && maximumValue != null)
            require(minimumValue.isFinite() && maximumValue.isFinite() && minimumValue <= maximumValue)
        } else require(minimumValue == null && maximumValue == null && unit.isEmpty())
        return ChecklistItem(id, sectionName, label, type, displayOrder, required, unit,
            minimumValue, maximumValue, evidenceRequiredOnFailure)
    }
}
