package pe.bpm.features.accounts.domain

import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest

/** Puerto de cuentas; el tenant siempre es el de la membresía autenticada y el backend lo vuelve a validar. */
interface AccountsRepository {
    suspend fun organization(organizationId: String): AccountsResult<AccountOrganization>
    suspend fun updateOrganization(organizationId: String, input: AccountOrganization): AccountsResult<AccountOrganization>
    suspend fun locations(organizationId: String, status: RecordStatus, request: PageRequest): AccountsResult<Page<AccountLocation>>
    suspend fun updateLocation(organizationId: String, input: AccountLocation): AccountsResult<AccountLocation>
    suspend fun members(organizationId: String, status: RecordStatus, request: PageRequest): AccountsResult<Page<AccountMember>>
    suspend fun updateMember(organizationId: String, userId: String, role: MemberRole, locationIds: List<String>, rowVersion: Long): AccountsResult<AccountMember>
    suspend fun deactivateMember(organizationId: String, userId: String, rowVersion: Long): AccountsResult<AccountMember>
    suspend fun reactivateMember(organizationId: String, userId: String, rowVersion: Long): AccountsResult<AccountMember>
    suspend fun transferAdmin(organizationId: String, userId: String, rowVersion: Long, actorRole: MemberRole?, actorLocationIds: List<String>): AccountsResult<AdminTransfer>
    suspend fun invitations(organizationId: String, status: InvitationStatus?, request: PageRequest): AccountsResult<Page<Invitation>>
    suspend fun invite(organizationId: String, id: String, email: String, role: MemberRole, locationIds: List<String>): AccountsResult<Invitation>
    suspend fun resendInvitation(organizationId: String, id: String): AccountsResult<Invitation>
    suspend fun revokeInvitation(organizationId: String, id: String): AccountsResult<Invitation>
    suspend fun acceptInvitation(token: String): AccountsResult<AcceptedInvitation>
    suspend fun devices(request: PageRequest): AccountsResult<Page<AccountDevice>>
    suspend fun revokeDevice(deviceId: String): AccountsResult<AccountDevice>
}
