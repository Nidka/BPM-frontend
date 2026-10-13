package pe.bpm.features.identity

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.RemoteIdentityRepository
import pe.bpm.features.identity.data.GoogleAuthProvider
import pe.bpm.features.identity.data.GoogleAuthResult
import pe.bpm.features.identity.data.GoogleCredential
import pe.bpm.features.identity.domain.*
import kotlin.test.*

class IdentityTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val credential = """{"idToken":"test.id.token","localId":"uid-1"}"""
    private val profile = """{"data":{"id":"uid-1","email":"test@example.com","displayName":"Persona BPM","organizations":[]}}"""

    private fun google(result: GoogleAuthResult) = object : GoogleAuthProvider {
        override val supported = true
        override suspend fun signIn() = result
        override fun clear() {}
    }

    @Test
    fun googleUsesSameProtectedProfileEndpointAndStoresOnlyAcceptedToken() = runTest {
        var calls = 0
        val client = createHttpClient(MockEngine { request ->
            calls++
            assertEquals("api.example.com", request.url.host)
            assertEquals("/api/v1/me", request.url.encodedPath)
            assertEquals("Bearer firebase.google.test", request.headers["Authorization"])
            respond(profile, HttpStatusCode.OK, jsonHeaders)
        })
        try {
            val repository = RemoteIdentityRepository(client, config,
                google(GoogleAuthResult.Success(GoogleCredential("firebase.google.test", "uid-1"))))
            assertIs<IdentityResult.Success<Profile>>(SignInWithGoogle(repository)())
            assertIs<IdentityResult.Success<Profile>>(repository.currentProfile())
            repository.signOut()
            assertEquals("UNAUTHENTICATED", assertIs<IdentityResult.Failure>(repository.currentProfile()).errors.single().code)
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test
    fun googleDoesNotCreateSessionForUnregisteredOrMismatchedUid() = runTest {
        for ((status, body, expected) in listOf(
            Triple(HttpStatusCode.Forbidden, """{"errors":[{"code":"USER_NOT_REGISTERED"}]}""", "USER_NOT_REGISTERED"),
            Triple(HttpStatusCode.OK, profile.replace("uid-1", "other-uid"), "INVALID_RESPONSE"),
        )) {
            var calls = 0
            val client = createHttpClient(MockEngine { calls++; respond(body, status, jsonHeaders) })
            try {
                val repository = RemoteIdentityRepository(client, config,
                    google(GoogleAuthResult.Success(GoogleCredential("firebase.google.test", "uid-1"))))
                assertEquals(expected, assertIs<IdentityResult.Failure>(repository.signInWithGoogle()).errors.single().code)
                assertIs<IdentityResult.Failure>(repository.currentProfile())
                assertEquals(if (status == HttpStatusCode.Forbidden) 2 else 1, calls)
            } finally { client.close() }
        }
    }

    @Test
    fun googleCancellationDoesNotCallBackendOrPublishSession() = runTest {
        val client = createHttpClient(MockEngine { error("Must not call backend") })
        try {
            val repository = RemoteIdentityRepository(client, config, google(GoogleAuthResult.Failure("GOOGLE_CANCELLED")))
            val result = assertIs<IdentityResult.Failure>(repository.signInWithGoogle())
            assertEquals("GOOGLE_CANCELLED", result.errors.single().code)
            assertIs<IdentityResult.Failure>(repository.currentProfile())
        } finally { client.close() }
    }

    @Test
    fun validatesFieldsBeforeAnyNetworkRequest() = runTest {
        val client = createHttpClient(MockEngine { error("Must not call network") })
        try {
            val result = SignIn(RemoteIdentityRepository(client, config))("not-an-email", "")
            val errors = assertIs<IdentityResult.Failure>(result).errors
            assertEquals("VALIDATION_ERROR", errors.single().code)
            assertEquals(setOf("email", "password"), errors.single().fields.keys)
        } finally { client.close() }
    }

    @Test
    fun verifiesBackendBeforePublishingSessionAndClearsOnLogout() = runTest {
        var calls = 0
        val client = createHttpClient(MockEngine { request ->
            calls++
            if (request.url.host == "identitytoolkit.googleapis.com") {
                assertEquals("/v1/accounts:signInWithPassword", request.url.encodedPath)
                assertEquals(config.firebaseApiKey, request.url.parameters["key"])
                assertNull(request.headers["Authorization"])
                val body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                assertEquals("test@example.com", body["email"]?.jsonPrimitive?.content)
                assertEquals(" unchanged ", body["password"]?.jsonPrimitive?.content)
                assertEquals("true", body["returnSecureToken"]?.jsonPrimitive?.content)
                respond(credential, HttpStatusCode.OK, jsonHeaders)
            } else {
                assertEquals("/api/v1/me", request.url.encodedPath)
                assertEquals("Bearer test.id.token", request.headers["Authorization"])
                respond(profile, HttpStatusCode.OK, jsonHeaders)
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config)
            val result = assertIs<IdentityResult.Success<Profile>>(SignIn(repository)(" test@example.com ", " unchanged "))
            assertEquals("Persona BPM", result.value.displayName)
            assertEquals(2, calls)
            assertIs<IdentityResult.Success<Profile>>(repository.currentProfile())
            repository.signOut()
            assertEquals("UNAUTHENTICATED", assertIs<IdentityResult.Failure>(repository.currentProfile()).errors.single().code)
            assertEquals(3, calls)
        } finally { client.close() }
    }

    @Test
    fun rejectsValidFirebaseIdentityWhenUserIsNotEnabledInBackend() = runTest {
        val client = createHttpClient(MockEngine { request ->
            if (request.url.host == "identitytoolkit.googleapis.com") respond(credential, HttpStatusCode.OK, jsonHeaders)
            else respond("""{"errors":[{"code":"USER_NOT_REGISTERED","message":"internal secret must not leak"}]}""",
                HttpStatusCode.Forbidden, jsonHeaders)
        })
        try {
            val repository = RemoteIdentityRepository(client, config)
            val error = assertIs<IdentityResult.Failure>(repository.signIn("test@example.com", "password")).errors.single()
            assertEquals("USER_NOT_REGISTERED", error.code)
            assertFalse(error.message.contains("secret"))
            assertIs<IdentityResult.Failure>(repository.currentProfile())
        } finally { client.close() }
    }

    @Test
    fun credentialsErrorsNeverRevealWhetherAnEmailExists() = runTest {
        for (providerCode in listOf("EMAIL_NOT_FOUND", "INVALID_PASSWORD", "INVALID_LOGIN_CREDENTIALS", "USER_DISABLED")) {
            val client = createHttpClient(MockEngine {
                respond("""{"error":{"message":"$providerCode"}}""", HttpStatusCode.BadRequest, jsonHeaders)
            })
            try {
                val error = assertIs<IdentityResult.Failure>(RemoteIdentityRepository(client, config)
                    .signIn("test@example.com", "password")).errors.single()
                assertEquals("INVALID_CREDENTIALS", error.code)
                assertEquals("El correo o la contraseña no son correctos.", error.message)
            } finally { client.close() }
        }
    }

    @Test
    fun refusesLoginWhenFirebaseIsNotConfigured() = runTest {
        val client = createHttpClient(MockEngine { error("Must not call Firebase") })
        try {
            val result = RemoteIdentityRepository(client, AppConfig("https://api.example.com"))
                .signIn("test@example.com", "password")
            assertEquals("AUTH_NOT_CONFIGURED", assertIs<IdentityResult.Failure>(result).errors.single().code)
        } finally { client.close() }
    }

    @Test
    fun webLoadsPublicConfigurationWithoutSendingPasswordToProxy() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/config.json" -> respond("""{"firebaseApiKey":"public-key-for-tests-only"}""", HttpStatusCode.OK, jsonHeaders)
                "/v1/accounts:signInWithPassword" -> respond(credential, HttpStatusCode.OK, jsonHeaders)
                else -> respond(profile, HttpStatusCode.OK, jsonHeaders)
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, AppConfig("https://api.example.com/backend",
                publicConfigUrl = "https://api.example.com/config.json"))
            assertIs<IdentityResult.Success<Profile>>(repository.signIn("test@example.com", "password"))
            assertEquals(listOf("/config.json", "/v1/accounts:signInWithPassword", "/backend/api/v1/me"), paths)
        } finally { client.close() }
    }

    @Test
    fun rejectsMalformedOrMismatchedProfile() = runTest {
        for (body in listOf("{}", profile.replace("uid-1", "other-uid"))) {
            val client = createHttpClient(MockEngine { request ->
                respond(if (request.url.host == "identitytoolkit.googleapis.com") credential else body,
                    HttpStatusCode.OK, jsonHeaders)
            })
            try {
                val repository = RemoteIdentityRepository(client, config)
                assertEquals("INVALID_RESPONSE", assertIs<IdentityResult.Failure>(repository.signIn("test@example.com", "password"))
                    .errors.single().code)
                assertIs<IdentityResult.Failure>(repository.currentProfile())
            } finally { client.close() }
        }
    }

    @Test
    fun cancellationDoesNotBecomeAnAuthenticationError() = runTest {
        val client = createHttpClient(MockEngine { throw CancellationException("cancelled") })
        try {
            assertFailsWith<CancellationException> {
                RemoteIdentityRepository(client, config).signIn("test@example.com", "password")
            }
        } finally { client.close() }
    }

    @Test
    fun expiredSessionIsDiscardedAndNotSentAgain() = runTest {
        var profileCalls = 0
        val client = createHttpClient(MockEngine { request ->
            if (request.url.host == "identitytoolkit.googleapis.com") respond(credential, HttpStatusCode.OK, jsonHeaders)
            else if (++profileCalls == 1) respond(profile, HttpStatusCode.OK, jsonHeaders)
            else respond("""{"errors":[{"code":"UNAUTHENTICATED"}]}""", HttpStatusCode.Unauthorized, jsonHeaders)
        })
        try {
            val repository = RemoteIdentityRepository(client, config)
            assertIs<IdentityResult.Success<Profile>>(repository.signIn("test@example.com", "password"))
            assertEquals("UNAUTHENTICATED", assertIs<IdentityResult.Failure>(repository.currentProfile()).errors.single().code)
            assertIs<IdentityResult.Failure>(repository.currentProfile())
            assertEquals(2, profileCalls)
        } finally { client.close() }
    }

    @Test
    fun backendWithFirebaseDisabledNeverCreatesSession() = runTest {
        val client = createHttpClient(MockEngine { request ->
            if (request.url.host == "identitytoolkit.googleapis.com") respond(credential, HttpStatusCode.OK, jsonHeaders)
            else respond("""{"errors":[{"code":"AUTHENTICATION_UNAVAILABLE"}]}""", HttpStatusCode.ServiceUnavailable, jsonHeaders)
        })
        try {
            val repository = RemoteIdentityRepository(client, config)
            assertEquals("AUTH_NOT_CONFIGURED", assertIs<IdentityResult.Failure>(repository.signIn("test@example.com", "password"))
                .errors.single().code)
            assertIs<IdentityResult.Failure>(repository.currentProfile())
        } finally { client.close() }
    }
}
