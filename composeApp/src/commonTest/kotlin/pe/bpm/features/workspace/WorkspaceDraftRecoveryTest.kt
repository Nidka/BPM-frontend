package pe.bpm.features.workspace

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.workspace.data.LocalWorkspaceFormDraftRepository
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.presentation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceDraftRecoveryTest {
    @Test fun formSurvivesExpiryWithoutSharingDataAcrossAccountsOrOrganizations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        try {
            val storage = TestDraftStorage()
            val drafts = ManageWorkspaceFormDraft(LocalWorkspaceFormDraftRepository(storage, "https://api.example.com"))
            var ids = 0
            val check = CheckMember(object : MemberEligibilityReader {
                override suspend fun checkMember(organizationId: String, email: String): Result<MemberEligibility> = Result.Success(MemberEligibility.READY)
            })
            val first = WorkspaceFormViewModel(drafts, check) { "id-${++ids}" }; models.put("first", first)
            first.open("user", "org", WorkspaceFormKind.Location); advanceUntilIdle()
            val id = first.state.value.draft!!.id
            first.edit { it.copy(name = "Sede sin terminar", address = "Dirección en proceso") }
            first.reset(); first.open("user", "org", WorkspaceFormKind.Location); advanceUntilIdle()
            assertEquals(id, first.state.value.draft?.id)
            assertEquals("Dirección en proceso", first.state.value.draft?.address)
            assertTrue(first.state.value.recovered)
            first.open("other", "org", WorkspaceFormKind.Location); advanceUntilIdle()
            assertEquals("", first.state.value.draft?.address)
            first.open("user", "other-org", WorkspaceFormKind.Location); advanceUntilIdle()
            assertEquals("", first.state.value.draft?.address)
            assertNotNull(drafts.read(DraftOwner("user", "org"), "Location"))
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun submitRequiresDurableDraftAndSuccessCleansItBeforeNavigation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        try {
            val storage = TestDraftStorage()
            val drafts = ManageWorkspaceFormDraft(LocalWorkspaceFormDraftRepository(storage, "https://api.example.com"))
            val check = CheckMember(object : MemberEligibilityReader {
                override suspend fun checkMember(organizationId: String, email: String): Result<MemberEligibility> = Result.Success(MemberEligibility.READY)
            })
            val vm = WorkspaceFormViewModel(drafts, check) { "id" }; models.put("vm", vm)
            vm.open("user", "org", WorkspaceFormKind.Member); advanceUntilIdle()
            vm.edit { it.copy(email = "test@example.com", role = "CHEF", locations = setOf("site")) }; advanceUntilIdle()
            vm.checkAccount(); advanceUntilIdle()
            storage.failWrites = true
            var sends = 0; vm.submit { sends++ }; advanceUntilIdle()
            assertEquals(0, sends)
            storage.failWrites = false; vm.submit { sends++ }; advanceUntilIdle()
            assertEquals(1, sends)
            var closed = false
            vm.complete { closed = true }; advanceUntilIdle()
            assertTrue(closed)
            assertNull(drafts.read(DraftOwner("user", "org"), "Member"))
        } finally { models.clear(); Dispatchers.resetMain() }
    }
}
