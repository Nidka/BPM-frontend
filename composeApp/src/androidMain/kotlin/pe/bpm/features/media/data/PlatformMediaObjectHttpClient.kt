package pe.bpm.features.media.data

import io.ktor.client.request.HttpRequestBuilder

internal actual fun HttpRequestBuilder.configureMediaObjectRequest() = Unit

internal actual fun mediaObjectHttpEngine(): io.ktor.client.engine.HttpClientEngine = pe.bpm.core.network.platformHttpEngine()

internal actual fun includeMediaObjectHeader(name: String): Boolean = true
