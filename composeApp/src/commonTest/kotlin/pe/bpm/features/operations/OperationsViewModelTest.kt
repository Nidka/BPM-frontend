package pe.bpm.features.operations

import androidx.lifecycle.viewModelScope
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import pe.bpm.core.config.AppConfig
import pe.bpm.core.storage.DraftStorage
import pe.bpm.features.operations.data.*
import pe.bpm.features.operations.domain.*
import pe.bpm.features.operations.presentation.OperationsViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class OperationsViewModelTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val org = "22222222-2222-4222-8222-222222222222"

    private class Memory : DraftStorage {
        val values = mutableMapOf<String, String>()

        override suspend fun read(key: String) = values[key]

        override suspend fun write(key: String, value: String) {
            values[key] = value
        }

        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }

    @Test
    fun revokedAccessHidesVisibleDataAndRetainsDurableDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var revoked = false
        val memory = Memory()
        val engine =
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        when {
                            request.url.encodedPath.endsWith("workspace") ->
                                respond(
                                    "{\"data\":{\"organization\":{\"id\":\"$org\"},\"role\":\"${if(revoked)"WORKER" else "ADMIN"}\"}}",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            revoked ->
                                respond(
                                    "{}",
                                    HttpStatusCode.Forbidden,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            else ->
                                respond(
                                    "{\"data\":[{\"id\":\"$id\",\"name\":\"Proveedor visible\"}],\"pagination\":{\"nextPageToken\":\"\"}}",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                        }
                    }
                }
            )
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        val repo = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
        val vm =
            OperationsViewModel(
                ManageOperations(repo, repo),
                ManageOperationsDraft(LocalOperationsDraftRepository(memory, "test")),
                { id },
                { "2026-11-02T10:00:00Z" },
            )
        try {
            vm.open("user", org, "ADMIN")
            advanceUntilIdle()
            assertEquals(1, vm.state.value.rows.size)
            vm.newForm()
            advanceUntilIdle()
            vm.field("name", "Borrador privado")
            advanceUntilIdle()
            revoked = true
            vm.pick(OperationsResource.SUPPLIERS)
            advanceUntilIdle()
            assertEquals(OperationsFailure.DENIED, vm.state.value.failure)
            assertNull(vm.state.value.form)
            assertNull(vm.state.value.pending)
            assertNull(vm.state.value.detail)
            assertNull(vm.state.value.saved)
            assertTrue(vm.state.value.rows.isEmpty())
            assertTrue(vm.state.value.pickerRows.isEmpty())
            assertTrue(vm.state.value.products.isEmpty())
            assertTrue(memory.values.values.any { it.contains("Borrador privado") })
            vm.newForm()
            advanceUntilIdle()
            assertNull(vm.state.value.form)
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun uncertainPostIsDurableAndRestoresIdenticalRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val bodies = mutableListOf<String>()
        val keys = mutableListOf<String>()
        val memory = Memory()
        val client =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler { request ->
                            when {
                                request.url.encodedPath.endsWith("workspace") ->
                                    respond(
                                        """{"data":{"organization":{"id":"$org"},"role":"ADMIN"}}""",
                                        headers =
                                            headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                request.method == HttpMethod.Post -> {
                                    bodies += request.body.toByteArray().decodeToString()
                                    keys += request.headers["Idempotency-Key"].orEmpty()
                                    respond(
                                        if (bodies.size == 1) "{}"
                                        else "{\"data\":${bodies.last()}}",
                                        if (bodies.size == 1) HttpStatusCode.ServiceUnavailable
                                        else HttpStatusCode.OK,
                                        headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                                }
                                else ->
                                    respond(
                                        """{"data":[],"pagination":{"nextPageToken":""}}""",
                                        headers =
                                            headersOf(HttpHeaders.ContentType, "application/json"),
                                    )
                            }
                        }
                    }
                )
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        val repo = RemoteOperationsRepository(client, AppConfig("https://example.test")) { "token" }
        fun vm() =
            OperationsViewModel(
                ManageOperations(repo, repo),
                ManageOperationsDraft(LocalOperationsDraftRepository(memory, "test")),
                { id },
                { "2026-11-02T10:00:00Z" },
            )
        val first = vm()
        val second = vm()
        try {
            first.open("user", org, "ADMIN")
            advanceUntilIdle()
            first.newForm()
            advanceUntilIdle()
            first.field("name", "Proveedor")
            advanceUntilIdle()
            first.save()
            advanceUntilIdle()
            assertNotNull(first.state.value.pending, first.state.value.toString())
            assertTrue(memory.values.values.any { it.contains("Proveedor") })
            first.reset()
            advanceUntilIdle()
            second.open("user", org, "ADMIN")
            advanceUntilIdle()
            second.newForm()
            advanceUntilIdle()
            assertNotNull(second.state.value.pending)
            second.field("name", "Cambio prohibido")
            second.save()
            advanceUntilIdle()
            assertEquals(2, bodies.size)
            assertEquals(bodies[0], bodies[1])
            assertEquals(keys[0], keys[1])
            assertEquals("Proveedor", second.state.value.form?.values?.get("name"))
            assertNotNull(second.state.value.saved)
            assertTrue(memory.values.isEmpty())
            second.closeForm()
            advanceUntilIdle()
            assertTrue(
                memory.values.isEmpty(),
                "Salir tras confirmar no debe recrear el borrador confirmado",
            )
        } finally {
            first.reset()
            second.reset()
            first.viewModelScope.cancel()
            second.viewModelScope.cancel()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
