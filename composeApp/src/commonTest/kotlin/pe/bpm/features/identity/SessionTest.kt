package pe.bpm.features.identity

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.*
import pe.bpm.features.identity.domain.*
import pe.bpm.features.identity.presentation.IdentityViewModel
import kotlin.test.*

class SessionTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val headers = headersOf("Content-Type", "application/json")
    private val credential = """{"idToken":"access-first","refreshToken":"refresh-first","localId":"uid-1","expiresIn":"3600"}"""
    private val renewed = """{"id_token":"access-next","refresh_token":"refresh-next","user_id":"uid-1","expires_in":"3600"}"""
    private val profile = """{"data":{"id":"uid-1","email":"test@example.com","displayName":"Persona BPM","organizations":[]}}"""

    private class Store : SessionStore {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    @Test fun concurrentCallsRefreshOnceAndRotateThePersistedCredential() = runTest {
        var now = 1000L
        var refreshes = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store()
        val client = createHttpClient(MockEngine { request ->
            when (request.url.host) {
                "identitytoolkit.googleapis.com" -> respond(credential, HttpStatusCode.OK, headers)
                "securetoken.googleapis.com" -> {
                    refreshes++
                    assertEquals("/v1/token", request.url.encodedPath)
                    assertEquals(config.firebaseApiKey, request.url.parameters["key"])
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertEquals(ContentType.Application.FormUrlEncoded, request.body.contentType?.withoutParameters())
                    assertEquals("grant_type=refresh_token&refresh_token=refresh-first", request.body.toByteArray().decodeToString())
                    entered.complete(Unit); release.await()
                    respond(renewed, HttpStatusCode.OK, headers)
                }
                else -> respond(profile, HttpStatusCode.OK, headers)
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config, sessionStore = store, nowSeconds = { now })
            assertIs<IdentityResult.Success<Profile>>(repository.signIn("test@example.com", "password"))
            assertTrue(store.value!!.contains("refresh-first"))
            assertFalse(store.value!!.contains("access-first"))
            assertFalse(store.value!!.contains("password"))
            now += 3541
            val first = async { repository.validSessionToken() }
            entered.await()
            val second = async { repository.validSessionToken() }
            release.complete(Unit)
            assertEquals(listOf("access-next", "access-next"), listOf(first.await(), second.await()))
            assertEquals(1, refreshes)
            assertTrue(store.value!!.contains("refresh-next"))
            assertFalse(store.value!!.contains("refresh-first"))
        } finally { release.complete(Unit); client.close() }
    }

    @Test fun restartRestoresOnlyAfterRefreshingAndConfirmingBackendProfile() = runTest {
        val store = Store()
        val calls = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            calls += request.url.encodedPath
            when (request.url.host) {
                "identitytoolkit.googleapis.com" -> respond(credential, HttpStatusCode.OK, headers)
                "securetoken.googleapis.com" -> respond(renewed, HttpStatusCode.OK, headers)
                else -> {
                    if (calls.contains("/v1/token")) assertEquals("Bearer access-next", request.headers[HttpHeaders.Authorization])
                    respond(profile, HttpStatusCode.OK, headers)
                }
            }
        })
        try {
            val first = RemoteIdentityRepository(client, config, sessionStore = store)
            assertIs<IdentityResult.Success<Profile>>(first.signIn("test@example.com", "password"))
            first.close()
            assertNull(first.sessionToken())
            assertNotNull(store.value)
            calls.clear()
            val restored = RemoteIdentityRepository(client, config, sessionStore = store)
            assertNull(restored.sessionToken())
            assertIs<IdentityResult.Success<Profile>>(restored.restoreSession())
            assertEquals(listOf("/v1/token", "/api/v1/me"), calls)
            assertEquals("access-next", restored.sessionToken())
            restored.signOut()
            assertNull(store.value)
            assertIs<IdentityResult.Failure>(restored.restoreSession())
        } finally { client.close() }
    }

    @Test fun failedBackendValidationNeverPersistsAnIdentity() = runTest {
        val store = Store()
        val client = createHttpClient(MockEngine { request ->
            if (request.url.host == "identitytoolkit.googleapis.com") respond(credential, HttpStatusCode.OK, headers)
            else respond("""{"errors":[{"code":"USER_INACTIVE"}]}""", HttpStatusCode.Forbidden, headers)
        })
        try {
            val repository = RemoteIdentityRepository(client, config, sessionStore = store)
            assertIs<IdentityResult.Failure>(repository.signIn("test@example.com", "password"))
            assertNull(store.value)
            assertNull(repository.sessionToken())
        } finally { client.close() }
    }

    @Test fun transientRefreshFailurePreservesCredentialsAndCanRetry() = runTest {
        var now = 1000L
        var failRefresh = true
        val store = Store()
        val client = createHttpClient(MockEngine { request ->
            when (request.url.host) {
                "identitytoolkit.googleapis.com" -> respond(credential, HttpStatusCode.OK, headers)
                "securetoken.googleapis.com" -> if (failRefresh) respond("{}", HttpStatusCode.ServiceUnavailable, headers) else respond(renewed, HttpStatusCode.OK, headers)
                else -> respond(profile, HttpStatusCode.OK, headers)
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config, sessionStore = store, nowSeconds = { now })
            repository.signIn("test@example.com", "password")
            now += 3600
            assertFalse(assertFailsWith<SessionAccessException> { repository.validSessionToken() }.expired)
            assertNotNull(store.value)
            failRefresh = false
            assertEquals("access-next", repository.validSessionToken())
        } finally { client.close() }
    }

    @Test fun revokedRefreshCredentialEndsAndErasesTheSession() = runTest {
        var now = 1000L
        val store = Store()
        val client = createHttpClient(MockEngine { request ->
            when (request.url.host) {
                "identitytoolkit.googleapis.com" -> respond(credential, HttpStatusCode.OK, headers)
                "securetoken.googleapis.com" -> respond("""{"error":{"message":"INVALID_REFRESH_TOKEN"}}""", HttpStatusCode.BadRequest, headers)
                else -> respond(profile, HttpStatusCode.OK, headers)
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config, sessionStore = store, nowSeconds = { now })
            repository.signIn("test@example.com", "password")
            now += 3600
            assertTrue(assertFailsWith<SessionAccessException> { repository.validSessionToken() }.expired)
            assertNull(repository.sessionToken())
            assertNull(store.value)
        } finally { client.close() }
    }

    @Test fun logoutDuringRefreshCannotRecreateTheSession() = runTest {
        var now = 1000L
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = Store()
        val client = createHttpClient(MockEngine { request ->
            when (request.url.host) {
                "identitytoolkit.googleapis.com" -> respond(credential, HttpStatusCode.OK, headers)
                "securetoken.googleapis.com" -> {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    respond(renewed, HttpStatusCode.OK, headers)
                }
                else -> respond(profile, HttpStatusCode.OK, headers)
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config, sessionStore = store, nowSeconds = { now })
            repository.signIn("test@example.com", "password")
            now += 3600
            val refresh = async { runCatching { repository.validSessionToken() } }
            entered.await()
            repository.signOut()
            release.complete(Unit)
            assertIs<SessionAccessException>(refresh.await().exceptionOrNull())
            assertNull(repository.sessionToken())
            assertNull(store.value)
        } finally { release.complete(Unit); client.close() }
    }

    @Test fun restorationRejectsAnotherApiOriginBeforeSendingCredentials() = runTest {
        val store = Store()
        store.value = """{"origin":"https://another.example.com","uid":"uid-1","refreshToken":"refresh"}"""
        val client = createHttpClient(MockEngine { error("Must not send a credential to another environment") })
        try {
            assertIs<IdentityResult.Failure>(RemoteIdentityRepository(client, config, sessionStore = store).restoreSession())
            assertNull(store.value)
        } finally { client.close() }
    }

    @Test fun restoredUidMustMatchBothFirebaseAndBackend() = runTest {
        for (mismatchAtFirebase in listOf(true, false)) {
            val store = Store()
            store.value = """{"origin":"https://api.example.com","uid":"uid-1","refreshToken":"refresh"}"""
            val client = createHttpClient(MockEngine { request ->
                if (request.url.host == "securetoken.googleapis.com") respond(if (mismatchAtFirebase) renewed.replace("uid-1", "another") else renewed, HttpStatusCode.OK, headers)
                else respond(profile.replace("uid-1", "another"), HttpStatusCode.OK, headers)
            })
            try {
                val repository = RemoteIdentityRepository(client, config, sessionStore = store)
                assertIs<IdentityResult.Failure>(repository.restoreSession())
                assertNull(repository.sessionToken())
                assertNull(store.value)
            } finally { client.close() }
        }
    }

    @Test fun browserStorageNeverPersistsRefreshCredentials() {
        val store = EphemeralSessionStore()
        store.write("refresh-secret")
        assertNull(store.read())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun apiRejectionClearsTheVisibleSessionButAnOldTokenCannotRejectANewerSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModels = ViewModelStore()
        val store = Store()
        val client = createHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                respond(if (request.url.host == "identitytoolkit.googleapis.com") credential else profile, HttpStatusCode.OK, headers)
            }
        }))
        try {
            val repository = RemoteIdentityRepository(client, config, sessionStore = store)
            val model = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), false, Register(repository), ResetPassword(repository), ResendVerification(repository), ConfirmEmailVerification(repository),
                RestoreSession(repository), ObserveSessionInvalidations(repository))
            viewModels.put("identity", model)
            runCurrent()
            assertFalse(model.state.value.restoring)
            model.login("test@example.com", "password")
            runCurrent()
            assertNotNull(model.state.value.profile)
            repository.rejectSession("a-token-from-a-previous-request")
            runCurrent()
            assertNotNull(model.state.value.profile)
            repository.rejectSession("access-first")
            runCurrent()
            assertNull(model.state.value.profile)
            assertNull(store.value)
            assertEquals("UNAUTHENTICATED", model.state.value.errors.single().code)
        } finally {
            viewModels.clear()
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun wasmStyleFetchErrorsAreSanitizedAndDoNotCreateASession() = runTest {
        val client = createHttpClient(MockEngine { throw Error("Fail to fetch: private provider detail") })
        try {
            val repository = RemoteIdentityRepository(client, config)
            val failure = assertIs<IdentityResult.Failure>(repository.signIn("test@example.com", "password"))
            assertEquals("NETWORK_ERROR", failure.errors.single().code)
            assertFalse(failure.toString().contains("private provider"))
            assertNull(repository.sessionToken())
        } finally { client.close() }
    }

}
