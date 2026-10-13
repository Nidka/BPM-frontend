package pe.bpm.features.connection

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.AppFailure
import pe.bpm.core.domain.AppResult
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.connection.data.RemoteConnectionRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith

class ConnectionRepositoryTest {
    private val config = AppConfig("https://api.example.com")

    @Test
    fun verifiesBothBackendAndDatabase() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond(if (paths.size == 1) """{"status":"ok","version":"abc123"}""" else """{"status":"ready"}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val result = RemoteConnectionRepository(client, config).check()
            assertEquals(listOf("/health", "/ready"), paths)
            assertEquals("abc123", assertIs<AppResult.Success<*>>(result).let {
                (it.value as pe.bpm.features.connection.domain.ConnectionStatus).backendVersion
            })
        } finally { client.close() }
    }

    @Test
    fun neverClaimsSuccessWhenDatabaseIsUnavailable() = runTest {
        val client = createHttpClient(MockEngine { request ->
            respond(if (request.url.encodedPath == "/health") """{"status":"ok","version":"abc"}""" else "{}",
                if (request.url.encodedPath == "/health") HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                headersOf("Content-Type", "application/json"))
        })
        try {
            assertEquals(AppResult.Failure(AppFailure.UNAVAILABLE), RemoteConnectionRepository(client, config).check())
        } finally { client.close() }
    }

    @Test
    fun rejectsMalformedSuccessResponse() = runTest {
        val client = createHttpClient(MockEngine {
            respond("""{"unexpected":true}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            assertEquals(AppResult.Failure(AppFailure.INVALID_RESPONSE), RemoteConnectionRepository(client, config).check())
        } finally { client.close() }
    }

    @Test
    fun cancellationIsPropagated() = runTest {
        val client = createHttpClient(MockEngine { throw CancellationException("cancelled") })
        try {
            assertFailsWith<CancellationException> { RemoteConnectionRepository(client, config).check() }
        } finally { client.close() }
    }
}
