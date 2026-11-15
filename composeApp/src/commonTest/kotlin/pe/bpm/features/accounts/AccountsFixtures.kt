package pe.bpm.features.accounts

import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.accounts.domain.*

const val STAMP = "2026-11-10T12:00:00Z"
const val TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCde"

fun member(id: String = "u1", role: MemberRole = MemberRole.CHEF, status: RecordStatus = RecordStatus.ACTIVE, version: Long = 1, locations: List<String> = listOf("l1")) =
    AccountMember(id, "Ana $id", "$id@example.com", role, locations, status, version)

fun location(id: String = "l1", status: RecordStatus = RecordStatus.ACTIVE) = AccountLocation(id, "Sede $id", "Calle 1", "", "", "America/Lima", status, 1)
fun invitation(id: String = "i1", status: InvitationStatus = InvitationStatus.PENDING, lastSent: String = STAMP) =
    Invitation(id, "$id@example.com", MemberRole.WORKER, listOf("l1"), status, "2026-11-17T12:00:00Z", lastSent, STAMP, 1, 1)
const val ACCEPT_URL = "https://app.bpm.example/cuenta/invitacion?token=$TOKEN"
fun shared(share: InvitationShare = InvitationShare.Link(ACCEPT_URL), id: String = "i1") = SharedInvitation(invitation(id), share)
fun organization() = AccountOrganization("o1", "BPM SAC", "", "", BusinessType.RESTAURANT, "", "", "", 5)

/** Repositorio de prueba: cada resultado es reemplazable y se registran las llamadas. */
class FakeAccountsRepository : AccountsRepository {
    val calls = mutableListOf<String>()
    var members: (RecordStatus) -> AccountsResult<Page<AccountMember>> = { AccountsResult.Success(Page(listOf(member()))) }
    var locations: AccountsResult<Page<AccountLocation>> = AccountsResult.Success(Page(listOf(location())))
    var updateMember: AccountsResult<AccountMember> = AccountsResult.Success(member())
    var deactivate: AccountsResult<AccountMember> = AccountsResult.Success(member(status = RecordStatus.INACTIVE))
    var transfer: AccountsResult<AdminTransfer> = AccountsResult.Success(AdminTransfer(member(role = MemberRole.ADMIN), null))
    var invitations: AccountsResult<Page<Invitation>> = AccountsResult.Success(Page(listOf(invitation())))
    var invite: AccountsResult<SharedInvitation> = AccountsResult.Success(shared())
    var resend: AccountsResult<SharedInvitation> = AccountsResult.Success(shared())
    var accept: AccountsResult<AcceptedInvitation> = AccountsResult.Success(AcceptedInvitation("o1", MemberRole.CHEF, listOf("l1")))
    var devices: AccountsResult<Page<AccountDevice>> = AccountsResult.Success(Page(emptyList()))
    val inviteIds = mutableListOf<String>()
    val memberRequests = mutableListOf<Triple<MemberRole, List<String>, Long>>()
    var transferActor: Pair<MemberRole?, List<String>>? = null
    val acceptedTokens = mutableListOf<String>()

    override suspend fun organization(organizationId: String) = AccountsResult.Success(organization())
    override suspend fun updateOrganization(organizationId: String, input: AccountOrganization) = AccountsResult.Success(input.copy(rowVersion = input.rowVersion + 1))
    override suspend fun locations(organizationId: String, status: RecordStatus, request: PageRequest) = locations
    override suspend fun updateLocation(organizationId: String, input: AccountLocation) = AccountsResult.Success(input.copy(rowVersion = input.rowVersion + 1))
    override suspend fun members(organizationId: String, status: RecordStatus, request: PageRequest): AccountsResult<Page<AccountMember>> { calls += "members:$status"; return members(status) }
    override suspend fun updateMember(organizationId: String, userId: String, role: MemberRole, locationIds: List<String>, rowVersion: Long): AccountsResult<AccountMember> {
        memberRequests += Triple(role, locationIds, rowVersion); return updateMember
    }
    override suspend fun deactivateMember(organizationId: String, userId: String, rowVersion: Long) = deactivate.also { calls += "deactivate:$rowVersion" }
    override suspend fun reactivateMember(organizationId: String, userId: String, rowVersion: Long) = AccountsResult.Success(member()).also { calls += "reactivate:$rowVersion" }
    override suspend fun transferAdmin(organizationId: String, userId: String, rowVersion: Long, actorRole: MemberRole?, actorLocationIds: List<String>): AccountsResult<AdminTransfer> {
        transferActor = actorRole to actorLocationIds; return transfer
    }
    override suspend fun invitations(organizationId: String, status: InvitationStatus?, request: PageRequest) = invitations
    override suspend fun invite(organizationId: String, id: String, email: String, role: MemberRole, locationIds: List<String>): AccountsResult<SharedInvitation> { inviteIds += id; return invite }
    override suspend fun resendInvitation(organizationId: String, id: String) = resend.also { calls += "resend:$id" }
    override suspend fun revokeInvitation(organizationId: String, id: String) = AccountsResult.Success(invitation(status = InvitationStatus.REVOKED)).also { calls += "revoke:$id" }
    override suspend fun acceptInvitation(token: String): AccountsResult<AcceptedInvitation> { acceptedTokens += token; return accept }
    override suspend fun devices(request: PageRequest) = devices
    override suspend fun revokeDevice(deviceId: String) = AccountsResult.Success(AccountDevice(deviceId, "o1", "BPM", DevicePlatform.WEB, RecordStatus.INACTIVE, null, STAMP)).also { calls += "revokeDevice:$deviceId" }
}
