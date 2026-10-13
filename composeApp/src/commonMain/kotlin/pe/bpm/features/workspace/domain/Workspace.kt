package pe.bpm.features.workspace.domain

data class Organization(val id: String, val legalName: String, val tradeName: String = "", val businessType: String = "RESTAURANT", val taxId: String = "", val contactName: String = "", val contactPhone: String = "", val contactEmail: String = "") {
    val name get() = tradeName.ifBlank { legalName }
}
data class Location(val id: String, val name: String, val address: String, val district: String = "", val contactPhone: String = "")
data class Member(val id: String, val name: String, val email: String, val role: String, val locationIds: List<String>)
data class Entry(val id: String, val title: String, val detail: String, val status: String)
data class Workspace(val organization: Organization, val role: String, val locations: List<Location>, val members: List<Member>, val courses: List<Entry>, val checklists: List<Entry>, val incidents: List<Entry>) {
    val canManage get() = role in setOf("ADMIN", "SUPER_ADMIN")
}
data class WorkspaceError(val code: String, val message: String, val fields: Map<String, String> = emptyMap())
sealed interface Result<out T> {
    data class Success<T>(val value: T) : Result<T>
    data class Failure(val errors: List<WorkspaceError>) : Result<Nothing>
}
interface WorkspaceRepository {
    suspend fun organizations(): Result<List<Organization>>
    suspend fun workspace(id: String): Result<Workspace>
    suspend fun createOrganization(input: Organization): Result<Unit>
    suspend fun createLocation(organizationId: String, input: Location): Result<Unit>
    suspend fun addMember(organizationId: String, email: String, role: String, locations: List<String>): Result<Unit>
}
class LoadOrganizations(private val repository: WorkspaceRepository) { suspend operator fun invoke() = repository.organizations() }
class LoadWorkspace(private val repository: WorkspaceRepository) { suspend operator fun invoke(id: String) = repository.workspace(id) }
class CreateOrganization(private val repository: WorkspaceRepository) { suspend operator fun invoke(input: Organization) = repository.createOrganization(input) }
class CreateLocation(private val repository: WorkspaceRepository) { suspend operator fun invoke(id: String, input: Location) = repository.createLocation(id, input) }
class AddMember(private val repository: WorkspaceRepository) { suspend operator fun invoke(id: String, email: String, role: String, locations: List<String>) = repository.addMember(id, email.trim(), role, locations) }
