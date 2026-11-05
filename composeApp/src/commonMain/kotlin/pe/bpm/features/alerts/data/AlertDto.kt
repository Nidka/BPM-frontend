package pe.bpm.features.alerts.data

import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import pe.bpm.core.domain.DecimalInput
import pe.bpm.features.alerts.domain.*

internal fun alertId(value: String) {
  require(
    value.matches(
      Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    )
  )
}

private fun date(value: String) {
  Instant.parse(value)
}

@Serializable
internal data class AlertDto(
  val id: String,
  val type: String,
  val title: String,
  val message: String,
  val status: String,
  val createdAt: String,
  val viewedAt: String? = null,
  val attendedAt: String? = null,
  val locationId: String? = null,
  val locationName: String,
  val sourceType: String,
  val sourceId: String,
  val sourceAvailable: Boolean,
  val sourceParentId: String? = null,
  val timezone: String? = null,
) {
  fun domain(): Alert {
    alertId(id)
    if (sourceType == "ORGANIZATION") require(sourceId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    else alertId(sourceId)
    locationId?.let(::alertId)
    sourceParentId?.let(::alertId)
    require(
      title.isNotBlank() &&
        title.length <= 200 &&
        message.length <= 10000 &&
        locationName.length <= 160
    )
    date(createdAt)
    viewedAt?.let(::date)
    attendedAt?.let(::date)
    require(attendedAt == null || viewedAt != null)
    val t = AlertType.valueOf(type)
    val s = AlertStatus.valueOf(status)
    require(s != AlertStatus.VIEWED || viewedAt != null)
    require(s != AlertStatus.ATTENDED || attendedAt != null)
    require(
      sourceType ==
        when (t) {
          AlertType.CHECKLIST_MISSING -> "CHECKLIST_ASSIGNMENT"
          AlertType.TEMPERATURE_DANGER -> "RECEIPT_ITEM"
          AlertType.ORGANIZATION_INACTIVE -> "ORGANIZATION"
          AlertType.TRAINING_ASSIGNED -> "TRAINING_ASSIGNMENT"
        }
    )
    return Alert(
      id,
      t,
      title,
      message,
      s,
      createdAt,
      viewedAt,
      attendedAt,
      locationId,
      locationName,
      sourceType,
      sourceId,
      sourceAvailable,
      sourceParentId,
      timezone,
    )
  }
}

@Serializable
internal data class RuleDto(
  val id: String,
  val locationId: String? = null,
  val locationName: String,
  val name: String,
  val type: String,
  val configuration: JsonObject,
  val active: Boolean,
  val emailEnabled: Boolean,
  val pushEnabled: Boolean,
  val rowVersion: Long,
) {
  fun domain(): AlertRule {
    alertId(id)
    locationId?.let(::alertId)
    require(rowVersion > 0 && locationName.length <= 160)
    val t = AlertType.valueOf(type)
    val c = configuration
    val expected =
      when (t) {
        AlertType.CHECKLIST_MISSING -> setOf("afterLocalTime")
        AlertType.TEMPERATURE_DANGER ->
          setOf("minimumTemperatureCelsius", "maximumTemperatureCelsius")
        AlertType.ORGANIZATION_INACTIVE -> setOf("inactivityDays")
        AlertType.TRAINING_ASSIGNED -> emptySet()
      }
    require(c.keys == expected)
    val config =
      AlertConfiguration(
        c["afterLocalTime"]?.jsonPrimitive?.content,
        c["minimumTemperatureCelsius"]?.jsonPrimitive?.double,
        c["maximumTemperatureCelsius"]?.jsonPrimitive?.double,
        c["inactivityDays"]?.jsonPrimitive?.int,
      )
    val r =
      AlertRule(
        id,
        locationId,
        locationName,
        name,
        t,
        config,
        active,
        emailEnabled,
        pushEnabled,
        rowVersion,
      )
    require(AlertRuleValidation.errors(AlertRuleForm.from(r)).isEmpty())
    if (config.minimumTemperatureCelsius != null)
      require(DecimalInput.isValid(config.minimumTemperatureCelsius))
    if (config.maximumTemperatureCelsius != null)
      require(DecimalInput.isValid(config.maximumTemperatureCelsius))
    return r
  }

  companion object {
    fun from(r: AlertRule) =
      RuleDto(
        r.id,
        r.locationId,
        r.locationName,
        r.name,
        r.type.name,
        configurationJson(r.type, r.configuration),
        r.active,
        r.emailEnabled,
        r.pushEnabled,
        r.rowVersion,
      )
  }
}

internal fun configurationJson(type: AlertType, c: AlertConfiguration) = buildJsonObject {
  when (type) {
    AlertType.CHECKLIST_MISSING -> put("afterLocalTime", c.afterLocalTime!!)
    AlertType.TEMPERATURE_DANGER -> {
      put("minimumTemperatureCelsius", c.minimumTemperatureCelsius!!)
      put("maximumTemperatureCelsius", c.maximumTemperatureCelsius!!)
    }
    AlertType.ORGANIZATION_INACTIVE -> put("inactivityDays", c.inactivityDays!!)
    AlertType.TRAINING_ASSIGNED -> Unit
  }
}

fun prepareAlertRule(form: AlertRuleForm, key: String): PendingAlertRule {
  alertId(form.id)
  alertId(key)
  form.locationId?.let(::alertId)
  val c = AlertRuleValidation.configuration(form)
  val body =
    buildJsonObject {
        if (form.original == null) put("id", form.id)
        else put("rowVersion", form.original.rowVersion)
        put("locationId", form.locationId?.let(::JsonPrimitive) ?: JsonNull)
        put("name", form.values.getValue("name").trim())
        put("type", form.type.name)
        put("configuration", configurationJson(form.type, c))
        put("active", form.active)
        put("emailEnabled", form.emailEnabled)
        put("pushEnabled", form.pushEnabled)
      }
      .toString()
  require(body.encodeToByteArray().size <= 512 * 1024)
  return PendingAlertRule(key, body)
}
