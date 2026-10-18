package pe.bpm.features.workspace.domain

import pe.bpm.core.domain.PageRequest

class LoadOrganizations(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(request: PageRequest = PageRequest()) = repository.organizations(request)
}

class LoadWorkspace(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(id: String) = repository.workspace(id)
}

class CreateOrganization(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(input: Organization) = repository.createOrganization(input)
}

class CreateLocation(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(organizationId: String, input: Location) =
        repository.createLocation(organizationId, input)
}

class AddMember(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(organizationId: String, email: String, role: String, locations: List<String>) =
        repository.addMember(organizationId, email.trim(), role, locations)
}

class LoadWorkspacePage(private val repository: WorkspaceRepository) {
    suspend operator fun invoke(organizationId: String, resource: WorkspaceResource, request: PageRequest) = repository.page(organizationId, resource, request)
}
