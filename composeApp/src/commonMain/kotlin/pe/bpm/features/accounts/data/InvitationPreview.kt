package pe.bpm.features.accounts.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import pe.bpm.core.config.AppConfig
import pe.bpm.features.accounts.domain.AccountsRules
import pe.bpm.features.accounts.domain.InvitationPreview
import pe.bpm.features.accounts.domain.MemberRole

/**
 * Vista previa pública de una invitación («Te invitaron a X como Y», correo al que llegó): se pide con el token antes de iniciar sesión.
 * Cualquier fallo (enlace vencido, sin red) devuelve nulo y la pantalla de acceso muestra su aviso genérico; el token nunca se registra.
 */
class RemoteInvitationPreview(private val client: HttpClient, private val config: AppConfig) {
    suspend fun preview(rawToken: String): InvitationPreview? {
        val token = AccountsRules.invitationToken(rawToken) ?: return null
        return try {
            val response: HttpResponse = client.post("${config.apiBaseUrl}/api/v1/invitations/preview") {
                contentType(ContentType.Application.Json)
                setBody(PreviewBody(token))
            }
            if (!response.status.isSuccess()) return null
            val dto = response.body<PreviewEnvelope>().data
            val role = MemberRole.entries.firstOrNull { it.name == dto.role } ?: return null
            if (dto.organizationName.isBlank() || dto.email.isBlank()) return null
            InvitationPreview(dto.organizationName, role, dto.email)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable private data class PreviewBody(val token: String)
@Serializable private data class PreviewEnvelope(val data: PreviewDto)
@Serializable private data class PreviewDto(val organizationName: String, val role: String, val email: String, val expiresAt: String = "", val status: String = "")
