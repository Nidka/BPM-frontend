package pe.bpm.features.accounts

import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import pe.bpm.features.accounts.domain.*

class AccountsDomainTest {
    @Test fun businessTypeMapsTheLegacyValueToOtherFoodService() {
        assertEquals(BusinessType.OTHER_FOOD_SERVICE, BusinessType.fromCode("RELATED_SERVICE"))
        assertEquals(BusinessType.CAFETERIA, BusinessType.fromCode("CAFETERIA"))
        assertNull(BusinessType.fromCode("OTRO"))
        assertEquals(listOf("Restaurante", "Cafetín", "Catering", "Otro servicio de alimentos"), BusinessType.entries.map { it.label })
    }

    @Test fun chefAndWorkerNeedLocationsButAdminDoesNot() {
        assertNotNull(AccountsRules.locationsFor(MemberRole.CHEF, emptyList()))
        assertNotNull(AccountsRules.locationsFor(MemberRole.WORKER, listOf("a", "a")))
        assertNull(AccountsRules.locationsFor(MemberRole.ADMIN, emptyList()))
        assertNotNull(AccountsRules.locationsFor(MemberRole.WORKER, (1..101).map { "l$it" }))
    }

    @Test fun updatingAnAdminDoesNotSendLocations() = runTest {
        val repo = FakeAccountsRepository()
        UpdateAccountMember(repo)("o1", member(version = 7), MemberRole.ADMIN, listOf("l1"))
        assertEquals(Triple(MemberRole.ADMIN, emptyList<String>(), 7L), repo.memberRequests.single())
        val invalid = UpdateAccountMember(repo)("o1", member(), MemberRole.WORKER, emptyList())
        assertTrue(invalid is AccountsResult.Failure && "locationIds" in invalid.fields)
        assertEquals(1, repo.memberRequests.size)
    }

    @Test fun transferRequiresActorRoleOnlyWhenTheActorIsAMember() = runTest {
        val repo = FakeAccountsRepository()
        val useCase = TransferAdministration(repo)
        val missing = useCase("o1", member(), true, null, emptyList())
        assertTrue(missing is AccountsResult.Failure && "actorRole" in missing.fields)
        assertTrue(useCase("o1", member(), true, MemberRole.ADMIN, listOf("l1")) is AccountsResult.Failure)
        val noLocations = useCase("o1", member(), true, MemberRole.CHEF, emptyList())
        assertTrue(noLocations is AccountsResult.Failure && "actorLocationIds" in noLocations.fields)
        assertNull(repo.transferActor)
        assertTrue(useCase("o1", member(), true, MemberRole.WORKER, listOf("l1")) is AccountsResult.Success)
        assertEquals(MemberRole.WORKER to listOf("l1"), repo.transferActor)
        // SUPER_ADMIN no miembro: se omiten rol y sedes aunque se le pasen.
        useCase("o1", member(), false, MemberRole.WORKER, listOf("l1"))
        assertEquals(null to emptyList<String>(), repo.transferActor)
        val inactive = useCase("o1", member(status = RecordStatus.INACTIVE), false, null, emptyList())
        assertEquals(AccountFailure.MEMBER_INACTIVE, (inactive as AccountsResult.Failure).reason)
    }

    @Test fun tokenCanBePastedAsTokenOrAsLink() {
        assertEquals(TOKEN, AccountsRules.tokenFromPaste("  $TOKEN \n"))
        assertEquals(TOKEN, AccountsRules.tokenFromPaste("https://app.example.com/cuenta/invitacion?token=$TOKEN"))
        assertEquals(TOKEN, AccountsRules.tokenFromPaste("https://app.example.com/cuenta/invitacion?a=1&token=$TOKEN#x"))
        assertNull(AccountsRules.tokenFromPaste("corto"))
        assertNull(AccountsRules.tokenFromPaste("$TOKEN="))
        assertNull(AccountsRules.tokenFromPaste("https://x.example?token=abc"))
    }

    @Test fun invalidTokenNeverReachesTheRepository() = runTest {
        val repo = FakeAccountsRepository()
        val result = AcceptInvitation(repo)("no-es-un-token")
        assertTrue(result is AccountsResult.Failure && "token" in result.fields)
        assertTrue(repo.acceptedTokens.isEmpty())
        AcceptInvitation(repo)("https://x.example/cuenta/invitacion?token=$TOKEN")
        assertEquals(listOf(TOKEN), repo.acceptedTokens)
    }

    @Test fun resendWaitIsOneMinuteFromTheLastSend() {
        assertEquals(60, AccountsRules.resendWaitSeconds(STAMP, Instant.parse(STAMP)))
        assertEquals(1, AccountsRules.resendWaitSeconds(STAMP, Instant.parse("2026-11-10T12:00:59Z")))
        assertEquals(0, AccountsRules.resendWaitSeconds(STAMP, Instant.parse("2026-11-10T12:01:00Z")))
        assertEquals(0, AccountsRules.resendWaitSeconds("no-fecha", Instant.parse(STAMP)))
    }

    @Test fun onlyPendingOrExpiredInvitationsCanBeResentOrRevoked() = runTest {
        val repo = FakeAccountsRepository()
        for (status in listOf(InvitationStatus.ACCEPTED, InvitationStatus.REVOKED)) {
            assertEquals(AccountFailure.INVITATION_NOT_PENDING, (ResendInvitation(repo)("o1", invitation(status = status)) as AccountsResult.Failure).reason)
            assertEquals(AccountFailure.INVITATION_NOT_PENDING, (RevokeInvitation(repo)("o1", invitation(status = status)) as AccountsResult.Failure).reason)
        }
        assertTrue(ResendInvitation(repo)("o1", invitation(status = InvitationStatus.EXPIRED)) is AccountsResult.Success)
    }

    @Test fun invitationValidatesEmailAndLocations() = runTest {
        val repo = FakeAccountsRepository()
        val result = InviteMember(repo)("o1", "id", "no-correo", MemberRole.CHEF, emptyList())
        assertTrue(result is AccountsResult.Failure && "email" in result.fields && "locationIds" in result.fields)
        assertTrue(repo.inviteIds.isEmpty())
    }

    @Test fun locationRulesRequireAValidTimezone() {
        assertTrue(AccountsRules.location(location().copy(timezone = "no valida")).containsKey("timezone"))
        assertTrue(AccountsRules.location(location()).isEmpty())
    }
}
