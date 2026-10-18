package pe.bpm.features.workspace.domain

import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest

interface WorkspaceRepository {
    suspend fun organizations(request: PageRequest = PageRequest()): Result<Page<Organization>>
    suspend fun page(organizationId: String, resource: WorkspaceResource, request: PageRequest): Result<WorkspaceItems>
    suspend fun workspace(id: String): Result<Workspace>
    suspend fun createOrganization(input: Organization): Result<Unit>
    suspend fun createLocation(organizationId: String, input: Location): Result<Unit>
    suspend fun addMember(organizationId: String, email: String, role: String, locations: List<String>): Result<Unit>
}

enum class WorkspaceResource(val path: String) { Locations("locations"), Members("members"), Courses("courses"), Incidents("incidents") }
sealed interface WorkspaceItems {
    data class Locations(val page: Page<Location>) : WorkspaceItems
    data class Members(val page: Page<Member>) : WorkspaceItems
    data class Entries(val page: Page<Entry>) : WorkspaceItems
}
