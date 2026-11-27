package pe.bpm.features.support.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.support.domain.*

@Serializable internal data class StaffMessageBody(val id: String, val locationId: String, val kind: String, val text: String)
@Serializable internal data class ContactEnvelope(val data: ContactDto)
@Serializable internal data class ContactDto(val name: String? = null, val role: String? = null, val whatsapp: String? = null)
@Serializable internal data class WhatsappBody(val whatsapp: String?)
@Serializable internal data class WhatsappEnvelope(val data: WhatsappDto)
@Serializable internal data class WhatsappDto(val whatsapp: String? = null)

/** «Avisar a mi jefe», WhatsApp del jefe y WhatsApp propio. No registra tokens ni mensajes. */
class RemoteTeamRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : TeamRepository {
    private val id = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val org = Regex("[A-Za-z0-9_-]{1,64}")

    override suspend fun sendStaffMessage(org: String, id: String, locationId: String, kind: StaffMessageKind, text: String, key: String): SupportResult<Unit> {
        require(this.org.matches(org) && this.id.matches(id) && this.id.matches(locationId))
        return call(HttpMethod.Post, "organizations/$org/staff-messages", StaffMessageBody(id, locationId, kind.name, text.trim().take(StaffMessageRules.TEXT_MAX)), key) { Unit }
    }

    override suspend fun supervisorContact(org: String, locationId: String): SupportResult<SupervisorContact?> {
        require(this.org.matches(org) && this.id.matches(locationId))
        return call(HttpMethod.Get, "organizations/$org/staff-messages/contact?locationId=$locationId", null, null) { r ->
            val c = r.body<ContactEnvelope>().data
            val phone = c.whatsapp?.takeIf { it.isNotBlank() } ?: return@call null
            require(phone.length in 8..15 && phone.all { it.isDigit() })
            SupervisorContact(c.name.orEmpty(), c.role.orEmpty(), phone)
        }
    }

    override suspend fun saveMyWhatsapp(raw: String?): SupportResult<String?> =
        call(HttpMethod.Put, "me/whatsapp", WhatsappBody(raw?.trim()?.takeIf { it.isNotEmpty() }), null) { it.body<WhatsappEnvelope>().data.whatsapp }

    private suspend fun <T> call(verb: HttpMethod, resource: String, body: Any?, key: String?, read: suspend (HttpResponse) -> T): SupportResult<T> = try {
        val access = token()?.takeIf { it.isNotBlank() } ?: return SupportResult.Failure(SupportFailure.SESSION)
        val response = client.request("${config.apiBaseUrl}/api/v1/$resource") {
            method = verb
            bearerAuth(access)
            key?.let { header("Idempotency-Key", it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        onSessionResponse(response)
        if (response.status.isSuccess()) SupportResult.Success(read(response))
        else {
            if (response.status.value == 401) onSessionRejected(access)
            SupportResult.Failure(when (response.status.value) {
                401 -> SupportFailure.SESSION; 403 -> SupportFailure.DENIED; 400, 422 -> SupportFailure.INVALID; else -> SupportFailure.UNAVAILABLE
            })
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: SessionAccessException) {
        SupportResult.Failure(if (error.expired) SupportFailure.SESSION else SupportFailure.CONNECTION)
    } catch (_: IllegalArgumentException) {
        SupportResult.Failure(SupportFailure.INVALID_RESPONSE)
    } catch (_: io.ktor.serialization.JsonConvertException) {
        SupportResult.Failure(SupportFailure.INVALID_RESPONSE)
    } catch (_: kotlinx.serialization.SerializationException) {
        SupportResult.Failure(SupportFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        SupportResult.Failure(SupportFailure.CONNECTION)
    }
}
