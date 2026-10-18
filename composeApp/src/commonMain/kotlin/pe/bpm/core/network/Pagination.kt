package pe.bpm.core.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest

/** Metadatos de transporte compartidos por adaptadores; el dominio conserva Page sin DTO. */
@Serializable internal data class PageMetadata(val nextPageToken: String = "")

internal fun HttpRequestBuilder.pageParameters(request: PageRequest) {
    parameter("pageSize", request.size)
    if (request.token.isNotEmpty()) parameter("pageToken", request.token)
    if (request.query.isNotBlank()) parameter("query", request.query.trim())
}
internal fun validatePageToken(token: String) {
    require(token.length <= 2048 && token.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' })
}
internal fun <T> checkedPage(items: List<T>, metadata: PageMetadata, request: PageRequest): Page<T> {
    require(items.size <= 100)
    validatePageToken(metadata.nextPageToken)
    require(metadata.nextPageToken.isEmpty() || metadata.nextPageToken != request.token)
    return Page(items, metadata.nextPageToken)
}
