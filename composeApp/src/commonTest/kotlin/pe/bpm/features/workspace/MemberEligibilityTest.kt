package pe.bpm.features.workspace

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.workspace.data.LocalWorkspaceFormDraftRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result
import pe.bpm.features.workspace.presentation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MemberEligibilityTest {
    @Test fun onlyCheckedEmailCanBeAssignedAndRecoveredDraftRequiresAnotherCheck() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        var status = MemberEligibility.UNAVAILABLE
        var calls = 0
        val check = CheckMember(object : MemberEligibilityReader {
            override suspend fun checkMember(organizationId: String, email: String): Result<MemberEligibility> {
                calls++; return Result.Success(status)
            }
        })
        try {
            val drafts = ManageWorkspaceFormDraft(LocalWorkspaceFormDraftRepository(TestDraftStorage(), "https://api.example.com"))
            val vm = WorkspaceFormViewModel(drafts, check) { "draft" }; models.put("vm", vm)
            vm.open("admin", "org", WorkspaceFormKind.Member); advanceUntilIdle()
            var sends = 0
            vm.edit { it.copy(email = "bad") }; vm.checkAccount(); advanceUntilIdle()
            assertEquals(0, calls)
            assertTrue(vm.state.value.memberCheck.errors.isNotEmpty())
            vm.edit { it.copy(email = "chef@example.com") }; vm.checkAccount(); advanceUntilIdle()
            vm.submit { sends++ }; advanceUntilIdle(); assertEquals(0, sends)
            status = MemberEligibility.ALREADY_MEMBER; vm.checkAccount(); advanceUntilIdle()
            vm.submit { sends++ }; advanceUntilIdle(); assertEquals(0, sends)
            status = MemberEligibility.READY; vm.checkAccount(); advanceUntilIdle()
            vm.edit { it.copy(role = "CHEF", locations = setOf("site")) }
            vm.submit { sends++ }; advanceUntilIdle(); assertEquals(1, sends)
            vm.onSaveFailure(listOf(WorkspaceError("RESOURCE_CONFLICT", "Ya pertenece al equipo.")))
            vm.submit { sends++ }; advanceUntilIdle(); assertEquals(1, sends)
            assertEquals(MemberEligibility.ALREADY_MEMBER, vm.state.value.memberCheck.status)
            vm.edit { it.copy(email = "other@example.com") }
            vm.submit { sends++ }; advanceUntilIdle(); assertEquals(1, sends)
            assertNull(vm.state.value.memberCheck.status)
            vm.checkAccount(); advanceUntilIdle()
            vm.reset(); vm.open("admin", "org", WorkspaceFormKind.Member); advanceUntilIdle()
            assertEquals("other@example.com", vm.state.value.draft?.email)
            assertNull(vm.state.value.memberCheck.status)
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun lateEligibilityCannotAuthorizeADifferentEmailOrOrganization() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        val release = CompletableDeferred<Unit>()
        val check = CheckMember(object : MemberEligibilityReader {
            override suspend fun checkMember(organizationId: String, email: String): Result<MemberEligibility> {
                withContext(NonCancellable) { release.await() }
                return Result.Success(MemberEligibility.READY)
            }
        })
        try {
            val drafts = ManageWorkspaceFormDraft(LocalWorkspaceFormDraftRepository(TestDraftStorage(), "https://api.example.com"))
            val vm = WorkspaceFormViewModel(drafts, check) { "draft" }; models.put("vm", vm)
            vm.open("admin", "first", WorkspaceFormKind.Member); runCurrent()
            vm.edit { it.copy(email = "chef@example.com") }; vm.checkAccount(); runCurrent()
            vm.open("admin", "second", WorkspaceFormKind.Member); runCurrent()
            release.complete(Unit); advanceUntilIdle()
            assertNull(vm.state.value.memberCheck.status)
            assertEquals("", vm.state.value.draft?.email)
        } finally { release.complete(Unit); models.clear(); Dispatchers.resetMain() }
    }
}
