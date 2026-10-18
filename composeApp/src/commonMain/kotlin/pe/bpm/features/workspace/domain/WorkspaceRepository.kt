package pe.bpm.features.workspace.domain

interface WorkspaceRepository {
    suspend fun organizations(): Result<List<Organization>>
    suspend fun workspace(id: String): Result<Workspace>
    suspend fun createOrganization(input: Organization): Result<Unit>
    suspend fun createLocation(organizationId: String, input: Location): Result<Unit>
    suspend fun addMember(organizationId: String, email: String, role: String, locations: List<String>): Result<Unit>
}
