package pe.bpm.features.alerts.domain

import pe.bpm.core.domain.*

enum class AlertType {
  CHECKLIST_MISSING,
  TEMPERATURE_DANGER,
  ORGANIZATION_INACTIVE,
  TRAINING_ASSIGNED,
}

enum class AlertStatus {
  PENDING,
  SENT,
  VIEWED,
  ATTENDED,
  CANCELLED,
}

enum class AlertFilter {
  ALL,
  UNREAD,
  VIEWED,
  ATTENDED,
}

data class Alert(
  val id: String,
  val type: AlertType,
  val title: String,
  val message: String,
  val status: AlertStatus,
  val createdAt: String,
  val viewedAt: String?,
  val attendedAt: String?,
  val locationId: String?,
  val locationName: String,
  val sourceType: String,
  val sourceId: String,
  val sourceAvailable: Boolean,
  val sourceParentId: String? = null,
  val timezone: String? = null,
) {
  val unread: Boolean
    get() =
      viewedAt == null &&
        attendedAt == null &&
        status !in setOf(AlertStatus.CANCELLED, AlertStatus.VIEWED, AlertStatus.ATTENDED)
}

data class AlertConfiguration(
  val afterLocalTime: String? = null,
  val minimumTemperatureCelsius: Double? = null,
  val maximumTemperatureCelsius: Double? = null,
  val inactivityDays: Int? = null,
)

data class AlertRule(
  val id: String,
  val locationId: String?,
  val locationName: String,
  val name: String,
  val type: AlertType,
  val configuration: AlertConfiguration,
  val active: Boolean,
  val emailEnabled: Boolean,
  val pushEnabled: Boolean,
  val rowVersion: Long,
)

data class AlertLocation(val id: String, val name: String)

enum class AlertsFailure {
  SESSION,
  DENIED,
  NOT_FOUND,
  INVALID,
  CONFLICT,
  INVALID_PAGE,
  CONNECTION,
  UNAVAILABLE,
  INVALID_RESPONSE,
  RATE_LIMITED,
  LOCAL,
}

sealed interface AlertsResult<out T> {
  data class Success<T>(val value: T) : AlertsResult<T>

  data class Failure(val reason: AlertsFailure) : AlertsResult<Nothing>
}

object AlertsAccess {
  fun canConfigure(role: String) = role in setOf("ADMIN", "SUPER_ADMIN")
}

interface AlertsRepository {
  suspend fun inbox(org: String, filter: AlertFilter, page: PageRequest): AlertsResult<Page<Alert>>

  suspend fun unreadCount(org: String): AlertsResult<Int>

  suspend fun detail(org: String, id: String): AlertsResult<Alert>

  suspend fun view(org: String, id: String): AlertsResult<Alert>

  suspend fun attend(org: String, id: String): AlertsResult<Alert>

  suspend fun authorizeRules(org: String, role: String): AlertsResult<Unit>

  suspend fun rules(
    org: String,
    locationId: String?,
    page: PageRequest,
  ): AlertsResult<Page<AlertRule>>

  suspend fun rule(org: String, locationId: String?, id: String): AlertsResult<AlertRule>
}

/** Conserva la base de edición: el tipo y el ámbito quedan inmutables. */
data class AlertRuleForm(
  val id: String,
  val type: AlertType,
  val locationId: String? = null,
  val values: Map<String, String> = emptyMap(),
  val active: Boolean = true,
  val emailEnabled: Boolean = false,
  val pushEnabled: Boolean = false,
  val original: AlertRule? = null,
  val locationName: String = "",
) {
  companion object {
    fun from(r: AlertRule) =
      AlertRuleForm(
        r.id,
        r.type,
        r.locationId,
        buildMap {
          put("name", r.name)
          r.configuration.afterLocalTime?.let { put("afterLocalTime", it) }
          r.configuration.minimumTemperatureCelsius?.let {
            put("minimumTemperatureCelsius", DecimalInput.format(it).orEmpty())
          }
          r.configuration.maximumTemperatureCelsius?.let {
            put("maximumTemperatureCelsius", DecimalInput.format(it).orEmpty())
          }
          r.configuration.inactivityDays?.let { put("inactivityDays", it.toString()) }
        },
        r.active,
        r.emailEnabled,
        r.pushEnabled,
        r,
        r.locationName,
      )
  }
}

