package pe.bpm.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun platformHttpEngine(): HttpClientEngine

fun createHttpClient(engine: HttpClientEngine = platformHttpEngine()): HttpClient =
    HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 12_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 12_000
        }
        // Después de HttpTimeout (los interceptores de envío más recientes se ejecutan primero): ve su excepción
        // y repite las lecturas contra la caché local.
        install(pe.bpm.core.offline.LocalFirstPlugin)
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        // Sin logging de cuerpos, tokens ni URL firmadas.
    }
