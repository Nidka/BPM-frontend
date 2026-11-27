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
import pe.bpm.core.presentation.OnboardingSync
import pe.bpm.features.support.domain.*

@Serializable internal data class SupportEnvelope(val data: SupportDto)
@Serializable internal data class SupportDto(val supportEmail: String, val supportWhatsapp: String? = null)
@Serializable internal data class SupportBody(val supportEmail: String, val supportWhatsapp: String?)
@Serializable internal data class SupportErrorItem(val code: String = "", val fields: Map<String, String> = emptyMap())
@Serializable internal data class SupportErrors(val errors: List<SupportErrorItem> = emptyList())

/** Adaptador Ktor del contacto de soporte y de la marca «introducción vista». No registra tokens ni cuerpos. */
class RemoteSupportRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : SupportRepository, OnboardingSync {

    override suspend fun contact(): SupportResult<SupportContact> = call(HttpMethod.Get, "platform/support", null) { it.body<SupportEnvelope>().data.domain() }

    override suspend fun save(email: String, whatsapp: String?): SupportResult<SupportContact> =
        call(HttpMethod.Put, "platform/support", SupportBody(email.trim(), whatsapp?.trim()?.takeIf { it.isNotEmpty() })) { it.body<SupportEnvelope>().data.domain() }

    override suspend fun setSeen(seen: Boolean): Boolean =
        call(if (seen) HttpMethod.Put else HttpMethod.Delete, "me/onboarding", null) { Unit } is SupportResult.Success

    private fun SupportDto.domain(): SupportContact {
        require(SupportRules.emailError(supportEmail) == null)
        val phone = supportWhatsapp?.takeIf { it.isNotBlank() }
        require(phone == null || (phone.length in 8..15 && phone.all { it.isDigit() }))
        return SupportContact(supportEmail, phone)
    }

    private suspend fun <T> call(verb: HttpMethod, resource: String, body: SupportBody?, read: suspend (HttpResponse) -> T): SupportResult<T> = try {
        val access = token()?.takeIf { it.isNotBlank() } ?: return SupportResult.Failure(SupportFailure.SESSION)
        val response = client.request("${config.apiBaseUrl}/api/v1/$resource") {
            method = verb
            bearerAuth(access)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        onSessionResponse(response)
        if (response.status.isSuccess()) SupportResult.Success(read(response))
        else {
            if (response.status.value == 401) onSessionRejected(access)
            val fields = if (response.status.value == 422) try {
                response.body<SupportErrors>().errors.flatMap { it.fields.keys }.filter { it in setOf("supportEmail", "supportWhatsapp") }.associateWith { "Revisa este campo." }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyMap() } else emptyMap()
            SupportResult.Failure(when (response.status.value) {
                401 -> SupportFailure.SESSION; 403 -> SupportFailure.DENIED; 400, 422 -> SupportFailure.INVALID; else -> SupportFailure.UNAVAILABLE
            }, fields)
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
