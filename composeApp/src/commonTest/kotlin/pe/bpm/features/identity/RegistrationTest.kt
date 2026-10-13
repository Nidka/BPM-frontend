package pe.bpm.features.identity

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.RemoteIdentityRepository
import pe.bpm.features.identity.data.GoogleAuthProvider
import pe.bpm.features.identity.data.GoogleAuthResult
import pe.bpm.features.identity.data.GoogleCredential
import pe.bpm.features.identity.domain.*
import kotlin.test.*

class RegistrationTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val headers = headersOf("Content-Type", "application/json")
    private val credential = """{"idToken":"firebase-test-token","localId":"uid-test","displayName":"Persona"}"""

    @Test fun newGoogleIdentityEnrollsOnceWithVerifiedBackendProfileOnly() = runTest {
        var registrations = 0
        val client = createHttpClient(MockEngine { request ->
            assertEquals("api.example.com", request.url.host)
            assertEquals("Bearer firebase-google-token", request.headers["Authorization"])
            if (request.url.encodedPath == "/api/v1/me" && registrations == 0)
                respond("""{"errors":[{"code":"USER_NOT_REGISTERED"}]}""", HttpStatusCode.Forbidden, headers)
            else {
                if (request.url.encodedPath == "/api/v1/auth/register") {
                    registrations++
                    assertEquals("""{"displayName":"Usuario BPM"}""", assertIs<TextContent>(request.body).text)
                }
                respond("""{"data":{"id":"new-google-uid","email":"person@example.com","displayName":"Usuario BPM","organizations":[]}}""", HttpStatusCode.OK, headers)
            }
        })
        val google = object : GoogleAuthProvider {
            override val supported = true
            override suspend fun signIn() = GoogleAuthResult.Success(GoogleCredential("firebase-google-token", "new-google-uid", "X"))
            override fun clear() {}
        }
        try {
            val repo = RemoteIdentityRepository(client, config, google)
            assertIs<IdentityResult.Success<Profile>>(repo.signInWithGoogle())
            assertIs<IdentityResult.Success<Profile>>(repo.currentProfile())
            assertEquals(1, registrations)
        } finally { client.close() }
    }

    @Test fun unknownForbiddenNeverTriggersAutomaticRegistration() = runTest {
        var calls = 0
        val client = createHttpClient(MockEngine { request ->
            calls++
            if (request.url.host == "identitytoolkit.googleapis.com") respond(credential, HttpStatusCode.OK, headers)
            else {
                assertEquals("/api/v1/me", request.url.encodedPath)
                respond("""{"errors":[{"code":"FORBIDDEN","message":"private SQL details"}]}""", HttpStatusCode.Forbidden, headers)
            }
        })
        try {
            val repo = RemoteIdentityRepository(client, config)
            val result = assertIs<IdentityResult.Failure>(repo.signIn("person@example.com", "password8"))
            assertEquals("ACCESS_DENIED", result.errors.single().code)
            assertFalse(result.errors.single().message.contains("SQL"))
            assertIs<IdentityResult.Failure>(repo.currentProfile())
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun partialCreationPreservesVerificationWithoutRetryingSignup() = runTest {
        for (failedStep in listOf("/v1/accounts:update", "/v1/accounts:sendOobCode")) {
            var failed = false
            var creations = 0
            val client = createHttpClient(MockEngine { request ->
                if (request.url.encodedPath == "/v1/accounts:signUp") creations++
                if (request.url.encodedPath == failedStep && !failed) {
                    failed = true
                    respond("{}", HttpStatusCode.ServiceUnavailable, headers)
                } else respond(credential, HttpStatusCode.OK, headers)
            })
            try {
                val repo = RemoteIdentityRepository(client, config)
                val result = assertIs<IdentityResult.Failure>(repo.register("Persona", "person@example.com", "password8"))
                assertEquals("REGISTRATION_INCOMPLETE", result.errors.single().code)
                assertIs<IdentityResult.Failure>(repo.currentProfile())
                assertIs<IdentityResult.Success<Unit>>(repo.resendVerification())
                assertEquals(1, creations)
            } finally { client.close() }
        }
    }

    @Test fun registrationValidatesBeforeCreatingFirebaseAccount() = runTest {
        val client = createHttpClient(MockEngine { error("No network expected") })
        try {
            val result = Register(RemoteIdentityRepository(client, config))("", "bad", "short", "different")
            assertEquals(setOf("displayName", "email", "password", "confirmPassword"), assertIs<IdentityResult.Failure>(result).errors.single().fields.keys)
        } finally { client.close() }
    }

    @Test fun signupUpdatesNameAndSendsVerificationWithoutPublishingSession() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            assertEquals("identitytoolkit.googleapis.com", request.url.host)
            paths += request.url.encodedPath
            val body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
            when (request.url.encodedPath) {
                "/v1/accounts:signUp" -> assertEquals("true", body["returnSecureToken"]?.jsonPrimitive?.content)
                "/v1/accounts:update" -> { assertEquals("Persona", body["displayName"]?.jsonPrimitive?.content); assertFalse(body.containsKey("password")) }
                "/v1/accounts:sendOobCode" -> { assertEquals("VERIFY_EMAIL", body["requestType"]?.jsonPrimitive?.content); assertEquals("es", request.headers["X-Firebase-Locale"]) }
                else -> error("Unexpected request")
            }
            respond(credential, HttpStatusCode.OK, headers)
        })
        try {
            val repo = RemoteIdentityRepository(client, config)
            assertIs<IdentityResult.Success<Unit>>(Register(repo)("Persona", "person@example.com", "password8", "password8"))
            assertEquals(listOf("/v1/accounts:signUp", "/v1/accounts:update", "/v1/accounts:sendOobCode"), paths)
            assertIs<IdentityResult.Failure>(repo.currentProfile())
            assertIs<IdentityResult.Success<Unit>>(repo.resendVerification())
            repo.signOut()
            assertIs<IdentityResult.Failure>(repo.resendVerification())
        } finally { client.close() }
    }

    @Test fun verifiedLoginCompletesBackendRegistrationWithTokenAndNameOnly() = runTest {
        val paths = mutableListOf<String>()
        val profile = """{"data":{"id":"uid-test","email":"person@example.com","displayName":"Persona","organizations":[]}}"""
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" -> respond(credential, HttpStatusCode.OK, headers)
                "/api/v1/me" -> respond("""{"errors":[{"code":"USER_NOT_REGISTERED"}]}""", HttpStatusCode.Forbidden, headers)
                "/api/v1/auth/register" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("Bearer firebase-test-token", request.headers["Authorization"])
                    val body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                    assertEquals(setOf("displayName"), body.keys)
                    respond(profile, HttpStatusCode.OK, headers)
                }
                else -> error("Unexpected request")
            }
        })
        try {
            assertIs<IdentityResult.Success<Profile>>(RemoteIdentityRepository(client, config).signIn("person@example.com", "password8"))
            assertEquals(listOf("/v1/accounts:signInWithPassword", "/api/v1/me", "/api/v1/auth/register"), paths)
        } finally { client.close() }
    }

    @Test fun unverifiedLoginOffersResendButNeverCreatesSession() = runTest {
        val client = createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" -> respond(credential, HttpStatusCode.OK, headers)
                "/api/v1/me" -> respond("""{"errors":[{"code":"USER_NOT_REGISTERED"}]}""", HttpStatusCode.Forbidden, headers)
                "/api/v1/auth/register" -> respond("""{"errors":[{"code":"EMAIL_NOT_VERIFIED"}]}""", HttpStatusCode.Forbidden, headers)
                else -> respond("{}", HttpStatusCode.OK, headers)
            }
        })
        try {
            val repo = RemoteIdentityRepository(client, config)
            assertEquals("EMAIL_NOT_VERIFIED", assertIs<IdentityResult.Failure>(repo.signIn("person@example.com", "password8")).errors.single().code)
            assertIs<IdentityResult.Failure>(repo.currentProfile())
            assertIs<IdentityResult.Success<Unit>>(repo.resendVerification())
        } finally { client.close() }
    }

    @Test fun passwordRecoveryDoesNotRevealWhetherEmailExists() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.BadRequest)) {
            val client = createHttpClient(MockEngine { request ->
                assertEquals("identitytoolkit.googleapis.com", request.url.host)
                respond(if (status == HttpStatusCode.OK) "{}" else """{"error":{"message":"EMAIL_NOT_FOUND"}}""", status, headers)
            })
            try { assertIs<IdentityResult.Success<Unit>>(ResetPassword(RemoteIdentityRepository(client, config))("person@example.com")) }
            finally { client.close() }
        }
    }
}
