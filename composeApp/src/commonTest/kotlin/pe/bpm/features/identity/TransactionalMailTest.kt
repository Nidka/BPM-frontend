package pe.bpm.features.identity

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.RemoteIdentityRepository
import pe.bpm.features.identity.domain.*
import kotlin.test.*

/**
 * Correo propio de BPM (verificación y nueva contraseña): primero la API de BPM y, solo si no está disponible, el envío de
 * Firebase. Ver `specs/IDENTITY.md` y la especificación del backend `specs/MAIL.md`.
 */
class TransactionalMailTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val headers = headersOf("Content-Type", "application/json")
    private val credential = """{"idToken":"firebase-test-token","localId":"uid-test","displayName":"Persona"}"""
    private val verificationPath = "/api/v1/auth/email/verification"
    private val resetPath = "/api/v1/auth/email/password-reset"

    /** Servidor simulado: [backend] responde a las dos rutas nuevas; el resto es Firebase y se anota en [firebase]. */
    private class Harness(val backend: (String) -> Pair<HttpStatusCode, String>?) {
        val backendCalls = mutableListOf<String>()
        val firebase = mutableListOf<String>()
        var verificationAuthorization: String? = null
        var resetBody: String? = null
        var resetAuthorization: String? = "unset"
    }

    private fun client(h: Harness, credentialJson: String, jsonHeaders: Headers) = createHttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        if (request.url.host == "api.example.com") {
            h.backendCalls += path
            if (path.endsWith("/email/verification")) h.verificationAuthorization = request.headers["Authorization"]
            if (path.endsWith("/email/password-reset")) {
                h.resetBody = assertIs<TextContent>(request.body).text
                h.resetAuthorization = request.headers["Authorization"]
            }
            val answer = h.backend(path) ?: throw RuntimeException("sin conexión")
            respond(answer.second, answer.first, jsonHeaders)
        } else {
            h.firebase += path
            if (path == "/v1/accounts:sendOobCode") assertEquals("es", request.headers["X-Firebase-Locale"])
            respond(if (path == "/v1/accounts:sendOobCode") "{}" else credentialJson, HttpStatusCode.OK, jsonHeaders)
        }
    })

    private suspend fun registered(h: Harness, block: suspend (RemoteIdentityRepository) -> Unit) {
        val c = client(h, credential, headers)
        try {
            val repo = RemoteIdentityRepository(c, config)
            assertIs<IdentityResult.Success<Unit>>(repo.register("Persona Pruebas", "person@example.com", "password8"))
            block(repo)
        } finally { c.close() }
    }

    @Test fun verificationUsesBpmMailAndSkipsFirebaseWhenTheBackendAccepts() = runTest {
        val h = Harness { HttpStatusCode.Accepted to """{"data":{"status":"SENT"}}""" }
        registered(h) { repo ->
            assertEquals(listOf(verificationPath), h.backendCalls)
            assertEquals("Bearer firebase-test-token", h.verificationAuthorization)
            assertFalse("/v1/accounts:sendOobCode" in h.firebase, "no debe pedir además el correo de Firebase")
            assertIs<IdentityResult.Success<Unit>>(repo.resendVerification())
            assertEquals(2, h.backendCalls.size)
            assertFalse("/v1/accounts:sendOobCode" in h.firebase)
        }
    }

    @Test fun verificationFallsBackToFirebaseWhenBpmMailIsUnavailable() = runTest {
        val unavailable = """{"errors":[{"code":"MAIL_UNAVAILABLE","message":"x"}]}"""
        for (answer in listOf<(String) -> Pair<HttpStatusCode, String>?>(
            { HttpStatusCode.ServiceUnavailable to unavailable },
            { HttpStatusCode.NotFound to """{"errors":[{"code":"NOT_FOUND"}]}""" },
            { HttpStatusCode.MethodNotAllowed to "{}" },
            { HttpStatusCode.InternalServerError to "{}" },
            { null },
        )) {
            val h = Harness(answer)
            registered(h) { assertEquals(1, h.firebase.count { it == "/v1/accounts:sendOobCode" }) }
        }
    }

    @Test fun verificationRateLimitIsReportedAndDoesNotBypassThroughFirebase() = runTest {
        val h = Harness { HttpStatusCode.TooManyRequests to """{"errors":[{"code":"RATE_LIMITED"}]}""" }
        val c = client(h, credential, headers)
        try {
            val result = assertIs<IdentityResult.Failure>(RemoteIdentityRepository(c, config).register("Persona Pruebas", "person@example.com", "password8"))
            assertEquals("REGISTRATION_RATE_LIMITED", result.errors.single().code)
            assertFalse("/v1/accounts:sendOobCode" in h.firebase)
        } finally { c.close() }
    }

    @Test fun passwordResetUsesBpmMailWithOnlyTheEmailAndNoCredentials() = runTest {
        val h = Harness { HttpStatusCode.Accepted to """{"data":{"status":"ACCEPTED"}}""" }
        val c = client(h, credential, headers)
        try {
            assertIs<IdentityResult.Success<Unit>>(ResetPassword(RemoteIdentityRepository(c, config))("person@example.com"))
            assertEquals(listOf(resetPath), h.backendCalls)
            val body = Json.parseToJsonElement(h.resetBody!!).jsonObject
            assertEquals(setOf("email"), body.keys)
            assertEquals("person@example.com", body["email"]?.jsonPrimitive?.content)
            assertNull(h.resetAuthorization, "la recuperación es pública: no envía token")
            assertTrue(h.firebase.isEmpty(), "no debe llamar a Firebase si BPM lo envió")
        } finally { c.close() }
    }

    @Test fun passwordResetFallsBackToFirebaseAndKeepsSwallowingUnknownAccounts() = runTest {
        for (answer in listOf<(String) -> Pair<HttpStatusCode, String>?>(
            { HttpStatusCode.ServiceUnavailable to """{"errors":[{"code":"MAIL_UNAVAILABLE"}]}""" }, { HttpStatusCode.NotFound to "{}" }, { null },
        )) {
            val h = Harness(answer)
            val c = client(h, credential, headers)
            try {
                assertIs<IdentityResult.Success<Unit>>(ResetPassword(RemoteIdentityRepository(c, config))("person@example.com"))
                assertEquals(listOf("/v1/accounts:sendOobCode"), h.firebase)
            } finally { c.close() }
        }
    }

    @Test fun passwordResetReportsRateLimitAndInvalidEmailWithoutFallback() = runTest {
        for ((status, code) in listOf(HttpStatusCode.TooManyRequests to "RATE_LIMITED", HttpStatusCode.UnprocessableEntity to "INVALID_EMAIL")) {
            val h = Harness { status to """{"errors":[{"code":"X"}]}""" }
            val c = client(h, credential, headers)
            try {
                val result = assertIs<IdentityResult.Failure>(RemoteIdentityRepository(c, config).resetPassword("person@example.com"))
                assertEquals(code, result.errors.single().code)
                assertTrue(h.firebase.isEmpty())
            } finally { c.close() }
        }
    }
}