object AlertRuleValidation {
  fun errors(f: AlertRuleForm): Map<String, String> = buildMap {
    InputRules.text(2, 160).error(f.values["name"].orEmpty())?.let { put("name", it) }
    if (f.original != null) {
      if (f.type != f.original.type) put("type", "El tipo de una regla existente no puede cambiar.")
      if (f.locationId != f.original.locationId)
        put("locationId", "El ámbito de una regla existente no puede cambiar.")
    }
    when (f.type) {
      AlertType.CHECKLIST_MISSING ->
        if (!f.values["afterLocalTime"].orEmpty().matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
          put(
            "afterLocalTime",
            "Ingresa la hora local de la sede en formato HH:mm, por ejemplo 17:30.",
          )
      AlertType.TEMPERATURE_DANGER -> {
        val min = DecimalInput.parse(f.values["minimumTemperatureCelsius"].orEmpty())
        val max = DecimalInput.parse(f.values["maximumTemperatureCelsius"].orEmpty())
        if (min == null)
          put(
            "minimumTemperatureCelsius",
            DecimalInput.error(f.values["minimumTemperatureCelsius"].orEmpty())!!,
          )
        if (max == null)
          put(
            "maximumTemperatureCelsius",
            DecimalInput.error(f.values["maximumTemperatureCelsius"].orEmpty())!!,
          )
        if (min != null && max != null && min >= max)
          put("maximumTemperatureCelsius", "El máximo debe ser mayor que el mínimo.")
      }
      AlertType.ORGANIZATION_INACTIVE -> {
        if (f.locationId != null)
          put("locationId", "La inactividad se configura para toda la empresa.")
        val v = f.values["inactivityDays"].orEmpty()
        if (!v.matches(Regex("[0-9]{1,3}")) || v.toIntOrNull() !in 1..365)
          put("inactivityDays", "Ingresa un número entero entre 1 y 365 días.")
      }
      AlertType.TRAINING_ASSIGNED -> Unit
    }
  }

  fun configuration(f: AlertRuleForm): AlertConfiguration {
    require(errors(f).isEmpty())
    return when (f.type) {
      AlertType.CHECKLIST_MISSING ->
        AlertConfiguration(afterLocalTime = f.values.getValue("afterLocalTime"))
      AlertType.TEMPERATURE_DANGER ->
        AlertConfiguration(
          minimumTemperatureCelsius =
            DecimalInput.parse(f.values.getValue("minimumTemperatureCelsius")),
          maximumTemperatureCelsius =
            DecimalInput.parse(f.values.getValue("maximumTemperatureCelsius")),
        )
      AlertType.ORGANIZATION_INACTIVE ->
        AlertConfiguration(inactivityDays = f.values.getValue("inactivityDays").toInt())
      AlertType.TRAINING_ASSIGNED -> AlertConfiguration()
    }
  }
}

data class PendingAlertRule(val key: String, val body: String)

data class AlertRuleDraft(val form: AlertRuleForm, val pending: PendingAlertRule? = null)

interface AlertRuleDraftRepository {
  suspend fun read(owner: DraftOwner): AlertRuleDraft?

  suspend fun write(owner: DraftOwner, draft: AlertRuleDraft?)
}

interface AlertRuleTransport {
  fun prepare(form: AlertRuleForm, key: String): PendingAlertRule

  suspend fun submit(
    org: String,
    form: AlertRuleForm,
    pending: PendingAlertRule,
  ): AlertsResult<AlertRule>
}

class ManageAlerts(
  private val repository: AlertsRepository,
  private val transport: AlertRuleTransport,
  private val drafts: AlertRuleDraftRepository,
) {
  suspend fun inbox(org: String, filter: AlertFilter, page: PageRequest) =
    repository.inbox(org, filter, page)

  suspend fun unreadCount(org: String) = repository.unreadCount(org)

  suspend fun detail(org: String, id: String) = repository.detail(org, id)

  suspend fun view(org: String, id: String) = repository.view(org, id)

  suspend fun attend(org: String, id: String) = repository.attend(org, id)

  suspend fun authorizeRules(org: String, role: String): AlertsResult<Unit> =
    if (AlertsAccess.canConfigure(role)) repository.authorizeRules(org, role)
    else AlertsResult.Failure(AlertsFailure.DENIED)

  suspend fun rules(org: String, locationId: String?, page: PageRequest) =
    repository.rules(org, locationId, page)

  suspend fun rule(org: String, locationId: String?, id: String) =
    repository.rule(org, locationId, id)

  fun prepare(form: AlertRuleForm, key: String) = transport.prepare(form, key)

  suspend fun submit(
    org: String,
    role: String,
    form: AlertRuleForm,
    pending: PendingAlertRule,
  ): AlertsResult<AlertRule> {
    if (!AlertsAccess.canConfigure(role)) return AlertsResult.Failure(AlertsFailure.DENIED)
    if (AlertRuleValidation.errors(form).isNotEmpty())
      return AlertsResult.Failure(AlertsFailure.INVALID)
    return transport.submit(org, form, pending)
  }

  suspend fun readDraft(owner: DraftOwner) = drafts.read(owner)

  suspend fun writeDraft(owner: DraftOwner, draft: AlertRuleDraft?) = drafts.write(owner, draft)
}
