package pe.bpm.features.media.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder

/** Cliente exclusivo para objetos firmados, sin sesión, cookies, logging ni redirecciones. */
fun createMediaObjectHttpClient(engine: HttpClientEngine = mediaObjectHttpEngine()): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 90_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
}

internal expect fun HttpRequestBuilder.configureMediaObjectRequest()

internal expect fun mediaObjectHttpEngine(): HttpClientEngine

internal expect fun includeMediaObjectHeader(name: String): Boolean
