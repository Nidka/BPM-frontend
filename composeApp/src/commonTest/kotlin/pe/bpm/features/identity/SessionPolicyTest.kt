package pe.bpm.features.identity

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.identity.data.*
import pe.bpm.features.identity.domain.*
import pe.bpm.features.identity.presentation.IdentityViewModel
import kotlin.test.*

class SessionPolicyTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val credential = """{"idToken":"access","refreshToken":"refresh","localId":"uid","expiresIn":"3600"}"""
    private val profile = """{"data":{"id":"uid","email":"p@example.com","displayName":"Persona","organizations":[]}}"""
    private fun headers(idle: Long = 1900) = headersOf(
        "Content-Type" to listOf("application/json"),
        "X-BPM-Session-Expires-At" to listOf("29800"),
        "X-BPM-Session-Idle-Expires-At" to listOf(idle.toString()),
    )

    @Test fun activityIsThrottledAndDoesNotReopenAnExpiredContext() = runTest {
        var now = 1000L
        var activities = 0
        val client = createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" -> respond(credential, HttpStatusCode.OK, headers())
                "/api/v1/auth/activity" -> {
                    activities++
                    if (activities == 2) respond("""{"errors":[{"code":"SESSION_EXPIRED"}]}""", HttpStatusCode.Unauthorized, headers())
                    else respond("""{"data":{"status":"ACTIVE"}}""", HttpStatusCode.OK, headers(1960))
                }
                else -> respond(profile, HttpStatusCode.OK, headers())
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config, nowSeconds = { now })
            assertIs<IdentityResult.Success<Profile>>(repository.signIn("p@example.com", "password"))
            assertEquals(1900L, repository.sessionWindow().first()!!.idleExpiresAt)
            assertIs<IdentityResult.Success<Unit>>(repository.recordActivity())
            repeat(10) { repository.recordActivity() }
            assertEquals(1, activities)
            assertEquals(1960L, repository.sessionWindow().first()!!.idleExpiresAt)
            now += 60
            val expired = assertIs<IdentityResult.Failure>(repository.recordActivity())
            assertEquals("SESSION_EXPIRED", expired.errors.single().code)
            assertNull(repository.sessionToken())
            assertNull(repository.sessionWindow().first())
        } finally { client.close() }
    }

    @Test fun logoutErasesLocalCredentialsBeforeWaitingForServer() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val client = createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" -> respond(credential, HttpStatusCode.OK, headers())
                "/api/v1/auth/logout" -> {
                    assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
                    entered.complete(Unit); release.await()
                    respond("""{"data":{"status":"SIGNED_OUT"}}""", HttpStatusCode.OK, headers())
                }
                else -> respond(profile, HttpStatusCode.OK, headers())
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config)
            assertIs<IdentityResult.Success<Profile>>(repository.signIn("p@example.com", "password"))
            val logout = async { repository.endSession() }
            entered.await()
            assertNull(repository.sessionToken()); assertNull(repository.sessionWindow().first())
            release.complete(Unit)
            assertIs<IdentityResult.Success<Unit>>(logout.await())
        } finally { release.complete(Unit); client.close() }
    }

    @Test fun expiredRestorationCannotLeaveRefreshCredentialsBehind() = runTest {
        val store = object : SessionStore {
            var value: String? = """{"origin":"https://api.example.com","uid":"uid","refreshToken":"refresh"}"""
            override fun read() = value
            override fun write(value: String) { this.value = value }
            override fun clear() { value = null }
        }
        val client = createHttpClient(MockEngine { request ->
            if (request.url.host == "securetoken.googleapis.com") respond("""{"id_token":"new","refresh_token":"next","user_id":"uid","expires_in":"3600"}""", HttpStatusCode.OK, headers())
            else respond("""{"errors":[{"code":"SESSION_EXPIRED"}]}""", HttpStatusCode.Unauthorized, headers())
        })
        try {
            val repository = RemoteIdentityRepository(client, config, sessionStore = store)
            val failure = assertIs<IdentityResult.Failure>(repository.restoreSession())
            assertEquals("SESSION_EXPIRED", failure.errors.single().code)
            assertNull(store.value); assertNull(repository.sessionToken())
        } finally { client.close() }
    }

    @Test fun invalidIdTokenDoesNotPretendRemoteLogoutWasConfirmed() = runTest {
        val client = createHttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/accounts:signInWithPassword" -> respond(credential, HttpStatusCode.OK, headers())
                "/api/v1/auth/logout" -> respond("""{"errors":[{"code":"UNAUTHENTICATED"}]}""", HttpStatusCode.Unauthorized, headers())
                else -> respond(profile, HttpStatusCode.OK, headers())
            }
        })
        try {
            val repository = RemoteIdentityRepository(client, config)
            assertIs<IdentityResult.Success<Profile>>(repository.signIn("p@example.com", "password"))
            assertIs<IdentityResult.Failure>(repository.endSession())
            assertNull(repository.sessionToken())
        } finally { client.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sessionWarnsThenLocksWithoutBackgroundActivity() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val client = createHttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                if (request.url.host == "identitytoolkit.googleapis.com") respond(credential, HttpStatusCode.OK, headers(1060))
                else respond(profile, HttpStatusCode.OK, headers(1060))
            }
        }))
        val store = ViewModelStore()
        try {
            val repository = RemoteIdentityRepository(client, config, nowSeconds = { 1000 + testScheduler.currentTime / 1000 })
            val vm = IdentityViewModel(SignIn(repository), GetCurrentProfile(repository), SignOut(repository),
                SignInWithGoogle(repository), false, Register(repository), ResetPassword(repository), ResendVerification(repository),
                observeWindow = ObserveSessionWindow(repository), nowSeconds = { 1000 + testScheduler.currentTime / 1000 })
            store.put("identity", vm)
            vm.login("p@example.com", "password"); runCurrent()
            assertNotNull(vm.state.value.profile)
            advanceTimeBy(5_000); runCurrent()
            assertNotNull(vm.state.value.notice)
            advanceTimeBy(55_000); runCurrent()
            assertNull(vm.state.value.profile)
            assertNull(repository.sessionToken())
            assertEquals("SESSION_EXPIRED", vm.state.value.errors.single().code)
        } finally { store.clear(); client.close(); Dispatchers.resetMain() }
    }
}
