package pe.bpm.features.alerts.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.*
import pe.bpm.features.alerts.domain.*

/** Una propuesta por cuenta, empresa y origen; nunca incluye credenciales. */
class LocalAlertRuleDraftRepository(storage: DraftStorage, origin: String) :
  AlertRuleDraftRepository {
  private val document = JsonDraftDocument(storage, origin, "alert-rule", RuleDraftDto.serializer())

  override suspend fun read(owner: DraftOwner) = document.read(owner)?.domain()

  override suspend fun write(owner: DraftOwner, draft: AlertRuleDraft?) {
    if (draft == null) document.remove(owner)
    else document.write(owner, RuleDraftDto.from(draft).also { it.domain() })
  }
}

@Serializable
private data class RuleDraftDto(
  val id: String,
  val type: String,
  val locationId: String?,
  val values: Map<String, String>,
  val active: Boolean,
  val emailEnabled: Boolean,
  val pushEnabled: Boolean,
  val original: RuleDto?,
  val pendingKey: String?,
  val pendingBody: String?,
  val locationName: String = "",
) {
  fun domain(): AlertRuleDraft {
    alertId(id)
    locationId?.let(::alertId)
    require(
      values.size <= 5 &&
        values.keys.all {
          it in
            setOf(
              "name",
              "afterLocalTime",
              "minimumTemperatureCelsius",
              "maximumTemperatureCelsius",
              "inactivityDays",
            )
        } &&
        values.values.all { it.length <= 1000 }
    )
    val original = original?.domain()
    require(original == null || original.id == id)
    val form =
      AlertRuleForm(
        id,
        AlertType.valueOf(type),
        locationId,
        values,
        active,
        emailEnabled,
        pushEnabled,
        original,
        locationName,
      )
    require(original == null || original.type == form.type && original.locationId == locationId)
    require(pendingKey == null && pendingBody == null || pendingKey != null && pendingBody != null)
    val pending = pendingKey?.let { PendingAlertRule(it, pendingBody!!) }
    if (pending != null) require(pending == prepareAlertRule(form, pending.key))
    return AlertRuleDraft(form, pending)
  }

  companion object {
    fun from(d: AlertRuleDraft) =
      d.form.let { f ->
        RuleDraftDto(
          f.id,
          f.type.name,
          f.locationId,
          f.values,
          f.active,
          f.emailEnabled,
          f.pushEnabled,
          f.original?.let(RuleDto::from),
          d.pending?.key,
          d.pending?.body,
          f.locationName,
        )
      }
  }
}
