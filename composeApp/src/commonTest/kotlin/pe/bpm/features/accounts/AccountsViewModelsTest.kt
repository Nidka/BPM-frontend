package pe.bpm.features.accounts

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.Page
import pe.bpm.features.accounts.domain.*
import pe.bpm.features.accounts.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelsTest {
    private fun team(repo: FakeAccountsRepository) = TeamViewModel(ListAccountMembers(repo), LoadActiveLocations(repo), UpdateAccountMember(repo), SetMemberActive(repo), TransferAdministration(repo))
    private fun invitations(repo: FakeAccountsRepository, now: Instant = Instant.parse("2026-11-10T12:00:30Z")): InvitationsViewModel {
        var n = 0
        return InvitationsViewModel(ListInvitations(repo), LoadActiveLocations(repo), InviteMember(repo), ResendInvitation(repo), RevokeInvitation(repo), { "id-${++n}" }, { now })
    }

    private fun scenario(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun lastAdminFailureKeepsTheConfirmationOpenWithAClearReason() = scenario {
        val repo = FakeAccountsRepository().apply { deactivate = AccountsResult.Failure(AccountFailure.LAST_ADMIN) }
        val vm = team(repo)
        vm.open("o1", allowed = true, actorIsMember = true); advanceUntilIdle()
        vm.askDeactivate(vm.state.value.page.items.single()); vm.confirmAction(); advanceUntilIdle()
        assertEquals(AccountFailure.LAST_ADMIN, vm.state.value.failure)
        assertNotNull(vm.state.value.action)
        assertFalse(vm.state.value.busy)
        assertTrue(accountFailureMessage(AccountFailure.LAST_ADMIN).contains("administradora activa"))
        vm.viewModelScope.cancel()
    }

    @Test fun versionConflictReloadsTheListAndClosesTheEditor() = scenario {
        val repo = FakeAccountsRepository().apply { updateMember = AccountsResult.Failure(AccountFailure.VERSION_CONFLICT) }
        val vm = team(repo)
        vm.open("o1", true, true); advanceUntilIdle()
        val before = repo.calls.count { it.startsWith("members") }
        vm.edit(vm.state.value.page.items.single()); vm.editRole(MemberRole.WORKER); vm.saveEditor(); advanceUntilIdle()
        assertEquals(AccountFailure.VERSION_CONFLICT, vm.state.value.failure)
        assertNull(vm.state.value.editor)
        assertEquals(before + 1, repo.calls.count { it.startsWith("members") })
        vm.viewModelScope.cancel()
    }

    @Test fun successfulEditSendsTheReadRowVersionAndRefreshes() = scenario {
        val repo = FakeAccountsRepository().apply { members = { AccountsResult.Success(Page(listOf(member(version = 9)))) } }
        val vm = team(repo)
        vm.open("o1", true, true); advanceUntilIdle()
        vm.edit(vm.state.value.page.items.single()); vm.editRole(MemberRole.ADMIN); vm.saveEditor(); advanceUntilIdle()
        assertEquals(Triple(MemberRole.ADMIN, emptyList<String>(), 9L), repo.memberRequests.single())
        assertEquals("Cambios guardados.", vm.state.value.notice)
        vm.viewModelScope.cancel()
    }

    @Test fun transferAsAdminMemberNeedsTheNewRoleBeforeSending() = scenario {
        val repo = FakeAccountsRepository()
        val vm = team(repo)
        vm.open("o1", true, actorIsMember = true); advanceUntilIdle()
        vm.askTransfer(vm.state.value.page.items.single()); vm.confirmAction(); advanceUntilIdle()
        assertNull(repo.transferActor)
        assertNotNull((vm.state.value.action as MemberAction.Transfer).fields["actorRole"])
        vm.transferRole(MemberRole.CHEF); vm.toggleTransferLocation("l1"); vm.confirmAction(); advanceUntilIdle()
        assertEquals(MemberRole.CHEF to listOf("l1"), repo.transferActor)
        assertNull(vm.state.value.action)
        vm.viewModelScope.cancel()
    }

    @Test fun nonManagersNeverLoadMembers() = scenario {
        val repo = FakeAccountsRepository()
        val vm = team(repo)
        vm.open("o1", allowed = false, actorIsMember = false); advanceUntilIdle()
        assertTrue(repo.calls.isEmpty())
        vm.edit(member()); vm.askDeactivate(member())
        assertNull(vm.state.value.editor); assertNull(vm.state.value.action)
        vm.viewModelScope.cancel()
    }

    @Test fun statusFilterReloadsWithInactiveMembers() = scenario {
        val repo = FakeAccountsRepository()
        val vm = team(repo)
        vm.open("o1", true, true); advanceUntilIdle()
        vm.selectStatus(RecordStatus.INACTIVE); advanceUntilIdle()
        assertEquals(listOf("members:ACTIVE", "members:INACTIVE"), repo.calls)
        vm.viewModelScope.cancel()
    }

    @Test fun resendInsideTheWaitIsBlockedLocallyAndAfterItIsSent() = scenario {
        val repo = FakeAccountsRepository()
        val waiting = invitations(repo, Instant.parse("2026-11-10T12:00:30Z"))
        waiting.open("o1", true); advanceUntilIdle()
        waiting.resend(waiting.state.value.page.items.single()); advanceUntilIdle()
        assertEquals(AccountFailure.RESEND_TOO_SOON, waiting.state.value.failure)
        assertTrue(repo.calls.none { it.startsWith("resend") })
        val ready = invitations(repo, Instant.parse("2026-11-10T12:02:00Z"))
        ready.open("o1", true); advanceUntilIdle()
        ready.resend(ready.state.value.page.items.single()); advanceUntilIdle()
        assertEquals(listOf("resend:i1"), repo.calls)
        waiting.viewModelScope.cancel(); ready.viewModelScope.cancel()
    }

    @Test fun expiredInvitationCanBeResentAndServerTooSoonIsReported() = scenario {
        val repo = FakeAccountsRepository().apply {
            invitations = AccountsResult.Success(Page(listOf(invitation(status = InvitationStatus.EXPIRED))))
            resend = AccountsResult.Failure(AccountFailure.RESEND_TOO_SOON)
        }
        val vm = invitations(repo, Instant.parse("2026-11-10T13:00:00Z"))
        vm.open("o1", true); advanceUntilIdle()
        vm.resend(vm.state.value.page.items.single()); advanceUntilIdle()
        assertEquals(listOf("resend:i1"), repo.calls)
        assertEquals(AccountFailure.RESEND_TOO_SOON, vm.state.value.failure)
        vm.viewModelScope.cancel()
    }

    @Test fun inviteKeepsTheClientIdOnRetryAndChangesItWhenContentChanges() = scenario {
        val repo = FakeAccountsRepository().apply { invite = AccountsResult.Failure(AccountFailure.CONNECTION) }
        val vm = invitations(repo)
        vm.open("o1", true); advanceUntilIdle()
        vm.openForm(); vm.editEmail("nueva@example.com"); vm.toggleLocation("l1")
        vm.submit(); advanceUntilIdle(); vm.submit(); advanceUntilIdle()
        assertEquals(2, repo.inviteIds.size); assertEquals(repo.inviteIds[0], repo.inviteIds[1])
        vm.editEmail("otra@example.com"); vm.submit(); advanceUntilIdle()
        assertNotEquals(repo.inviteIds[0], repo.inviteIds[2])
        repo.invite = AccountsResult.Failure(AccountFailure.MEMBER_ALREADY_EXISTS)
        vm.submit(); advanceUntilIdle()
        assertEquals(AccountFailure.MEMBER_ALREADY_EXISTS, vm.state.value.failure)
        vm.viewModelScope.cancel()
    }

    @Test fun expiredInvitationIsReportedOnAccept() = scenario {
        val repo = FakeAccountsRepository().apply { accept = AccountsResult.Failure(AccountFailure.INVITATION_EXPIRED) }
        val vm = InvitationAcceptViewModel(AcceptInvitation(repo))
        vm.edit(TOKEN); vm.accept(); advanceUntilIdle()
        assertEquals(AccountFailure.INVITATION_EXPIRED, vm.state.value.failure)
        assertNull(vm.state.value.accepted)
        assertTrue(accountFailureMessage(AccountFailure.INVITATION_EXPIRED).contains("venció"))
        vm.viewModelScope.cancel()
    }

    @Test fun linkTokenPrefillsButNeverAcceptsByItself() = scenario {
        val repo = FakeAccountsRepository()
        val vm = InvitationAcceptViewModel(AcceptInvitation(repo))
        vm.openWithLink(TOKEN); vm.openWithLink("invalido"); advanceUntilIdle()
        assertEquals(TOKEN, vm.state.value.input)
        assertTrue(repo.acceptedTokens.isEmpty())
        vm.accept(); advanceUntilIdle()
        assertEquals(listOf(TOKEN), repo.acceptedTokens)
        assertEquals("", vm.state.value.input)
        assertNotNull(vm.state.value.accepted)
        vm.viewModelScope.cancel()
    }

    @Test fun malformedPastedTokenShowsAFieldErrorWithoutCallingTheServer() = scenario {
        val repo = FakeAccountsRepository()
        val vm = InvitationAcceptViewModel(AcceptInvitation(repo))
        vm.edit("abc"); vm.accept(); advanceUntilIdle()
        assertNotNull(vm.state.value.fieldError)
        assertTrue(repo.acceptedTokens.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test fun revokingADeviceRefreshesTheList() = scenario {
        val repo = FakeAccountsRepository()
        val vm = DevicesViewModel(ListMyDevices(repo), RevokeMyDevice(repo))
        vm.open(); advanceUntilIdle()
        val device = AccountDevice("d1", "o1", "BPM", DevicePlatform.WEB, RecordStatus.ACTIVE, null, STAMP)
        vm.askRevoke(device); vm.confirmRevoke(); advanceUntilIdle()
        assertEquals(listOf("revokeDevice:d1"), repo.calls)
        assertNull(vm.state.value.revoking)
        assertNotNull(vm.state.value.notice)
        vm.viewModelScope.cancel()
    }
}
