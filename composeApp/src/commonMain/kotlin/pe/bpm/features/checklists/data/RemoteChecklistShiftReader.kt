package pe.bpm.features.checklists.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.checklists.domain.*

/**
 * Lee los turnos activos de una sede para programar controles. Es una lectura mínima propia de
 * checklists: no depende de la feature de operaciones.
 */
class RemoteChecklistShiftReader(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : ChecklistShiftReader {
    override suspend fun shifts(organizationId: String, locationId: String): ShiftOptionsResult =
        try {
            val ids = Regex("[A-Za-z0-9_-]{1,64}")
            require(organizationId.matches(ids) && locationId.matches(ids))
            val access =
                token()?.takeIf { it.isNotBlank() }
                    ?: return ShiftOptionsResult.Failure(AssignmentFailure.SESSION)
            val response =
                client.get("${config.apiBaseUrl}/api/v1/organizations/$organizationId/shifts") {
                    bearerAuth(access)
                    parameter("locationId", locationId)
                    parameter("status", "ACTIVE")
                    parameter("pageSize", 100)
                }
            onSessionResponse(response)
            if (response.status.isSuccess()) {
                val list = response.body<ShiftOptionsDto>().data
                require(list.size <= 100 && list.map { it.id }.distinct().size == list.size)
                ShiftOptionsResult.Success(
                    list.filter { it.active }.map { it.domain(locationId) }
                )
            } else {
                if (response.status.value == 401) onSessionRejected(access)
                ShiftOptionsResult.Failure(
                    when (response.status.value) {
                        401 -> AssignmentFailure.SESSION
                        403 -> AssignmentFailure.DENIED
                        404 -> AssignmentFailure.NOT_FOUND
                        429 -> AssignmentFailure.RATE_LIMITED
                        else -> AssignmentFailure.UNAVAILABLE
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionAccessException) {
            ShiftOptionsResult.Failure(
                if (e.expired) AssignmentFailure.SESSION else AssignmentFailure.CONNECTION
            )
        } catch (_: SerializationException) {
            ShiftOptionsResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: JsonConvertException) {
            ShiftOptionsResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
            ShiftOptionsResult.Failure(AssignmentFailure.INVALID_RESPONSE)
        } catch (_: Throwable) {
            ShiftOptionsResult.Failure(AssignmentFailure.CONNECTION)
        }
}

@Serializable private data class ShiftOptionsDto(val data: List<ShiftOptionDto>)

@Serializable
private data class ShiftOptionDto(
    val id: String,
    val locationId: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val active: Boolean = true,
    val openNow: Boolean = false,
) {
    fun domain(location: String): ChecklistShiftOption {
        val clock = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
        require(locationId == location && name.isNotBlank() && name.length <= 60)
        require(clock.matches(startTime) && clock.matches(endTime))
        return ChecklistShiftOption(id, name, startTime, endTime, openNow)
    }
}
