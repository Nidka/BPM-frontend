package pe.bpm.features.checklists.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.*
import pe.bpm.features.checklists.domain.*

/** Historial y publicación de versiones; el servidor decide permisos, versión base y archivado. */
class RemoteChecklistVersionRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : ChecklistVersionRepository {
    private val json = Json { encodeDefaults = true }
    private val id = Regex("[A-Za-z0-9_-]{1,64}")

    override suspend fun versions(org: String, templateId: String, page: PageRequest) =
        call<Page<ChecklistVersionSummary>>(org, templateId) { access ->
            val response =
                client.get(url(org, templateId, "versions")) {
                    bearerAuth(access)
                    pageParameters(page)
                }
            fail(response, access)
                ?: response.body<VersionListDto>().let { list ->
                    val items = list.data.map { it.domain(templateId) }
                    require(items.map { it.id }.distinct().size == items.size)
                    VersionResult.Success(checkedPage(items, list.pagination, page))
                }
        }

    override suspend fun version(org: String, templateId: String, versionId: String) =
        call<ChecklistTemplate>(org, templateId, versionId) { access ->
            val response =
                client.get(url(org, templateId, "versions/$versionId")) { bearerAuth(access) }
            fail(response, access)
                ?: VersionResult.Success(
                    response.body<TemplateEnvelope>().data.domain().also {
                        require(it.id == templateId)
                    }
                )
        }

    override suspend fun publish(org: String, templateId: String, draft: ChecklistVersionDraft) =
        call<ChecklistTemplate>(org, templateId, draft.versionId) { access ->
            val payload =
                json.encodeToString(
                    PublishVersionDto.serializer(),
                    PublishVersionDto(
                        draft.versionId,
                        draft.baseVersionNumber,
                        draft.changeNote,
                        draft.items.map(ChecklistItem::dto),
                    ),
                )
            require(payload.encodeToByteArray().size <= 65536)
            val response =
                client.post(url(org, templateId, "versions")) {
                    bearerAuth(access)
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            fail(response, access)
                ?: response.body<TemplateEnvelope>().data.domain().let { published ->
                    // La respuesta debe ser exactamente la versión solicitada.
                    require(
                        published.id == templateId &&
                            published.versionId == draft.versionId &&
                            published.versionNumber == draft.baseVersionNumber + 1 &&
                            published.items.sortedBy { it.displayOrder } ==
                                draft.items.sortedBy { it.displayOrder }
                    )
                    VersionResult.Success(published)
                }
        }

    private fun url(org: String, templateId: String, path: String): String {
        require(org.matches(id) && templateId.matches(id))
        return "${config.apiBaseUrl}/api/v1/organizations/$org/checklists/$templateId/$path"
    }

    private suspend fun fail(response: HttpResponse, access: String): VersionResult.Failure? {
        onSessionResponse(response)
        if (response.status.isSuccess()) return null
        if (response.status.value == 401) onSessionRejected(access)
        val body =
            if (response.status.value in setOf(409, 422))
                try {
                    response.body<VersionErrorsDto>()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            else null
        val reason =
            when (response.status.value) {
                400 -> VersionFailure.INVALID_PAGE
                401 -> VersionFailure.SESSION
                403 -> VersionFailure.DENIED
                404 -> VersionFailure.NOT_FOUND
                409 ->
                    if (body?.errors?.firstOrNull()?.code == "VERSION_CONFLICT")
                        VersionFailure.VERSION_CONFLICT
                    else VersionFailure.CONFLICT
                422 -> VersionFailure.VALIDATION
                429 -> VersionFailure.RATE_LIMITED
                else -> VersionFailure.UNAVAILABLE
            }
        val fields =
            if (reason == VersionFailure.VALIDATION)
                body
                    ?.errors
                    .orEmpty()
                    .flatMap { it.fields.keys }
                    .filter { it in setOf("versionId", "baseVersionNumber", "changeNote", "items") }
                    .associateWith { "Revisa este campo y vuelve a intentar." }
            else emptyMap()
        return VersionResult.Failure(reason, fields)
    }

    private suspend fun <T> call(
        org: String,
        templateId: String,
        versionId: String? = null,
        block: suspend (String) -> VersionResult<T>,
    ): VersionResult<T> =
        try {
            require(org.matches(id) && templateId.matches(id) && versionId?.matches(id) != false)
            val access =
                token()?.takeIf { it.isNotBlank() } ?: return VersionResult.Failure(VersionFailure.SESSION)
            block(access)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionAccessException) {
            VersionResult.Failure(if (e.expired) VersionFailure.SESSION else VersionFailure.CONNECTION)
        } catch (_: SerializationException) {
            VersionResult.Failure(VersionFailure.INVALID_RESPONSE)
        } catch (_: JsonConvertException) {
            VersionResult.Failure(VersionFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            VersionResult.Failure(VersionFailure.INVALID_RESPONSE)
        } catch (_: Throwable) {
            VersionResult.Failure(VersionFailure.CONNECTION)
        }
}

@Serializable private data class TemplateEnvelope(val data: TemplateDto)

@Serializable
private data class PublishVersionDto(
    val versionId: String,
    val baseVersionNumber: Int,
    val changeNote: String,
    val items: List<ItemDto>,
)

@Serializable private data class VersionErrorsDto(val errors: List<VersionErrorDto> = emptyList())

@Serializable
private data class VersionErrorDto(val code: String = "", val fields: Map<String, String> = emptyMap())

@Serializable
private data class VersionListDto(
    val data: List<VersionSummaryDto>,
    val pagination: PageMetadata = PageMetadata(),
)

@Serializable
private data class VersionSummaryDto(
    val id: String,
    val templateId: String,
    val versionNumber: Int,
    val status: String,
    val changeNote: String = "",
    val itemCount: Int,
    val publishedAt: String,
) {
    fun domain(templateId: String): ChecklistVersionSummary {
        require(this.templateId == templateId && id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(versionNumber > 0 && itemCount in 1..100 && changeNote.length <= 500)
        Instant.parse(publishedAt)
        return ChecklistVersionSummary(
            id,
            templateId,
            versionNumber,
            VersionStatus.valueOf(status),
            changeNote,
            itemCount,
            publishedAt,
        )
    }
}
