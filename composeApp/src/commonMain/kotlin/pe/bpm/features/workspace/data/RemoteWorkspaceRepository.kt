package pe.bpm.features.workspace.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result

class RemoteWorkspaceRepository(private val client: HttpClient, private val config: AppConfig, private val token: () -> String?) : WorkspaceRepository {
    override suspend fun organizations(): Result<List<Organization>> = request {
        val response = client.get("${config.apiBaseUrl}/api/v1/organizations") { bearerAuth(requireNotNull(token())) }
        if (response.status.isSuccess()) Result.Success(response.body<Envelope<List<OrgDto>>>().data.map { it.domain() }) else failure(response)
    }
    override suspend fun workspace(id: String): Result<Workspace> = request {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val response = client.get("${config.apiBaseUrl}/api/v1/organizations/$id/workspace") { bearerAuth(requireNotNull(token())) }
        if (response.status.isSuccess()) Result.Success(response.body<Envelope<WorkspaceDto>>().data.domain()) else failure(response)
    }
    override suspend fun createOrganization(input: Organization) = post("organizations", OrgDto(input.id,input.legalName,input.tradeName,input.businessType,input.taxId,input.contactName,input.contactPhone,input.contactEmail))
    override suspend fun createLocation(organizationId: String,input: Location) = post("organizations/$organizationId/locations", LocationDto(input.id,input.name,input.address,input.district,input.contactPhone))
    override suspend fun addMember(organizationId: String,email: String,role: String,locations: List<String>) = post("organizations/$organizationId/members", MemberRequest(email,role,locations))
    private suspend inline fun <reified T> post(path: String, payload: T): Result<Unit> = request {
        val response = client.post("${config.apiBaseUrl}/api/v1/$path") { bearerAuth(requireNotNull(token())); contentType(ContentType.Application.Json); setBody(payload) }
        if (response.status.isSuccess()) Result.Success(Unit) else failure(response)
    }
    private suspend fun <T> request(block: suspend () -> Result<T>): Result<T> {
        if (token().isNullOrBlank()) return Result.Failure(listOf(WorkspaceError("UNAUTHENTICATED","Tu sesión terminó. Vuelve a iniciar sesión.")))
        return try { block() } catch(e: CancellationException) { throw e } catch (_: Exception) {
            Result.Failure(listOf(WorkspaceError("CONNECTION_ERROR","No pudimos conectar. Revisa tu conexión y vuelve a intentar.")))
        }
    }
    private suspend fun failure(response: HttpResponse): Result.Failure {
        val dto = try { response.body<ErrorEnvelope>() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val code = when(response.status.value) {401->"UNAUTHENTICATED";403->"ACCESS_DENIED";404->"MEMBER_NOT_AVAILABLE";409->"RESOURCE_CONFLICT";422->"VALIDATION_ERROR";429->"RATE_LIMITED";else->"SERVICE_UNAVAILABLE"}
        val message = when(code) {
            "UNAUTHENTICATED"->"Tu sesión terminó. Vuelve a iniciar sesión."
            "ACCESS_DENIED"->"No tienes permisos para realizar esta acción."
            "MEMBER_NOT_AVAILABLE"->"La persona debe crear y verificar primero su cuenta BPM."
            "RESOURCE_CONFLICT"->"El registro ya existe. Actualiza antes de volver a intentar."
            "VALIDATION_ERROR"->"Revisa los campos indicados."
            "RATE_LIMITED"->"Espera un momento antes de volver a intentar."
            else->"El servicio no está disponible. Inténtalo de nuevo."
        }
        val allowed = setOf("legalName","tradeName","taxId","businessType","contactName","contactPhone","contactEmail","name","address","district","email","role","locationIds")
        val fields = if (code=="VALIDATION_ERROR") dto?.errors.orEmpty().flatMap { it.fields.keys }.filter { it in allowed }.associateWith { "Revisa este campo y vuelve a intentar." } else emptyMap()
        return Result.Failure(listOf(WorkspaceError(code,message,fields)))
    }
}
@Serializable private data class Envelope<T>(val data: T)
@Serializable private data class ErrorEnvelope(val errors: List<ErrorDto> = emptyList())
@Serializable private data class ErrorDto(val code: String = "",val fields: Map<String,String> = emptyMap())
@Serializable private data class OrgDto(val id: String,val legalName: String,val tradeName: String="",val businessType: String="RESTAURANT",val taxId: String="",val contactName: String="",val contactPhone: String="",val contactEmail: String="") {
 fun domain()=Organization(id,legalName,tradeName,businessType,taxId,contactName,contactPhone,contactEmail)
}
@Serializable private data class LocationDto(val id:String,val name:String,val address:String,val district:String="",val contactPhone:String="") {fun domain()=Location(id,name,address,district,contactPhone)}
@Serializable private data class MemberDto(val id:String,val name:String,val email:String,val role:String,val locationIds:List<String> = emptyList()) {fun domain()=Member(id,name,email,role,locationIds)}
@Serializable private data class EntryDto(val id:String,val title:String,val detail:String,val status:String) {fun domain()=Entry(id,title,detail,status)}
@Serializable private data class WorkspaceDto(val organization:OrgDto,val role:String,val locations:List<LocationDto>,val members:List<MemberDto>,val courses:List<EntryDto>,val checklists:List<EntryDto>,val incidents:List<EntryDto>) {fun domain()=Workspace(organization.domain(),role,locations.map{it.domain()},members.map{it.domain()},courses.map{it.domain()},checklists.map{it.domain()},incidents.map{it.domain()})}
@Serializable private data class MemberRequest(val email:String,val role:String,val locationIds:List<String>)
