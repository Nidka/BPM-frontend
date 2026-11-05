package pe.bpm.features.notifications.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.notifications.domain.*

class RemoteNotificationDevicesRepository(
  private val client: HttpClient,
  private val config: AppConfig,
  private val onSessionRejected: (String) -> Unit = {},
  private val onSessionResponse: (HttpResponse) -> Unit = {},
  private val token: suspend () -> String?,
) : NotificationDevicesRepository {
  private suspend fun response(
    org: String,
    id: String? = null,
    push: PushToken? = null,
  ): HttpResponse {
    require(org.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    id?.let(::uuid)
    val access = token()?.takeIf { it.isNotBlank() } ?: throw Failure(NotificationFailure.SESSION)
    val r =
      client.request(
        "${config.apiBaseUrl}/api/v1/organizations/$org/notifications/devices${id?.let{"/$it"}.orEmpty()}"
      ) {
        method = if (id == null) HttpMethod.Post else HttpMethod.Delete
        bearerAuth(access)
        if (push != null) {
          uuid(push.id)
          uuid(push.installationId)
          require(
            push.platform in setOf("ANDROID", "IOS", "WEB") &&
              push.token.length in 1..4096 &&
              push.token.none { it.isISOControl() }
          )
          contentType(ContentType.Application.Json)
          setBody(
            buildJsonObject {
                put("id", push.id)
                put("installationId", push.installationId)
                put("platform", push.platform)
                put("pushToken", push.token)
              }
              .toString()
          )
        }
      }
    onSessionResponse(r)
    currentCoroutineContext().ensureActive()
    if (r.status.isSuccess()) return r
    if (r.status.value == 401) onSessionRejected(access)
    throw Failure(
      when (r.status.value) {
        401 -> NotificationFailure.SESSION
        403 -> NotificationFailure.DENIED
        409 -> NotificationFailure.CONFLICT
        else -> NotificationFailure.CONNECTION
      }
    )
  }

  override suspend fun register(
    org: String,
    token: PushToken,
  ): NotificationResult<NotificationDevice> = guarded {
    val r = response(org, push = token).body<Envelope>().data
    uuid(r.id)
    require(r.id == token.id && r.platform == token.platform && r.status == "ACTIVE")
    NotificationDevice(r.id, r.platform, r.status)
  }

  override suspend fun deactivate(org: String, id: String): NotificationResult<Unit> = guarded {
    val r = response(org, id = id).body<Envelope>().data
    require(r.id == id && r.status == "INACTIVE")
  }
}

private fun uuid(v: String) {
  require(
    v.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
  )
}

private class Failure(val reason: NotificationFailure) : Exception()

private suspend inline fun <T> guarded(block: () -> T): NotificationResult<T> =
  try {
    val r = block()
    currentCoroutineContext().ensureActive()
    NotificationResult.Success(r)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Failure) {
    NotificationResult.Failure(e.reason)
  } catch (e: SessionAccessException) {
    NotificationResult.Failure(
      if (e.expired) NotificationFailure.SESSION else NotificationFailure.CONNECTION
    )
  } catch (_: SerializationException) {
    NotificationResult.Failure(NotificationFailure.INVALID_RESPONSE)
  } catch (_: JsonConvertException) {
    NotificationResult.Failure(NotificationFailure.INVALID_RESPONSE)
  } catch (_: IllegalArgumentException) {
    NotificationResult.Failure(NotificationFailure.INVALID_RESPONSE)
  } catch (_: Exception) {
    NotificationResult.Failure(NotificationFailure.CONNECTION)
  }

@Serializable private data class Envelope(val data: DeviceDto)

@Serializable
private data class DeviceDto(
  val id: String,
  val platform: String? = null,
  val status: String,
  val lastSeenAt: String? = null,
)
