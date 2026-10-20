package pe.bpm.features.workspace

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.workspace.data.LocalWorkspaceFormDraftRepository
import pe.bpm.features.workspace.data.RemoteWorkspaceRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result
import pe.bpm.features.workspace.presentation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MemberEligibilityTest {
    private class Finder(val read: suspend (String, String) -> Result<MemberAccountMatches>) : MemberAccountFinder {
        override suspend fun searchMemberAccounts(organizationId: String, query: String) = read(organizationId, query)
    }
    private fun drafts() = ManageWorkspaceFormDraft(LocalWorkspaceFormDraftRepository(TestDraftStorage(), "https://api.example.com"))

    @Test fun typingDebouncesAndSelectionChecksAutomaticallyBeforeAssignment() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        val calls = mutableListOf<String>(); var available = true
        val search = SearchMemberAccounts(Finder { _, query ->
            calls += query
            Result.Success(MemberAccountMatches(listOf("chef@example.com"), if (query == "chef@example.com")
                if (available) MemberEligibility.READY else MemberEligibility.UNAVAILABLE else null))
        })
        try {
            val vm = WorkspaceFormViewModel(drafts(), search) { "draft" }; models.put("vm", vm)
            vm.open("admin", "org", WorkspaceFormKind.Member); advanceUntilIdle()
            vm.edit { it.copy(email = "ch") }; advanceUntilIdle(); assertTrue(calls.isEmpty())
            vm.edit { it.copy(email = "bad email") }; advanceUntilIdle(); assertTrue(calls.isEmpty())
            assertEquals("Escribe el correo sin espacios.", vm.state.value.memberSearch.inputError)
            vm.edit { it.copy(email = "che") }; runCurrent(); advanceTimeBy(200)
            vm.edit { it.copy(email = "chef") }; runCurrent(); advanceTimeBy(299)
            assertTrue(calls.isEmpty())
            advanceUntilIdle(); assertEquals(listOf("chef"), calls)
            assertEquals(listOf("chef@example.com"), vm.state.value.memberSearch.suggestions)
            var sends = 0; vm.submit { sends++ }; advanceUntilIdle(); assertEquals(0, sends)
            vm.selectAccount("unlisted@example.com"); assertEquals("chef", vm.state.value.draft?.email)
            vm.selectAccount("chef@example.com")
            vm.submit { sends++ }; assertEquals(0, sends)
            advanceUntilIdle(); assertEquals(MemberEligibility.READY, vm.state.value.memberSearch.status)
            assertTrue(vm.state.value.memberSearch.suggestions.isEmpty())
            vm.edit { it.copy(role = "CHEF", locations = setOf("site")) }
            vm.submit { sends++ }; advanceUntilIdle(); assertEquals(1, sends)
            vm.onSaveFailure(listOf(WorkspaceError("RESOURCE_CONFLICT", "Ya pertenece al equipo.")))
            vm.submit { sends++ }; advanceUntilIdle(); assertEquals(1, sends)
            assertEquals(MemberEligibility.ALREADY_MEMBER, vm.state.value.memberSearch.status)
            available = false
            vm.reset(); vm.open("admin", "org", WorkspaceFormKind.Member); runCurrent()
            assertNull(vm.state.value.memberSearch.status)
            advanceUntilIdle(); assertEquals(MemberEligibility.UNAVAILABLE, vm.state.value.memberSearch.status)
            assertEquals("chef@example.com", vm.state.value.draft?.email)
            assertEquals(setOf("site"), vm.state.value.draft?.locations)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun lateResponsesCannotRestoreAnotherQueryOrTenant() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        val search = SearchMemberAccounts(Finder { _, query ->
            if (query == "old@example.com") withContext(NonCancellable) { release.await() }
            Result.Success(MemberAccountMatches(emptyList(), MemberEligibility.READY))
        })
        try {
            val vm = WorkspaceFormViewModel(drafts(), search) { "draft" }; models.put("vm", vm)
            vm.open("admin", "first", WorkspaceFormKind.Member); advanceUntilIdle()
            vm.edit { it.copy(email = "old@example.com") }; runCurrent(); advanceTimeBy(300); runCurrent()
            vm.edit { it.copy(email = "new@example.com") }; runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals("new@example.com", vm.state.value.memberSearch.email)
            vm.open("admin", "second", WorkspaceFormKind.Member); runCurrent()
            release.complete(Unit); advanceUntilIdle()
            assertNull(vm.state.value.memberSearch.status)
            assertTrue(vm.state.value.memberSearch.suggestions.isEmpty())
            assertEquals("", vm.state.value.draft?.email)
        } finally { release.complete(Unit); models.clear(); Dispatchers.resetMain() }
    }

    @Test fun connectionFailureDoesNotMeanAccountMissingAndRetryPreservesDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        var failed = true
        val search = SearchMemberAccounts(Finder { _, _ ->
            if (failed) Result.Failure(listOf(WorkspaceError("CONNECTION_ERROR", "No pudimos conectar.")))
            else Result.Success(MemberAccountMatches(emptyList(), MemberEligibility.ALREADY_MEMBER))
        })
        try {
            val vm = WorkspaceFormViewModel(drafts(), search) { "draft" }; models.put("vm", vm)
            vm.open("admin", "org", WorkspaceFormKind.Member); advanceUntilIdle()
            vm.edit { it.copy(email = "chef@example.com", locations = setOf("site")) }; advanceUntilIdle()
            assertNull(vm.state.value.memberSearch.status)
            assertEquals("CONNECTION_ERROR", vm.state.value.memberSearch.errors.single().code)
            var sends = 0; vm.submit { sends++ }; advanceUntilIdle(); assertEquals(0, sends)
            failed = false; vm.retryAccountSearch(); advanceUntilIdle()
            assertEquals(MemberEligibility.ALREADY_MEMBER, vm.state.value.memberSearch.status)
            assertEquals(setOf("site"), vm.state.value.draft?.locations)
            vm.edit { it.copy(email = "") }; advanceUntilIdle()
            assertNull(vm.state.value.memberSearch.status); assertTrue(vm.state.value.memberSearch.suggestions.isEmpty())
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun remoteSearchSendsOnlyQueryAndRejectsMalformedSuggestions() = runTest {
        var payload = """{"data":{"suggestions":["chef@example.com"],"exactStatus":null}}"""
        var calls = 0; var token: String? = null
        val client = createHttpClient(MockEngine { request ->
            calls++
            assertEquals("/api/v1/organizations/org/members/search", request.url.encodedPath)
            assertEquals("", request.url.encodedQuery)
            assertEquals("Bearer test.token", request.headers["Authorization"])
            assertEquals("""{"query":"chef"}""", (request.body as TextContent).text)
            respond(payload, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        try {
            val search = SearchMemberAccounts(RemoteWorkspaceRepository(client, AppConfig("https://api.example.com")) { token })
            assertIs<Result.Failure>(search("org", "ch")); assertEquals(0, calls)
            assertIs<Result.Failure>(search("org", "chef")); assertEquals(0, calls)
            token = "test.token"
            assertEquals(listOf("chef@example.com"), assertIs<Result.Success<MemberAccountMatches>>(search("org", " CHEF ")).value.suggestions)
            for (bad in listOf(
                """{"data":{"suggestions":["other@example.com"],"exactStatus":null}}""",
                """{"data":{"suggestions":["chef@example.com","chef@example.com"],"exactStatus":null}}""",
                """{"data":{"suggestions":[],"exactStatus":"PRIVATE"}}""",
                """{"data":{"suggestions":[],"exactStatus":"READY"}}"""
            )) {
                payload = bad
                assertEquals("INVALID_RESPONSE", assertIs<Result.Failure>(search("org", "chef")).errors.single().code)
            }
        } finally { client.close() }
    }
}
