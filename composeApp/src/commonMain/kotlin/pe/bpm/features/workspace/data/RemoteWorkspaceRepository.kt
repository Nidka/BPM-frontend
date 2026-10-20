package pe.bpm.features.workspace.data

import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.PageMetadata
import pe.bpm.core.network.pageParameters
import pe.bpm.core.network.checkedPage
import pe.bpm.core.network.validatePageToken
import io.ktor.serialization.JsonConvertException
import kotlinx.serialization.SerializationException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result

class RemoteWorkspaceRepository(private val client: HttpClient, private val config: AppConfig, private val onSessionRejected: (String) -> Unit = {}, private val onSessionResponse: (HttpResponse) -> Unit = {}, private val token: suspend () -> String?) : WorkspaceRepository, MemberEligibilityReader {
    override suspend fun checkMember(organizationId: String, email: String): Result<MemberEligibility> = request { accessToken ->
        require(organizationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val response = client.post("${config.apiBaseUrl}/api/v1/organizations/$organizationId/members/check") {
            bearerAuth(accessToken); contentType(ContentType.Application.Json); setBody(MemberCheckRequest(email))
        }
        onSessionResponse(response)
        if (response.status.isSuccess()) Result.Success(MemberEligibility.valueOf(response.body<Envelope<MemberCheckDto>>().data.status))
        else failure(response, accessToken)
    }
    override suspend fun organizations(request: PageRequest): Result<Page<Organization>> = request { accessToken ->
        val response = client.get("${config.apiBaseUrl}/api/v1/organizations") { bearerAuth(accessToken); pageParameters(request) }
        onSessionResponse(response)
        if (response.status.isSuccess()) response.body<Envelope<List<OrgDto>>>().let { Result.Success(checkedPage(it.data.map { v -> v.domain() }, it.pagination, request)) } else failure(response, accessToken, paged = true)
    }
    override suspend fun workspace(id: String): Result<Workspace> = request { accessToken ->
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val response = client.get("${config.apiBaseUrl}/api/v1/organizations/$id/workspace") { bearerAuth(accessToken); parameter("pageSize", 25) }
        onSessionResponse(response)
        if (response.status.isSuccess()) Result.Success(response.body<Envelope<WorkspaceDto>>().data.domain()) else failure(response, accessToken, paged = true)
    }
    override suspend fun page(organizationId: String, resource: WorkspaceResource, request: PageRequest): Result<WorkspaceItems> = request { accessToken ->
        require(organizationId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val response = client.get("${config.apiBaseUrl}/api/v1/organizations/$organizationId/${resource.path}") { bearerAuth(accessToken); pageParameters(request) }
        onSessionResponse(response)
        if (!response.status.isSuccess()) failure(response, accessToken, paged = true)
        else Result.Success(when (resource) {
            WorkspaceResource.Locations -> response.body<Envelope<List<LocationDto>>>().let { WorkspaceItems.Locations(checkedPage(it.data.map(LocationDto::domain), it.pagination, request)) }
            WorkspaceResource.Members -> response.body<Envelope<List<MemberDto>>>().let { WorkspaceItems.Members(checkedPage(it.data.map(MemberDto::domain), it.pagination, request)) }
            else -> response.body<Envelope<List<EntryDto>>>().let { WorkspaceItems.Entries(checkedPage(it.data.map(EntryDto::domain), it.pagination, request)) }
        })
    }
    override suspend fun createOrganization(input: Organization) = post("organizations", OrgDto(input.id,input.legalName,input.tradeName,input.businessType,input.taxId,input.contactName,input.contactPhone,input.contactEmail))
    override suspend fun createLocation(organizationId: String,input: Location) = post("organizations/$organizationId/locations", LocationDto(input.id,input.name,input.address,input.district,input.contactPhone))
    override suspend fun addMember(organizationId: String,email: String,role: String,locations: List<String>) = post("organizations/$organizationId/members", MemberRequest(email,role,locations))
    private suspend inline fun <reified T> post(path: String, payload: T): Result<Unit> = request { accessToken ->
        val response = client.post("${config.apiBaseUrl}/api/v1/$path") { bearerAuth(accessToken); contentType(ContentType.Application.Json); setBody(payload) }
        onSessionResponse(response)
        if (response.status.isSuccess()) Result.Success(Unit) else failure(response, accessToken, memberRequest = path.endsWith("/members"))
    }
    private suspend fun <T> request(block: suspend (String) -> Result<T>): Result<T> {
        return try {
            val accessToken = token()?.takeIf { it.isNotBlank() }
                ?: return Result.Failure(listOf(WorkspaceError("UNAUTHENTICATED", "Tu sesión terminó. Vuelve a iniciar sesión.")))
            block(accessToken)
        } catch(e: CancellationException) { throw e } catch (e: SessionAccessException) {
            Result.Failure(listOf(WorkspaceError(if (e.expired) "UNAUTHENTICATED" else "SERVICE_UNAVAILABLE",
                if (e.expired) "Tu sesión terminó. Vuelve a iniciar sesión." else "No pudimos renovar la sesión. Revisa tu conexión y vuelve a intentar.")))
        } catch (_: SerializationException) { invalidResponse() }
        catch (_: JsonConvertException) { invalidResponse() }
        catch (_: IllegalArgumentException) { invalidResponse() }
        catch (_: Throwable) {
            Result.Failure(listOf(WorkspaceError("CONNECTION_ERROR","No pudimos conectar. Revisa tu conexión y vuelve a intentar.")))
        }
    }
    private fun invalidResponse() = Result.Failure(listOf(WorkspaceError("INVALID_RESPONSE", "No pudimos interpretar la información. Actualiza la lista y vuelve a intentar.")))
    private suspend fun failure(response: HttpResponse, accessToken: String, paged: Boolean = false, memberRequest: Boolean = false): Result.Failure {
        if (response.status.value == 401) onSessionRejected(accessToken)
        val dto = try { response.body<ErrorEnvelope>() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val code = when(response.status.value) {400->if (paged) "INVALID_PAGINATION" else "INVALID_REQUEST";401->"UNAUTHENTICATED";403->"ACCESS_DENIED";404->if (memberRequest) "MEMBER_NOT_AVAILABLE" else "RESOURCE_NOT_AVAILABLE";409->"RESOURCE_CONFLICT";422->"VALIDATION_ERROR";429->"RATE_LIMITED";else->"SERVICE_UNAVAILABLE"}
        val message = when(code) {
            "INVALID_REQUEST"->"No pudimos enviar el formulario. Revisa los datos y vuelve a intentar."
            "RESOURCE_NOT_AVAILABLE"->"El recurso ya no está disponible. Actualiza la información."
            "INVALID_PAGINATION"->"La lista cambió. Actualízala para volver a empezar."
            "UNAUTHENTICATED"->"Tu sesión terminó. Vuelve a iniciar sesión."
            "ACCESS_DENIED"->"No tienes permisos para realizar esta acción."
            "MEMBER_NOT_AVAILABLE"->"No encontramos una cuenta BPM activa con ese correo. La persona debe crear su cuenta, confirmar su correo e iniciar sesión en BPM antes de que la agregues al equipo."
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
@Serializable private data class Envelope<T>(val data: T, val pagination: PageMetadata = PageMetadata())
@Serializable private data class ErrorEnvelope(val errors: List<ErrorDto> = emptyList())
@Serializable private data class ErrorDto(val code: String = "",val fields: Map<String,String> = emptyMap())
@Serializable private data class OrgDto(val id: String,val legalName: String,val tradeName: String="",val businessType: String="RESTAURANT",val taxId: String="",val contactName: String="",val contactPhone: String="",val contactEmail: String="") {
 fun domain()=Organization(id,legalName,tradeName,businessType,taxId,contactName,contactPhone,contactEmail)
}
@Serializable private data class LocationDto(val id:String,val name:String,val address:String,val district:String="",val contactPhone:String="") {fun domain()=Location(id,name,address,district,contactPhone)}
@Serializable private data class MemberDto(val id:String,val name:String,val email:String,val role:String,val locationIds:List<String> = emptyList()) {fun domain()=Member(id,name,email,role,locationIds)}
@Serializable private data class EntryDto(val id:String,val title:String,val detail:String,val status:String) {fun domain()=Entry(id,title,detail,status)}
@Serializable private data class WorkspaceDto(val organization:OrgDto,val role:String,val locations:List<LocationDto>,val members:List<MemberDto>,val courses:List<EntryDto>,val checklists:List<EntryDto>,val incidents:List<EntryDto>,val nextPageTokens:Map<String,String> = emptyMap(),val totals:Map<String,Int> = emptyMap()) {fun domain(): Workspace {
    require(listOf(locations.size, members.size, courses.size, checklists.size, incidents.size).all { it <= 100 })
    nextPageTokens.values.forEach(::validatePageToken)
    require(totals.values.all { it >= 0 })
    return Workspace(organization.domain(),role,locations.map{it.domain()},members.map{it.domain()},courses.map{it.domain()},checklists.map{it.domain()},incidents.map{it.domain()},nextPageTokens,totals)
 }}
@Serializable private data class MemberRequest(val email:String,val role:String,val locationIds:List<String>)
@Serializable private data class MemberCheckRequest(val email: String)
@Serializable private data class MemberCheckDto(val status: String)
