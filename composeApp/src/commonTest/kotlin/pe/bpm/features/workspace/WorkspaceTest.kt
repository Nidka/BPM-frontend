package pe.bpm.features.workspace

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.workspace.data.RemoteWorkspaceRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result
import pe.bpm.features.workspace.presentation.WorkspaceViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceTest {
    private val config = AppConfig("https://api.example.com")
    private val headers = headersOf("Content-Type", "application/json")

    @Test fun requestsRequireSessionAndSendOnlyBusinessFields() = runTest {
        var calls = 0
        var token: String? = null
        val client = createHttpClient(MockEngine { request ->
            calls++
            assertEquals("Bearer test.token", request.headers["Authorization"])
            if (request.method == HttpMethod.Post) {
                val body = (request.body as TextContent).text
                assertTrue(body.contains("RESTAURANT"))
                assertFalse(body.contains("platformRole"))
                respond("{}", HttpStatusCode.OK, headers)
            } else respond("""{"data":[]}""", HttpStatusCode.OK, headers)
        })
        try {
            val repository = RemoteWorkspaceRepository(client, config) { token }
            assertIs<Result.Failure>(repository.organizations())
            assertEquals(0, calls)
            token = "test.token"
            assertEquals(emptyList(), assertIs<Result.Success<Page<Organization>>>(repository.organizations()).value.items)
            assertIs<Result.Success<Unit>>(repository.createOrganization(Organization("id", "BPM")))
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun formErrorsKeepOnlyAllowedFieldsAndNeverInternalMessages() = runTest {
        val client = createHttpClient(MockEngine {
            respond("""{"errors":[{"code":"VALIDATION_ERROR","message":"SQL secret","fields":{"name":"private","password":"secret"}}]}""", HttpStatusCode.UnprocessableEntity, headers)
        })
        try {
            val result = RemoteWorkspaceRepository(client, config) { "test.token" }.createLocation("org", Location("id", "", ""))
            val error = assertIs<Result.Failure>(result).errors.single()
            assertEquals("VALIDATION_ERROR", error.code)
            assertEquals(setOf("name"), error.fields.keys)
            assertFalse(error.toString().contains("secret"))
        } finally { client.close() }
    }

    @Test fun switchingAndLogoutClearPreviousTenantAndCancelPendingLoads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val first = Organization("first", "Primera")
        val second = Organization("second", "Segunda")
        val repository = object : WorkspaceRepository {
            override suspend fun organizations(request: PageRequest) = Result.Success(Page(listOf(first, second)))
            override suspend fun page(organizationId: String, resource: WorkspaceResource, request: PageRequest): Result<WorkspaceItems> = Result.Failure(emptyList())
            override suspend fun workspace(id: String): Result<Workspace> {
                if (id == second.id) awaitCancellation()
                return Result.Success(Workspace(first, "ADMIN", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
            }
            override suspend fun createOrganization(input: Organization) = Result.Success(Unit)
            override suspend fun createLocation(organizationId: String, input: Location) = Result.Success(Unit)
            override suspend fun addMember(organizationId: String, email: String, role: String, locations: List<String>) = Result.Success(Unit)
        }
        try {
            val model = WorkspaceViewModel(LoadOrganizations(repository), LoadWorkspace(repository), CreateOrganization(repository), CreateLocation(repository), AddMember(repository), LoadWorkspacePage(repository))
            store.put("workspace", model)
            model.load(); advanceUntilIdle()
            assertEquals(first.id, model.state.value.workspace?.organization?.id)
            model.select(second.id); runCurrent()
            assertNull(model.state.value.workspace)
            assertTrue(model.state.value.loading)
            model.reset(); advanceUntilIdle()
            assertNull(model.state.value.selected)
            assertTrue(model.state.value.organizations.isEmpty())
            assertFalse(model.state.value.loading)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun aLateResponseCannotRestoreWorkspaceAfterLogout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val response = CompletableDeferred<Unit>()
        val organization = Organization("first", "Primera")
        val repository = object : WorkspaceRepository {
            override suspend fun organizations(request: PageRequest) = Result.Success(Page(listOf(organization)))
            override suspend fun page(organizationId: String, resource: WorkspaceResource, request: PageRequest): Result<WorkspaceItems> = Result.Failure(emptyList())
            override suspend fun workspace(id: String): Result<Workspace> {
                // Simulate an external adapter that finishes even after cancellation.
                withContext(NonCancellable) { response.await() }
                return Result.Success(Workspace(organization, "ADMIN", emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))
            }
            override suspend fun createOrganization(input: Organization) = Result.Success(Unit)
            override suspend fun createLocation(organizationId: String, input: Location) = Result.Success(Unit)
            override suspend fun addMember(organizationId: String, email: String, role: String, locations: List<String>) = Result.Success(Unit)
        }
        try {
            val model = WorkspaceViewModel(LoadOrganizations(repository), LoadWorkspace(repository), CreateOrganization(repository), CreateLocation(repository), AddMember(repository), LoadWorkspacePage(repository))
            store.put("workspace", model)
            model.load()
            runCurrent()
            assertTrue(model.state.value.loading)
            model.reset()
            response.complete(Unit)
            advanceUntilIdle()
            assertNull(model.state.value.workspace)
            assertNull(model.state.value.selected)
            assertTrue(model.state.value.organizations.isEmpty())
        } finally {
            response.complete(Unit)
            store.clear()
            Dispatchers.resetMain()
        }
    }

}
