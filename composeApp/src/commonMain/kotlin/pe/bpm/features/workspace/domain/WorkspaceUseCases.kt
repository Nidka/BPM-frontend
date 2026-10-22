package pe.bpm.features.workspace.domain

import pe.bpm.core.domain.PageRequest

class LoadOrganizations(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(request: PageRequest = PageRequest()) = repository.organizations(request)
}

class LoadWorkspace(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(id: String) = repository.workspace(id)
}

class CreateOrganization(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(input: Organization): Result<Unit> {
        val fields = WorkspaceInputRules.organization(input)
        if (fields.isNotEmpty()) return validationFailure(fields)
        return repository.createOrganization(input.copy(legalName = input.legalName.trim(), tradeName = input.tradeName.trim(),
            taxId = input.taxId.trim(), contactName = input.contactName.trim(), contactPhone = input.contactPhone.trim(), contactEmail = input.contactEmail.trim()))
    }
}

class CreateLocation(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(organizationId: String, input: Location): Result<Unit> {
        val fields = WorkspaceInputRules.location(input)
        if (fields.isNotEmpty()) return validationFailure(fields)
        return repository.createLocation(organizationId, input.copy(name = input.name.trim(), address = input.address.trim(),
            district = input.district.trim(), contactPhone = input.contactPhone.trim()))
    }
}

class AddMember(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(organizationId: String, email: String, role: String, locations: List<String>): Result<Unit> {
        val fields = buildMap {
            WorkspaceInputRules.memberEmail.error(email)?.let { put("email", it) }
            if (role !in setOf("ADMIN", "CHEF", "WORKER")) put("role", "Selecciona un rol válido.")
            if (locations.size > 100 || role != "ADMIN" && locations.isEmpty()) put("locationIds", "Selecciona entre 1 y 100 sedes.")
        }
        if (fields.isNotEmpty()) return validationFailure(fields)
        return repository.addMember(organizationId, email.trim(), role, locations)
    }
}

class LoadWorkspacePage(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(organizationId: String, resource: WorkspaceResource, request: PageRequest) = repository.page(organizationId, resource, request)
}
