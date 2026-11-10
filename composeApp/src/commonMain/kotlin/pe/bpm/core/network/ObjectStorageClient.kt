package pe.bpm.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout

/**
 * Cliente exclusivo para objetos firmados (R2): sin sesión BPM, sin negociación de contenido,
 * sin redirecciones ni registro de URL. Los tiempos son holgados porque los videos son grandes;
 * cada operación puede ajustar su propio límite con `timeout { }`.
 */
fun createObjectStorageClient(engine: HttpClientEngine = platformHttpEngine()): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 60_000
    }
}
