package pe.bpm.features.accounts.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.PageMetadata
import pe.bpm.core.network.SessionAccessException
import pe.bpm.core.network.checkedPage
import pe.bpm.core.network.pageParameters
import pe.bpm.features.accounts.domain.*

/**
 * Adaptador HTTP de cuentas (ORGANIZATIONS.md del backend). Traduce DTO a dominio, valida las respuestas
 * y reduce cualquier error a un motivo público; nunca registra tokens, cuerpos ni el token de invitación.
 */
class RemoteAccountsRepository(
    private val client: HttpClient, private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {}, private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : AccountsRepository {
    private val api get() = "${config.apiBaseUrl}/api/v1"
    private fun org(id: String) = "$api/organizations/${safe(id)}"
    private fun safe(id: String): String { require(idPattern.matches(id)); return id }

    override suspend fun organization(organizationId: String): AccountsResult<AccountOrganization> = guarded {
        call<WorkspaceDto, AccountOrganization>(HttpMethod.Get, "${org(organizationId)}/workspace", params = { parameter("pageSize", 1) }) { it.organization.domain() }
    }

    override suspend fun updateOrganization(organizationId: String, input: AccountOrganization) = guarded {
        call<OrganizationDto, AccountOrganization>(HttpMethod.Patch, org(organizationId),
            OrganizationPatch(input.legalName, input.tradeName, input.taxId, input.businessType.name, input.contactName, input.contactPhone, input.contactEmail, input.rowVersion)) { it.domain() }
    }

    override suspend fun locations(organizationId: String, status: RecordStatus, request: PageRequest) = guarded {
        call<List<LocationDto>, Page<AccountLocation>>(HttpMethod.Get, "${org(organizationId)}/locations", params = { pageParameters(request); parameter("status", status.name) }, paged = true,
            pageOf = request) { items, meta -> checkedPage(items.map(LocationDto::domain), meta, request) }
    }

    override suspend fun updateLocation(organizationId: String, input: AccountLocation) = guarded {
        call<LocationDto, AccountLocation>(HttpMethod.Patch, "${org(organizationId)}/locations/${safe(input.id)}",
            LocationPatch(input.name, input.address, input.district, input.contactPhone, input.timezone, input.status.name, input.rowVersion)) { it.domain() }
    }

    override suspend fun members(organizationId: String, status: RecordStatus, request: PageRequest) = guarded {
        call<List<MemberDto>, Page<AccountMember>>(HttpMethod.Get, "${org(organizationId)}/members", params = { pageParameters(request); parameter("status", status.name) }, paged = true,
            pageOf = request) { items, meta -> checkedPage(items.map(MemberDto::domain), meta, request) }
    }

    override suspend fun updateMember(organizationId: String, userId: String, role: MemberRole, locationIds: List<String>, rowVersion: Long) = guarded {
        call<MemberDto, AccountMember>(HttpMethod.Patch, "${org(organizationId)}/members/${safe(userId)}", MemberPatch(role.name, locationIds, rowVersion)) { it.domain() }
    }

    override suspend fun deactivateMember(organizationId: String, userId: String, rowVersion: Long) = guarded {
        call<MemberDto, AccountMember>(HttpMethod.Post, "${org(organizationId)}/members/${safe(userId)}/deactivate", VersionBody(rowVersion)) { it.domain() }
    }

    override suspend fun reactivateMember(organizationId: String, userId: String, rowVersion: Long) = guarded {
        call<MemberDto, AccountMember>(HttpMethod.Post, "${org(organizationId)}/members/${safe(userId)}/reactivate", VersionBody(rowVersion)) { it.domain() }
    }

    override suspend fun transferAdmin(organizationId: String, userId: String, rowVersion: Long, actorRole: MemberRole?, actorLocationIds: List<String>) = guarded {
        call<TransferDto, AdminTransfer>(HttpMethod.Post, "${org(organizationId)}/members/${safe(userId)}/transfer-admin",
            if (actorRole == null) VersionBody(rowVersion) else TransferBody(rowVersion, actorRole.name, actorLocationIds)) {
            AdminTransfer(it.newAdmin.domain(), it.formerAdmin?.domain()).also { result -> require(result.newAdmin.role == MemberRole.ADMIN) }
        }
    }

    override suspend fun invitations(organizationId: String, status: InvitationStatus?, request: PageRequest) = guarded {
        call<List<InvitationDto>, Page<Invitation>>(HttpMethod.Get, "${org(organizationId)}/invitations", params = {
            pageParameters(request); if (status != null) parameter("status", status.name)
        }, paged = true, pageOf = request) { items, meta -> checkedPage(items.map(InvitationDto::domain), meta, request) }
    }

    override suspend fun invite(organizationId: String, id: String, email: String, role: MemberRole, locationIds: List<String>) = guarded {
        call<InvitationDto, SharedInvitation>(HttpMethod.Post, "${org(organizationId)}/invitations", InvitationBody(id, email, role.name, locationIds)) { it.shared() }
    }

    override suspend fun resendInvitation(organizationId: String, id: String) = guarded {
        call<InvitationDto, SharedInvitation>(HttpMethod.Post, "${org(organizationId)}/invitations/${safe(id)}/resend") { it.shared() }
    }

    override suspend fun revokeInvitation(organizationId: String, id: String) = guarded {
        call<InvitationDto, Invitation>(HttpMethod.Post, "${org(organizationId)}/invitations/${safe(id)}/revoke") { it.domain() }
    }

    override suspend fun acceptInvitation(token: String): AccountsResult<AcceptedInvitation> {
        // El token solo viaja en el cuerpo; nunca en la URL ni en mensajes de error.
        if (!Regex("^[A-Za-z0-9_-]{43}$").matches(token)) return AccountsResult.Failure(AccountFailure.INVALID_RESPONSE)
        return call<AcceptedDto, AcceptedInvitation>(HttpMethod.Post, "$api/invitations/accept", AcceptBody(token)) { it.domain() }
    }

    override suspend fun devices(request: PageRequest) = guarded {
        call<List<DeviceDto>, Page<AccountDevice>>(HttpMethod.Get, "$api/me/devices", params = {
            parameter("pageSize", request.size); if (request.token.isNotEmpty()) parameter("pageToken", request.token)
        }, paged = true, pageOf = request) { items, meta -> checkedPage(items.map(DeviceDto::domain), meta, request) }
    }

    override suspend fun revokeDevice(deviceId: String) = guarded {
        call<DeviceDto, AccountDevice>(HttpMethod.Delete, "$api/me/devices/${safe(deviceId)}") { it.domain() }
    }

    /** Un identificador con caracteres de ruta se rechaza antes de enviar nada. */
    private suspend inline fun <T> guarded(block: () -> AccountsResult<T>): AccountsResult<T> =
        try { block() } catch (_: IllegalArgumentException) { AccountsResult.Failure(AccountFailure.INVALID_RESPONSE) }

    /** Llamada con un solo objeto de datos. */
    private suspend inline fun <reified D, T> call(method: HttpMethod, url: String, payload: Any? = null,
        noinline params: HttpRequestBuilder.() -> Unit = {}, crossinline map: (D) -> T): AccountsResult<T> =
        execute(method, url, payload, params, paged = false) { response -> map(response.body<Envelope<D>>().data) }

    /** Llamada paginada: devuelve los datos y los metadatos de cursor. */
    private suspend inline fun <reified D, T> call(method: HttpMethod, url: String, noinline params: HttpRequestBuilder.() -> Unit,
        paged: Boolean, pageOf: PageRequest, crossinline map: (D, PageMetadata) -> T): AccountsResult<T> {
        check(pageOf.size in 1..100)
        return execute(method, url, null, params, paged) { response -> response.body<Envelope<D>>().let { map(it.data, it.pagination) } }
    }

    private suspend fun <T> execute(method: HttpMethod, url: String, payload: Any?, params: HttpRequestBuilder.() -> Unit,
        paged: Boolean, parse: suspend (HttpResponse) -> T): AccountsResult<T> = try {
        val accessToken = token()?.takeIf { it.isNotBlank() } ?: return AccountsResult.Failure(AccountFailure.SESSION)
        val response = client.request(url) {
            this.method = method
            bearerAuth(accessToken)
            params()
            if (payload != null) { contentType(ContentType.Application.Json); setBody(payload) }
        }
        onSessionResponse(response)
        if (response.status.isSuccess()) AccountsResult.Success(parse(response)) else failure(response, accessToken, paged)
    } catch (e: CancellationException) { throw e
    } catch (e: SessionAccessException) { AccountsResult.Failure(if (e.expired) AccountFailure.SESSION else AccountFailure.UNAVAILABLE)
    } catch (_: SerializationException) { AccountsResult.Failure(AccountFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) { AccountsResult.Failure(AccountFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) { AccountsResult.Failure(AccountFailure.INVALID_RESPONSE)
    } catch (_: Throwable) { AccountsResult.Failure(AccountFailure.CONNECTION) }

    private suspend fun failure(response: HttpResponse, accessToken: String, paged: Boolean): AccountsResult.Failure {
        if (response.status.value == 401) onSessionRejected(accessToken)
        val dto = try { response.body<ErrorEnvelope>() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        val code = dto?.errors?.firstOrNull()?.code.orEmpty()
        val reason = domainCodes[code] ?: when (response.status.value) {
            400 -> if (paged) AccountFailure.INVALID_PAGINATION else AccountFailure.INVALID_REQUEST
            401 -> AccountFailure.SESSION
            403 -> AccountFailure.DENIED
            404 -> AccountFailure.NOT_FOUND
            409 -> AccountFailure.CONFLICT
            422 -> AccountFailure.VALIDATION
            429 -> AccountFailure.RATE_LIMITED
            else -> AccountFailure.UNAVAILABLE
        }
        val fields = if (reason == AccountFailure.VALIDATION) dto?.errors.orEmpty().flatMap { it.fields.keys }.filter { it in fieldNames }
            .associateWith { "Revisa este campo y vuelve a intentar." } else emptyMap()
        return AccountsResult.Failure(reason, fields)
    }

    private companion object {
        val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
        val fieldNames = setOf("legalName", "tradeName", "taxId", "businessType", "contactName", "contactPhone", "contactEmail", "name", "address",
            "district", "timezone", "status", "email", "role", "locationIds", "userId", "actorRole", "actorLocationIds", "token")
        val domainCodes = mapOf(
            "VERSION_CONFLICT" to AccountFailure.VERSION_CONFLICT, "LAST_ADMIN_REQUIRED" to AccountFailure.LAST_ADMIN,
            "MEMBER_ALREADY_EXISTS" to AccountFailure.MEMBER_ALREADY_EXISTS, "MEMBER_INACTIVE" to AccountFailure.MEMBER_INACTIVE,
            "MEMBER_NOT_AVAILABLE" to AccountFailure.MEMBER_NOT_AVAILABLE, "INVITATION_ALREADY_PENDING" to AccountFailure.ALREADY_PENDING,
            "INVITATION_NOT_FOUND" to AccountFailure.INVITATION_NOT_FOUND, "INVITATION_EXPIRED" to AccountFailure.INVITATION_EXPIRED,
            "INVITATION_ALREADY_USED" to AccountFailure.INVITATION_USED, "INVITATION_REVOKED" to AccountFailure.INVITATION_REVOKED,
            "INVITATION_NOT_PENDING" to AccountFailure.INVITATION_NOT_PENDING, "INVITATION_EMAIL_MISMATCH" to AccountFailure.EMAIL_MISMATCH,
            "INVITATION_RESEND_TOO_SOON" to AccountFailure.RESEND_TOO_SOON, "RESOURCE_CONFLICT" to AccountFailure.CONFLICT,
            "RESOURCE_NOT_FOUND" to AccountFailure.NOT_FOUND,
        )
    }
}

// ---- DTO (internos a data) --------------------------------------------------------------------

@Serializable private data class Envelope<T>(val data: T, val pagination: PageMetadata = PageMetadata())
@Serializable private data class ErrorEnvelope(val errors: List<ErrorDto> = emptyList())
@Serializable private data class ErrorDto(val code: String = "", val fields: Map<String, String> = emptyMap())

private fun instant(value: String) { Instant.parse(value) }
private fun role(value: String) = MemberRole.entries.firstOrNull { it.name == value } ?: throw IllegalArgumentException("Rol inválido")
private fun status(value: String) = RecordStatus.entries.firstOrNull { it.name == value } ?: throw IllegalArgumentException("Estado inválido")
private fun ids(values: List<String>): List<String> {
    require(values.size <= 100 && values.toSet().size == values.size && values.all { it.length in 1..64 })
    return values
}

@Serializable private data class OrganizationDto(val id: String, val legalName: String, val tradeName: String = "", val taxId: String = "",
    val businessType: String, val contactName: String = "", val contactPhone: String = "", val contactEmail: String = "", val rowVersion: Long) {
    fun domain(): AccountOrganization {
        require(rowVersion >= 1)
        return AccountOrganization(id, legalName, tradeName, taxId, BusinessType.fromCode(businessType) ?: throw IllegalArgumentException("Tipo inválido"),
            contactName, contactPhone, contactEmail, rowVersion)
    }
}
@Serializable private data class WorkspaceDto(val organization: OrganizationDto)
@Serializable private data class LocationDto(val id: String, val name: String, val address: String, val district: String = "",
    val contactPhone: String = "", val timezone: String, val status: String, val rowVersion: Long) {
    fun domain(): AccountLocation { require(rowVersion >= 1); return AccountLocation(id, name, address, district, contactPhone, timezone, status(status), rowVersion) }
}
@Serializable private data class MemberDto(val id: String, val name: String, val email: String, val role: String,
    val locationIds: List<String> = emptyList(), val status: String, val rowVersion: Long) {
    fun domain(): AccountMember { require(rowVersion >= 1); return AccountMember(id, name, email, role(role), ids(locationIds), status(status), rowVersion) }
}
@Serializable private data class TransferDto(val newAdmin: MemberDto, val formerAdmin: MemberDto? = null)
@Serializable private data class InvitationDto(val id: String, val email: String, val role: String, val locationIds: List<String> = emptyList(),
    val status: String, val expiresAt: String, val lastSentAt: String, val createdAt: String, val sendCount: Int = 0, val rowVersion: Long,
    /** Solo llegan al crear y reenviar; la lista nunca los trae y aquí se ignoran. */
    val acceptUrl: String? = null, val token: String? = null) {
    /** Invitación con su enlace; si no hay URL configurada se exige el código de 43 caracteres. */
    fun shared(): SharedInvitation {
        val share = when {
            acceptUrl != null -> InvitationShare.Link(acceptUrl.also { require(AccountsRules.acceptLink(it)) })
            token != null -> InvitationShare.Code(AccountsRules.invitationToken(token) ?: throw IllegalArgumentException("Código inválido"))
            else -> throw IllegalArgumentException("Falta el enlace de la invitación")
        }
        return SharedInvitation(domain(), share)
    }

    fun domain(): Invitation {
        require(rowVersion >= 1 && sendCount >= 0)
        instant(expiresAt); instant(lastSentAt); instant(createdAt)
        val parsed = InvitationStatus.entries.firstOrNull { it.name == status } ?: throw IllegalArgumentException("Estado inválido")
        return Invitation(id, email, role(role), ids(locationIds), parsed, expiresAt, lastSentAt, createdAt, sendCount, rowVersion)
    }
}
@Serializable private data class AcceptedDto(val organizationId: String, val role: String, val locationIds: List<String> = emptyList()) {
    fun domain() = AcceptedInvitation(organizationId, role(role), ids(locationIds))
}
@Serializable private data class DeviceDto(val id: String, val organizationId: String, val organizationName: String = "", val platform: String,
    val status: String, val lastSeenAt: String? = null, val createdAt: String) {
    fun domain(): AccountDevice {
        lastSeenAt?.let(::instant); instant(createdAt)
        return AccountDevice(id, organizationId, organizationName, DevicePlatform.entries.firstOrNull { it.name == platform } ?: throw IllegalArgumentException("Plataforma inválida"),
            status(status), lastSeenAt, createdAt)
    }
}

@Serializable private data class OrganizationPatch(val legalName: String, val tradeName: String, val taxId: String, val businessType: String,
    val contactName: String, val contactPhone: String, val contactEmail: String, val rowVersion: Long)
@Serializable private data class LocationPatch(val name: String, val address: String, val district: String, val contactPhone: String,
    val timezone: String, val status: String, val rowVersion: Long)
@Serializable private data class MemberPatch(val role: String, val locationIds: List<String>, val rowVersion: Long)
@Serializable private data class VersionBody(val rowVersion: Long)
@Serializable private data class TransferBody(val rowVersion: Long, val actorRole: String, val actorLocationIds: List<String>)
@Serializable private data class InvitationBody(val id: String, val email: String, val role: String, val locationIds: List<String>)
@Serializable private data class AcceptBody(val token: String)
