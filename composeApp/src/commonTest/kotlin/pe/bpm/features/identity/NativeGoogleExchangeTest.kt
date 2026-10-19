package pe.bpm.features.identity

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.*
import pe.bpm.features.identity.domain.IdentityResult
import kotlin.test.*

class NativeGoogleExchangeTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val headers = headersOf("Content-Type", "application/json")
    private val nonce = "nonce-for-test-only-with-at-least-32-characters"
    private val googleToken = "google-id-token.payload-for-tests.signature"
    private fun provider(result: GoogleAuthResult) = object : GoogleAuthProvider {
        override val supported = true
        override suspend fun signIn() = result
        override fun clear() {}
    }

    @Test fun nativeGoogleCredentialIsExchangedBeforeBackendAuthorizationAndPersistsOnlyFirebaseRefresh() = runTest {
        val paths = mutableListOf<String>()
        var stored: String? = null
        val store = object : SessionStore {
            override fun read() = stored
            override fun write(value: String) { stored = value }
            override fun clear() { stored = null }
        }
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithIdp" -> {
                    val body = Json.parseToJsonElement(assertIs<TextContent>(request.body).text).jsonObject
                    assertEquals(setOf("postBody", "requestUri", "returnSecureToken"), body.keys)
                    val form = parseQueryString(body.getValue("postBody").jsonPrimitive.content)
                    assertEquals(googleToken, form["id_token"])
                    assertEquals(nonce, form["nonce"]); assertEquals("google.com", form["providerId"])
                    assertEquals("http://localhost", body.getValue("requestUri").jsonPrimitive.content)
                    assertTrue(body.getValue("returnSecureToken").jsonPrimitive.boolean)
                    respond("""{"idToken":"firebase-id-token","localId":"uid","displayName":"Persona Pruebas","refreshToken":"firebase-refresh","expiresIn":"3600"}""", HttpStatusCode.OK, headers)
                }
                "/api/v1/me" -> {
                    assertEquals("Bearer firebase-id-token", request.headers["Authorization"])
                    respond("""{"data":{"id":"uid","email":"persona@example.invalid","displayName":"Persona Pruebas","organizations":[]}}""", HttpStatusCode.OK, headers)
                }
                else -> error("No debe crear ni vincular cuentas automáticamente")
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config, provider(GoogleAuthResult.Federated(googleToken, nonce)), store)
            assertIs<IdentityResult.Success<*>>(repository.signInWithGoogle())
            assertEquals(listOf("/v1/accounts:signInWithIdp", "/api/v1/me"), paths)
            assertEquals("firebase-id-token", repository.sessionToken())
            assertTrue(stored.orEmpty().contains("firebase-refresh"))
            assertFalse(stored.orEmpty().contains(googleToken))
        } finally { client.close() }
    }

    @Test fun missingConfigAndUserCancellationNeverContactFirebaseOrBackend() = runTest {
        for (code in listOf("GOOGLE_NOT_CONFIGURED", "GOOGLE_CANCELLED")) {
            var calls = 0
            val client = createHttpClient(MockEngine { calls++; error("No se esperaba red") })
            try {
                val repository = RemoteIdentityRepository(client, config, provider(GoogleAuthResult.Failure(code)))
                val failure = assertIs<IdentityResult.Failure>(repository.signInWithGoogle())
                assertEquals(code, failure.errors.single().code); assertEquals(0, calls)
                assertNull(repository.sessionToken())
            } finally { client.close() }
        }
    }

    @Test fun accountLinkingAndBackendDenialDoNotCreateOrTrustAnUnconfirmedSession() = runTest {
        for (linking in listOf(true, false)) {
            var backendCalls = 0
            val client = createHttpClient(MockEngine { request ->
                if (request.url.host == "identitytoolkit.googleapis.com") {
                    respond(if (linking) """{"needConfirmation":true,"pendingToken":"do-not-link"}"""
                    else """{"idToken":"firebase-id-token","localId":"uid","refreshToken":"firebase-refresh"}""", HttpStatusCode.OK, headers)
                } else {
                    backendCalls++
                    assertEquals("/api/v1/me", request.url.encodedPath)
                    respond("""{"errors":[{"code":"ACCESS_DENIED"}]}""", HttpStatusCode.Forbidden, headers)
                }
            })
            try {
                val repository = RemoteIdentityRepository(client, config, provider(GoogleAuthResult.Federated(googleToken, nonce)))
                val failure = assertIs<IdentityResult.Failure>(repository.signInWithGoogle())
                assertEquals(if (linking) "ACCOUNT_CONFLICT" else "ACCESS_DENIED", failure.errors.single().code)
                assertEquals(if (linking) 0 else 1, backendCalls); assertNull(repository.sessionToken())
            } finally { client.close() }
        }
    }

    @Test fun logoutPreventsLateFederatedSelectionFromStartingAnExchange() = runTest {
        var calls = 0
        val selected = CompletableDeferred<Unit>()
        val google = object : GoogleAuthProvider {
            override val supported = true
            override suspend fun signIn(): GoogleAuthResult {
                withContext(NonCancellable) { selected.await() }
                return GoogleAuthResult.Federated(googleToken, nonce)
            }
            override fun clear() {}
        }
        val client = createHttpClient(MockEngine { calls++; error("No se esperaba red") })
        try {
            val repository = RemoteIdentityRepository(client, config, google)
            val pending = async { repository.signInWithGoogle() }
            yield(); repository.signOut(); selected.complete(Unit)
            assertIs<IdentityResult.Failure>(pending.await())
            assertEquals(0, calls); assertNull(repository.sessionToken())
        } finally { selected.complete(Unit); client.close() }
    }

    @Test fun existingEmailDuringGoogleLoginOffersOriginalSignInWithoutRegisteringAgain() = runTest {
        val client = createHttpClient(MockEngine { request ->
            assertEquals("/v1/accounts:signInWithIdp", request.url.encodedPath)
            respond("""{"error":{"message":"EMAIL_EXISTS"}}""", HttpStatusCode.BadRequest, headers)
        })
        try {
            val repository = RemoteIdentityRepository(client, config, provider(GoogleAuthResult.Federated(googleToken, nonce)))
            val failure = assertIs<IdentityResult.Failure>(repository.signInWithGoogle())
            assertEquals("ACCOUNT_CONFLICT", failure.errors.single().code)
            assertNull(repository.sessionToken())
        } finally { client.close() }
    }
}
