package pe.bpm.features.accounts.domain

import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest

private fun invalid(fields: Map<String, String>) = AccountsResult.Failure(AccountFailure.VALIDATION, fields)

class LoadOrganizationAccount(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String) = repository.organization(organizationId)
}

class UpdateOrganizationAccount(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, input: AccountOrganization): AccountsResult<AccountOrganization> {
        val fields = AccountsRules.organization(input)
        if (fields.isNotEmpty()) return invalid(fields)
        return repository.updateOrganization(organizationId, input.copy(legalName = input.legalName.trim(), tradeName = input.tradeName.trim(),
            taxId = input.taxId.trim(), contactName = input.contactName.trim(), contactPhone = input.contactPhone.trim(), contactEmail = input.contactEmail.trim()))
    }
}

class ListAccountLocations(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, status: RecordStatus, request: PageRequest): AccountsResult<Page<AccountLocation>> =
        repository.locations(organizationId, status, request)
}

class UpdateAccountLocation(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, input: AccountLocation): AccountsResult<AccountLocation> {
        val fields = AccountsRules.location(input)
        if (fields.isNotEmpty()) return invalid(fields)
        return repository.updateLocation(organizationId, input.copy(name = input.name.trim(), address = input.address.trim(),
            district = input.district.trim(), contactPhone = input.contactPhone.trim(), timezone = input.timezone.trim()))
    }
}

class ListAccountMembers(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, status: RecordStatus, request: PageRequest): AccountsResult<Page<AccountMember>> =
        repository.members(organizationId, status, request)
}

class UpdateAccountMember(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, member: AccountMember, role: MemberRole, locationIds: List<String>): AccountsResult<AccountMember> {
        AccountsRules.locationsFor(role, locationIds)?.let { return invalid(mapOf("locationIds" to it)) }
        // ADMIN ve todas las sedes: no se envía una selección que el servidor ignoraría.
        return repository.updateMember(organizationId, member.id, role, if (role == MemberRole.ADMIN) emptyList() else locationIds, member.rowVersion)
    }
}

/** Baja lógica o reactivación; el servidor es idempotente y valida la regla del último administrador. */
class SetMemberActive(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, member: AccountMember, active: Boolean): AccountsResult<AccountMember> =
        if (active) repository.reactivateMember(organizationId, member.id, member.rowVersion)
        else repository.deactivateMember(organizationId, member.id, member.rowVersion)
}

class TransferAdministration(private val repository: AccountsRepository) {
    /** [actorIsMember] es falso para un SUPER_ADMIN que no pertenece a la organización. */
    suspend operator fun invoke(organizationId: String, target: AccountMember, actorIsMember: Boolean, actorRole: MemberRole?, actorLocationIds: List<String>): AccountsResult<AdminTransfer> {
        if (target.status != RecordStatus.ACTIVE) return AccountsResult.Failure(AccountFailure.MEMBER_INACTIVE)
        val fields = AccountsRules.transfer(actorIsMember, actorRole, actorLocationIds)
        if (fields.isNotEmpty()) return invalid(fields)
        return repository.transferAdmin(organizationId, target.id, target.rowVersion, actorRole.takeIf { actorIsMember }, if (actorIsMember) actorLocationIds else emptyList())
    }
}

class ListInvitations(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, status: InvitationStatus?, request: PageRequest) = repository.invitations(organizationId, status, request)
}

class InviteMember(private val repository: AccountsRepository) {
    /** [id] lo genera el cliente: repetirlo con el mismo contenido no duplica la invitación. */
    suspend operator fun invoke(organizationId: String, id: String, email: String, role: MemberRole, locationIds: List<String>): AccountsResult<SharedInvitation> {
        val fields = AccountsRules.invitation(email, role, locationIds)
        if (fields.isNotEmpty()) return invalid(fields)
        return repository.invite(organizationId, id, email.trim(), role, if (role == MemberRole.ADMIN) emptyList() else locationIds)
    }
}

class ResendInvitation(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, invitation: Invitation): AccountsResult<SharedInvitation> =
        if (!AccountsRules.canResend(invitation.status)) AccountsResult.Failure(AccountFailure.INVITATION_NOT_PENDING)
        else repository.resendInvitation(organizationId, invitation.id)
}

class RevokeInvitation(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String, invitation: Invitation): AccountsResult<Invitation> =
        if (!AccountsRules.canRevoke(invitation.status)) AccountsResult.Failure(AccountFailure.INVITATION_NOT_PENDING)
        else repository.revokeInvitation(organizationId, invitation.id)
}

class AcceptInvitation(private val repository: AccountsRepository) {
    /** Acepta un token escrito, pegado o recibido en el enlace del correo. */
    suspend operator fun invoke(raw: String): AccountsResult<AcceptedInvitation> {
        val token = AccountsRules.tokenFromPaste(raw) ?: return invalid(mapOf("token" to "El código de la invitación no es válido. Cópialo completo del correo."))
        return repository.acceptInvitation(token)
    }
}

class ListMyDevices(private val repository: AccountsRepository) {
    suspend operator fun invoke(request: PageRequest) = repository.devices(request)
}

class RevokeMyDevice(private val repository: AccountsRepository) {
    suspend operator fun invoke(deviceId: String) = repository.revokeDevice(deviceId)
}

/** Sedes activas para asignar a personas: hasta 5 páginas de 100; devuelve las leídas o el primer fallo. */
class LoadActiveLocations(private val repository: AccountsRepository) {
    suspend operator fun invoke(organizationId: String): AccountsResult<List<AccountLocation>> {
        val found = mutableListOf<AccountLocation>()
        var request = PageRequest(size = 100)
        repeat(5) {
            when (val result = repository.locations(organizationId, RecordStatus.ACTIVE, request)) {
                is AccountsResult.Failure -> return result
                is AccountsResult.Success -> {
                    found += result.value.items
                    if (result.value.nextToken.isEmpty()) return AccountsResult.Success(found)
                    request = request.copy(token = result.value.nextToken)
                }
            }
        }
        return AccountsResult.Success(found)
    }
}
