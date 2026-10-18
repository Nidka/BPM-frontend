package pe.bpm.features.checklists.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.checklists.domain.*

/** Comprueba contenido publicado y usa IDs estables para confirmar los reintentos mediante lectura. */
class RemoteChecklistTemplatePublisher(
    private val client: HttpClient, private val config: AppConfig,
    private val reader: PublishedChecklistReader,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : ChecklistTemplatePublisher {
    private val publicationJson = Json { encodeDefaults = true }
    override suspend fun publish(organizationId: String, draft: ChecklistTemplateDraft): TemplatePublicationResult {
        if (!organizationId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return rejected(PublicationFailure.ACCESS_DENIED)
        return try {
            val payload = publicationJson.encodeToString(PublishDto.serializer(), PublishDto(draft.id, draft.name, draft.description, draft.area, draft.versionId, draft.items.map(ChecklistItem::dto)))
            if (payload.encodeToByteArray().size > 65536) return TemplatePublicationResult.Rejected(PublicationFailure.VALIDATION,
                mapOf("items" to "La plantilla supera el tamaño permitido. Reduce los textos o divídela en varias plantillas."))
            val existing = existing(organizationId, draft)
            currentCoroutineContext().ensureActive()
            if (existing != null) return existing
            val access = token()?.takeIf(String::isNotBlank) ?: return rejected(PublicationFailure.SESSION_REQUIRED)
            val response = client.post("${config.apiBaseUrl}/api/v1/organizations/$organizationId/checklists") {
                bearerAuth(access); contentType(ContentType.Application.Json)
                setBody(payload)
            }
            onSessionResponse(response)
            when {
                response.status.isSuccess() -> {
                    val published = response.body<PublishedDto>().data.domain()
                    if (matches(draft, published)) TemplatePublicationResult.Published(published) else rejected(PublicationFailure.INVALID_RESPONSE)
                }
                response.status.value == 409 -> existing(organizationId, draft) ?: TemplatePublicationResult.Rejected(PublicationFailure.VALIDATION,
                    mapOf("name" to "Revisa si ya existe una plantilla con este nombre. Usa un nombre diferente si estás creando otra."))
                else -> {
                    if (response.status.value == 401) onSessionRejected(access)
                    val reason = when (response.status.value) {
                        401 -> PublicationFailure.SESSION_REQUIRED
                        403 -> PublicationFailure.ACCESS_DENIED
                        400, 415, 422 -> PublicationFailure.VALIDATION
                        429 -> PublicationFailure.RATE_LIMITED
                        else -> PublicationFailure.UNAVAILABLE
                    }
                    val fields = if (reason == PublicationFailure.VALIDATION) {
                        val body = response.body<ErrorsDto>()
                        body.errors.flatMap { it.fields.keys }.filter { it in setOf("id", "name", "description", "area", "items") }
                            .associateWith { "Revisa este campo y vuelve a intentar." }
                    } else emptyMap()
                    TemplatePublicationResult.Rejected(reason, fields)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: SessionAccessException) { rejected(if (e.expired) PublicationFailure.SESSION_REQUIRED else PublicationFailure.CONNECTION) }
        catch (_: Throwable) { rejected(PublicationFailure.UNAVAILABLE) }
    }
    private suspend fun existing(organizationId: String, draft: ChecklistTemplateDraft): TemplatePublicationResult? =
        when (val result = reader.template(organizationId, draft.id)) {
            is ChecklistTemplateResult.Success -> if (matches(draft, result.template)) TemplatePublicationResult.Published(result.template) else rejected(PublicationFailure.CONFLICT)
            is ChecklistTemplateResult.Failure -> when (result.reason) {
                ChecklistFailure.NOT_FOUND -> null
                ChecklistFailure.SESSION_REQUIRED -> rejected(PublicationFailure.SESSION_REQUIRED)
                ChecklistFailure.ACCESS_DENIED -> rejected(PublicationFailure.ACCESS_DENIED)
                ChecklistFailure.RATE_LIMITED -> rejected(PublicationFailure.RATE_LIMITED)
                else -> rejected(PublicationFailure.UNAVAILABLE)
            }
        }
    private fun rejected(reason: PublicationFailure) = TemplatePublicationResult.Rejected(reason)
    private fun matches(draft: ChecklistTemplateDraft, result: ChecklistTemplate) = result.id == draft.id && result.versionId == draft.versionId &&
        result.versionNumber == 1 && result.name == draft.name && result.description == draft.description && result.area == draft.area &&
        result.items.sortedBy { it.displayOrder } == draft.items.sortedBy { it.displayOrder }
}
@Serializable private data class PublishDto(val id: String, val name: String, val description: String, val area: String, val versionId: String, val items: List<ItemDto>)
@Serializable private data class PublishedDto(val data: TemplateDto)
@Serializable private data class ErrorsDto(val errors: List<ErrorDto> = emptyList())
@Serializable private data class ErrorDto(val fields: Map<String, String> = emptyMap())
