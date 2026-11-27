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

  /** La persona activó las notificaciones para esta empresa en este dispositivo (sobrevive a reinicios y a sesiones vencidas). */
  fun rememberedOptIn(userId: String, organizationId: String): Boolean = false

  /** Olvida la activación (Desactivar o cierre de sesión explícito). */
  fun forgetOptIn() {}

  /** Abre los ajustes de notificaciones de la app en el sistema (permiso bloqueado). Devuelve false si no se puede. */
  fun openSystemSettings(): Boolean = false
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

  /** Pide al servidor una notificación de prueba para los celulares activos de la persona (máximo una por minuto). */
  suspend fun testPush(org: String): TestPushOutcome = TestPushOutcome.FAILED
}

enum class TestPushOutcome { SENT, RATE_LIMITED, NO_DEVICE, OFFLINE, FAILED }

/** Texto corto del resultado de la prueba. */
fun testPushText(outcome: TestPushOutcome): String = when (outcome) {
  TestPushOutcome.SENT -> "Prueba enviada. Debería llegar en unos segundos."
  TestPushOutcome.RATE_LIMITED -> "Ya enviamos una prueba. Espera un minuto."
  TestPushOutcome.NO_DEVICE -> "Primero enciende «Notificaciones en este celular»."
  TestPushOutcome.OFFLINE -> "Sin conexión. Inténtalo cuando tengas internet."
  TestPushOutcome.FAILED -> "No pudimos enviar la prueba. Inténtalo más tarde."
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

  /** Si ya estaban activadas aquí, se vuelve a registrar el token al abrir la app (el servidor lo actualiza sin duplicar). */
  fun shouldRefresh(user: String, org: String): Boolean = provider.available && provider.rememberedOptIn(user, org)

  fun forgetOptIn() = provider.forgetOptIn()

  fun openSystemSettings(): Boolean = provider.openSystemSettings()

  suspend fun testPush(org: String): TestPushOutcome = repository.testPush(org)

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
    provider.forgetOptIn()
    val r = if (id == null) NotificationResult.Success(Unit) else repository.deactivate(org, id)
    provider.deleteToken()
    return r
  }
}

/**
 * El adaptador nativo rechaza payloads ajenos e identificadores inválidos. Las tres claves del vínculo son obligatorias;
 * el servidor añade claves de navegación (`route`, `type`, `entityId`, `siteId`) que se ignoran aquí y se validan aparte.
 */
fun acceptedAlertId(
  data: Map<String, String>,
  organizationId: String?,
  recipientHash: String?,
): String? {
  if (organizationId.isNullOrEmpty() || recipientHash.isNullOrEmpty()) return null
  if (!data.keys.containsAll(setOf("alertId", "organizationId", "recipientHash"))) return null
  if (data["organizationId"] != organizationId || data["recipientHash"] != recipientHash)
    return null
  if (!recipientHash.matches(Regex("[0-9a-f]{64}"))) return null
  return data["alertId"]?.takeIf {
    it.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
  }
}

private val PUSH_ROUTES = setOf("avisos", "empresa/operacion", "control/operacion", "empresa", "aprender")
private const val UUID_RE = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
private val PUSH_ASSIGNMENT = Regex("control/programacion/$UUID_RE")
/** Incidencia concreta (se abre su detalle) o control enviado (seguimiento del jefe). */
private val PUSH_ENTITY = Regex("(control/incidencias|empresa/incidencias|seguimiento)/$UUID_RE")

/** Separa «control/incidencias/<id>» en la pantalla de incidencias y el id a abrir; null si no es una incidencia concreta. */
fun incidentDeepLink(route: String): Pair<String, String>? {
  val m = Regex("(control/incidencias|empresa/incidencias)/($UUID_RE)").matchEntire(route) ?: return null
  return m.groupValues[1] to m.groupValues[2].lowercase()
}

/** Destino que se abre al tocar un aviso: solo rutas conocidas de la app; cualquier otra cosa cae en «avisos». */
fun acceptedPushRoute(raw: String?): String? {
  val route = raw?.trim().orEmpty()
  if (route.isEmpty()) return null
  return if (route in PUSH_ROUTES || PUSH_ASSIGNMENT.matches(route) || PUSH_ENTITY.matches(route) ||
    route in setOf("control/incidencias", "empresa/incidencias")) route else "avisos"
}
