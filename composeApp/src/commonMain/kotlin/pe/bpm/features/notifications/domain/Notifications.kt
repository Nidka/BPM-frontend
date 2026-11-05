package pe.bpm.features.notifications.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** El token solo cruza el puerto de registro; nunca forma parte del estado de pantalla. */
class PushToken(
  val id: String,
  val installationId: String,
  val platform: String,
  val token: String,
  val revision: Long = 0,
) {
  override fun toString() = "PushToken([privado])"
}

data class NotificationDevice(val id: String, val platform: String, val status: String)

enum class NotificationFailure {
  UNAVAILABLE,
  PERMISSION,
  SESSION,
  DENIED,
  CONFLICT,
  CONNECTION,
  INVALID_RESPONSE,
  TOKEN_CHANGED,
}

sealed interface NotificationResult<out T> {
  data class Success<T>(val value: T) : NotificationResult<T>

  data class Failure(val reason: NotificationFailure) : NotificationResult<Nothing>
}

sealed interface PushTokenResult {
  data class Success(val value: PushToken) : PushTokenResult

  data class Failure(val reason: NotificationFailure) : PushTokenResult
}

interface PushProvider {
  val available: Boolean
  val unavailableReason: String
  val invalidations: Flow<Unit>
    get() = emptyFlow()

  fun tokenCurrent(token: PushToken): Boolean = true

  /** Solo se invoca tras pulsar Activar. Puede solicitar permiso del sistema. */
  suspend fun requestToken(userId: String, organizationId: String): PushTokenResult

  fun bind(userId: String, organizationId: String, deviceId: String)

  fun bindCurrent(
    userId: String,
    organizationId: String,
    deviceId: String,
    token: PushToken,
  ): Boolean {
    if (!tokenCurrent(token)) return false
    bind(userId, organizationId, deviceId)
    return true
  }

  /** Se ejecuta sincrónicamente al perder la sesión antes de cualquier trabajo asíncrono. */
  fun clearBinding()

  suspend fun deleteToken()
}

class UnavailablePushProvider(
  override val unavailableReason: String =
    "Las notificaciones externas no están disponibles en esta plataforma. La bandeja de avisos sigue funcionando."
) : PushProvider {
  override val available = false

  override suspend fun requestToken(userId: String, organizationId: String) =
    PushTokenResult.Failure(NotificationFailure.UNAVAILABLE)

  override fun bind(userId: String, organizationId: String, deviceId: String) {}

  override fun clearBinding() {}

  override suspend fun deleteToken() {}
}

interface NotificationDevicesRepository {
  suspend fun register(org: String, token: PushToken): NotificationResult<NotificationDevice>

  suspend fun deactivate(org: String, id: String): NotificationResult<Unit>
}

class ManageNotificationDevices(
  private val repository: NotificationDevicesRepository,
  private val provider: PushProvider,
) {
  val available
    get() = provider.available

  val unavailableReason
    get() = provider.unavailableReason

  val invalidations
    get() = provider.invalidations

  fun invalidateOperation() {
    generation++
  }

  private var generation = 0L

  fun clearLocal() {
    generation++
    provider.clearBinding()
  }

  suspend fun deleteLocalToken() {
    provider.deleteToken()
  }

  suspend fun enable(user: String, org: String): NotificationResult<NotificationDevice> {
    if (!provider.available) return NotificationResult.Failure(NotificationFailure.UNAVAILABLE)
    val expected = generation
    val token =
      when (val r = provider.requestToken(user, org)) {
        is PushTokenResult.Success -> r.value
        is PushTokenResult.Failure -> return NotificationResult.Failure(r.reason)
      }
    if (expected != generation) return NotificationResult.Failure(NotificationFailure.SESSION)
    val r = repository.register(org, token)
    if (expected != generation) return NotificationResult.Failure(NotificationFailure.SESSION)
    if (r is NotificationResult.Success) {
      if (!provider.bindCurrent(user, org, r.value.id, token))
        return NotificationResult.Failure(NotificationFailure.TOKEN_CHANGED)
    }
    return r
  }

  suspend fun disable(org: String, id: String?): NotificationResult<Unit> {
    clearLocal()
    val r = if (id == null) NotificationResult.Success(Unit) else repository.deactivate(org, id)
    provider.deleteToken()
    return r
  }
}

/** El adaptador nativo rechaza payloads ajenos, datos adicionales e identificadores inválidos. */
fun acceptedAlertId(
  data: Map<String, String>,
  organizationId: String?,
  recipientHash: String?,
): String? {
  if (organizationId.isNullOrEmpty() || recipientHash.isNullOrEmpty()) return null
  if (data.keys != setOf("alertId", "organizationId", "recipientHash")) return null
  if (data["organizationId"] != organizationId || data["recipientHash"] != recipientHash)
    return null
  if (!recipientHash.matches(Regex("[0-9a-f]{64}"))) return null
  return data["alertId"]?.takeIf {
    it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
  }
}
