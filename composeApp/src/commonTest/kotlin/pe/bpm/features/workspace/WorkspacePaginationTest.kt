package pe.bpm.features.workspace

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.workspace.data.RemoteWorkspaceRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result
import pe.bpm.features.workspace.presentation.WorkspaceViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspacePaginationTest {
    private open class Repository : WorkspaceRepository {
        val org = Organization("first", "Primera")
        override suspend fun organizations(request: PageRequest): Result<Page<Organization>> = Result.Success(Page(listOf(org), "organizations-next"))
        override suspend fun workspace(id: String): Result<Workspace> = Result.Success(Workspace(org.copy(id = id), "ADMIN", listOf(Location("site-1", "Sede uno", "Dirección")), emptyList(), emptyList(), emptyList(), emptyList(), nextPageTokens = mapOf("locations" to "sites-next")))
        override suspend fun page(organizationId: String, resource: WorkspaceResource, request: PageRequest): Result<WorkspaceItems> = Result.Success(WorkspaceItems.Locations(Page(listOf(Location("site-2", "Sede dos", "Dirección")))))
        override suspend fun createOrganization(input: Organization) = Result.Success(Unit)
        override suspend fun createLocation(organizationId: String, input: Location) = Result.Success(Unit)
        override suspend fun addMember(organizationId: String, email: String, role: String, locations: List<String>) = Result.Success(Unit)
    }
    private fun model(repository: WorkspaceRepository) = WorkspaceViewModel(LoadOrganizations(repository), LoadWorkspace(repository), CreateOrganization(repository), CreateLocation(repository), AddMember(repository), LoadWorkspacePage(repository))

    @Test fun pageTransportPreservesOpaqueCursorLiteralQueryAndMetadata() = runTest {
        val client = createHttpClient(MockEngine { request ->
            assertEquals("Bearer token", request.headers["Authorization"])
            assertEquals("17", request.url.parameters["pageSize"])
            assertEquals("opaque_cursor", request.url.parameters["pageToken"])
            assertEquals("%_á", request.url.parameters["query"])
            respond("""{"data":[{"id":"site-2","name":"Sede dos","address":"Av. Prueba"}],"pagination":{"nextPageToken":"next_cursor"}}""", HttpStatusCode.OK, headersOf("Content-Type","application/json"))
        })
        try {
            val result = RemoteWorkspaceRepository(client, AppConfig("https://api.example.com")) { "token" }.page("org", WorkspaceResource.Locations, PageRequest("opaque_cursor", "%_á",17))
            val items = assertIs<WorkspaceItems.Locations>(assertIs<Result.Success<WorkspaceItems>>(result).value)
            assertEquals("site-2", items.page.items.single().id)
            assertEquals("next_cursor", items.page.nextToken)
        } finally { client.close() }
    }
    @Test fun invalidOrNonAdvancingCursorsAreRejectedAsInvalidResponses() = runTest {
        var next = "same_cursor"
        val client = createHttpClient(MockEngine {
            respond("""{"data":[],"pagination":{"nextPageToken":"$next"}}""", HttpStatusCode.OK, headersOf("Content-Type","application/json"))
        })
        try {
            val repository = RemoteWorkspaceRepository(client, AppConfig("https://api.example.com")) { "token" }
            assertEquals("INVALID_RESPONSE", assertIs<Result.Failure>(repository.organizations(PageRequest("same_cursor"))).errors.single().code)
            next = "x".repeat(2049)
            assertEquals("INVALID_RESPONSE", assertIs<Result.Failure>(repository.organizations()).errors.single().code)
        } finally { client.close() }
    }
    @Test fun organizationPagesNeverReplaceTheActiveTenantUntilSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store=ViewModelStore()
        val requests=mutableListOf<PageRequest>()
        val repository=object:Repository() {
            override suspend fun organizations(request: PageRequest): Result<Page<Organization>> {
                requests+=request
                return Result.Success(if(request.token.isEmpty()) Page(listOf(org),"next") else Page(listOf(Organization("second","Segunda"))))
            }
        }
        try {
            val vm=model(repository);store.put("vm",vm);vm.load();advanceUntilIdle()
            vm.organizationNext();advanceUntilIdle()
            assertEquals("second",vm.state.value.organizations.single().id)
            assertEquals("first",vm.state.value.selected)
            assertEquals("first",vm.state.value.workspace?.organization?.id)
            assertEquals(2,vm.state.value.organizationPage.number)
            vm.organizationPrevious();advanceUntilIdle();assertEquals("",requests.last().token)
            vm.organizationSearch("descartada");vm.organizationSearch("final");advanceUntilIdle()
            assertEquals(1,requests.count { it.query.isNotEmpty() });assertEquals("final",requests.last().query)
            assertEquals("",requests.last().token)
        } finally {store.clear();Dispatchers.resetMain()}
    }
    @Test fun anExpiredMembershipClearsEveryBusinessListOnNextPage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore()
        val repository=object:Repository(){
            override suspend fun page(organizationId:String,resource:WorkspaceResource,request:PageRequest):Result<WorkspaceItems> = Result.Failure(listOf(WorkspaceError("ACCESS_DENIED","Sin permiso")))
        }
        try {
            val vm=model(repository);store.put("vm",vm);vm.load();advanceUntilIdle();vm.pageNext(WorkspaceResource.Locations);advanceUntilIdle()
            assertNull(vm.state.value.workspace);assertTrue(vm.state.value.pages.isEmpty());assertEquals("ACCESS_DENIED",vm.state.value.errors.single().code)
        }finally{store.clear();Dispatchers.resetMain()}
    }
    @Test fun latePagesCannotRestoreLocationsAfterLogout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();val release=CompletableDeferred<Unit>()
        val repository=object:Repository(){
            override suspend fun page(organizationId:String,resource:WorkspaceResource,request:PageRequest):Result<WorkspaceItems>{withContext(NonCancellable){release.await()};return super.page(organizationId,resource,request)}
        }
        try {
            val vm=model(repository);store.put("vm",vm);vm.load();advanceUntilIdle();vm.pageNext(WorkspaceResource.Locations);runCurrent();vm.reset();release.complete(Unit);advanceUntilIdle()
            assertNull(vm.state.value.workspace);assertTrue(vm.state.value.pages.isEmpty());assertTrue(vm.state.value.organizations.isEmpty())
        }finally{release.complete(Unit);store.clear();Dispatchers.resetMain()}
    }
    @Test fun pageErrorsRetryTheSameCursorAndPreviousRestoresTheFirstPage()= runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val store=ViewModelStore();var failed=true;val seen=mutableListOf<PageRequest>()
        val repository=object:Repository(){
            override suspend fun page(organizationId:String,resource:WorkspaceResource,request:PageRequest):Result<WorkspaceItems>{seen+=request;return if(failed)Result.Failure(listOf(WorkspaceError("CONNECTION_ERROR","Sin conexión"))) else super.page(organizationId,resource,request)}
        }
        try {
            val vm=model(repository);store.put("vm",vm);vm.load();advanceUntilIdle();vm.pageNext(WorkspaceResource.Locations);advanceUntilIdle();failed=false
            vm.pageRetry(WorkspaceResource.Locations);advanceUntilIdle();assertEquals(listOf("sites-next","sites-next"),seen.map { it.token })
            assertEquals("site-2",vm.state.value.workspace?.locations?.single()?.id)
            vm.pagePrevious(WorkspaceResource.Locations);advanceUntilIdle();assertEquals("",seen.last().token)
        }finally{store.clear();Dispatchers.resetMain()}
    }
}
