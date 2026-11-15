package pe.bpm.features.identity

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.offline.OfflineHarness
import pe.bpm.core.offline.OfflineScope
import pe.bpm.core.offline.RuntimeOfflineIdentity
import pe.bpm.features.identity.data.RemoteIdentityRepository
import pe.bpm.features.identity.data.SessionStore
import pe.bpm.features.identity.domain.IdentityResult
import pe.bpm.features.identity.domain.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inicio sin conexión con una sesión ya verificada en el dispositivo, sin debilitar la seguridad. */
class OfflineSessionTest {
    private val config = AppConfig("https://api.example.com", "public-key-for-tests-only")
    private val json = headersOf("Content-Type", "application/json")
    private val credential = """{"idToken":"access-first","refreshToken":"refresh-first","localId":"uid-1","expiresIn":"3600"}"""
    private val renewed = """{"id_token":"access-next","refresh_token":"refresh-next","user_id":"uid-1","expires_in":"3600"}"""
    private val profile = """{"data":{"id":"uid-1","email":"a@b.com","displayName":"Persona BPM","organizations":[{"id":"org1","legalName":"Cocina","role":"CHEF"}]}}"""

    private class Store : SessionStore {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    private class Rig(val h: OfflineHarness, val config: AppConfig) {
        var network = true
        var denied = false
        val store = Store()
        var now = 1_000L
    }

    private fun client(rig: Rig, credential: String, renewed: String, profile: String) = createHttpClient(MockEngine { request ->
        if (!rig.network) throw kotlinx.io.IOException("sin red")
        when (request.url.host) {
            "identitytoolkit.googleapis.com" -> respond(credential, HttpStatusCode.OK, json)
            "securetoken.googleapis.com" ->
                if (rig.denied) respond("""{"error":{"message":"TOKEN_EXPIRED"}}""", HttpStatusCode.BadRequest, json) else respond(renewed, HttpStatusCode.OK, json)
            else -> respond(profile, HttpStatusCode.OK, json)
        }
    })

    private fun repo(rig: Rig, client: io.ktor.client.HttpClient) = RemoteIdentityRepository(client, config, sessionStore = rig.store,
        nowSeconds = { rig.now }, offline = RuntimeOfflineIdentity(rig.h.runtime, config.apiBaseUrl))

    private suspend fun signedInRig(): Pair<Rig, io.ktor.client.HttpClient> {
        val rig = Rig(OfflineHarness(now = 1_000_000), config)
        val client = client(rig, credential, renewed, profile)
        assertIs<IdentityResult.Success<Profile>>(repo(rig, client).signIn("a@b.com", "password"))
        return rig to client
    }

    @Test fun signInSavesVerifiedProfileAndActivatesTheAccountScope() = runTest {
        val (rig, client) = signedInRig()
        assertEquals(OfflineScope(config.apiBaseUrl, "uid-1"), rig.h.runtime.active.current)
        val saved = rig.h.runtime.store.get("profile", "me")
        assertNotNull(saved)
        // Nunca se guardan tokens ni contraseñas en la caché del perfil.
        assertTrue(listOf("access-", "refresh-", "password").none { saved.json.contains(it) })
        client.close()
    }

    @Test fun restartWithoutNetworkOpensWithLastVerifiedProfileAndRenewsOnReconnect() = runTest {
        val (rig, client) = signedInRig()
        rig.h.runtime.deactivate()
        rig.network = false
        val restarted = repo(rig, client)
        val restored = assertIs<IdentityResult.Success<Profile>>(restarted.restoreSession())
        assertEquals("Persona BPM", restored.value.displayName)
        assertEquals("org1", restored.value.organizations.single().id)
        assertNull(restarted.sessionToken())
        assertNotNull(rig.h.runtime.active.current)
        // Al volver la red, el token se renueva con la credencial de renovación guardada.
        rig.network = true
        assertEquals("access-next", restarted.validSessionToken())
        client.close()
    }

    @Test fun deniedSessionIsNotRescuedByTheOfflineCache() = runTest {
        val (rig, client) = signedInRig()
        rig.denied = true
        val result = repo(rig, client).restoreSession()
        assertIs<IdentityResult.Failure>(result)
        // Un rechazo del servidor no abre la sesión local y oculta los datos.
        assertNull(rig.h.runtime.active.current)
        client.close()
    }

    @Test fun cachedProfileOlderThanTheLimitIsNotUsed() = runTest {
        val (rig, client) = signedInRig()
        rig.h.runtime.deactivate()
        rig.network = false
        rig.now = 1_000L + RuntimeOfflineIdentity.OFFLINE_SESSION_MAX_AGE_MS / 1000 + 10_000
        val result = repo(rig, client).restoreSession()
        assertIs<IdentityResult.Failure>(result)
        client.close()
    }

    @Test fun explicitLogoutWipesLocalDataAndOtherAccountsStartClean() = runTest {
        val (rig, client) = signedInRig()
        // El cierre explícito solo ocurre sin cambios por enviar (la interfaz lo exige).
        rig.h.enqueue("key-pending-001"); rig.h.runtime.syncNow()
        val repository = repo(rig, client)
        repository.endSession()
        assertEquals(emptyList(), rig.h.database.scopes())
        assertNull(rig.h.runtime.active.current)
        client.close()
    }

    @Test fun sessionExpiryHidesButKeepsDataForTheSameAccount() = runTest {
        val (rig, client) = signedInRig()
        rig.h.enqueue("key-pending-002")
        val repository = repo(rig, client)
        repository.signOut()
        assertNull(rig.h.runtime.active.current)
        assertEquals(1, rig.h.database.scopes().size)
        client.close()
    }

    @Test fun signingInAsAnotherAccountKeepsThePreviousAccountsPendingDataHidden() = runTest {
        val (rig, client) = signedInRig()
        rig.h.enqueue("key-pending-003")
        val other = client(rig, """{"idToken":"t2","refreshToken":"r2","localId":"uid-2","expiresIn":"3600"}""", renewed,
            """{"data":{"id":"uid-2","email":"c@d.com","displayName":"Otra persona","organizations":[]}}""")
        assertIs<IdentityResult.Success<Profile>>(repo(rig, other).signIn("c@d.com", "password"))
        assertEquals(OfflineScope(config.apiBaseUrl, "uid-2").id, rig.h.runtime.active.current!!.id)
        assertEquals(0, rig.h.runtime.outbox.snapshot().size)
        // La partición de la cuenta anterior sigue ahí (tiene un cambio sin enviar) pero la nueva no la ve.
        assertTrue(rig.h.database.scopes().any { it == OfflineScope(config.apiBaseUrl, "uid-1").id })
        client.close(); other.close()
    }
}
