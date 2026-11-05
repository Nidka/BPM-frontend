package pe.bpm.features.alerts

import kotlin.test.*
import pe.bpm.features.alerts.domain.*

class AlertRulesTest {
  private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"

  @Test
  fun dangerRangeIsRequiredOrderedAndAcceptsLocalDecimalsWithoutDefaults() {
    val f =
      AlertRuleForm(
        id,
        AlertType.TEMPERATURE_DANGER,
        values = mapOf("name" to "Peligro configurado"),
      )
    assertEquals(
      setOf("minimumTemperatureCelsius", "maximumTemperatureCelsius"),
      AlertRuleValidation.errors(f).keys,
    )
    val valid =
      f.copy(
        values =
          f.values +
            mapOf("minimumTemperatureCelsius" to "-2,25", "maximumTemperatureCelsius" to "7.5")
      )
    assertTrue(AlertRuleValidation.errors(valid).isEmpty())
    assertEquals(-2.25, AlertRuleValidation.configuration(valid).minimumTemperatureCelsius)
    assertTrue(
      AlertRuleValidation.errors(
          valid.copy(values = valid.values + ("maximumTemperatureCelsius" to "-2.25"))
        )
        .isNotEmpty()
    )
    for (invalid in listOf("NaN", "1e2", "1.001", "100000000")) assertTrue(
      AlertRuleValidation.errors(
          valid.copy(values = valid.values + ("minimumTemperatureCelsius" to invalid))
        )
        .isNotEmpty()
    )
  }

  @Test
  fun checklistTimeAndInactivityDaysFollowTheirOwnContracts() {
    val f =
      AlertRuleForm(
        id,
        AlertType.CHECKLIST_MISSING,
        values = mapOf("name" to "Control diario", "afterLocalTime" to "23:59"),
      )
    assertTrue(AlertRuleValidation.errors(f).isEmpty())
    assertTrue(
      AlertRuleValidation.errors(f.copy(values = f.values + ("afterLocalTime" to "24:00")))
        .isNotEmpty()
    )
    val inactivity =
      f.copy(
        type = AlertType.ORGANIZATION_INACTIVE,
        values = mapOf("name" to "Inactividad", "inactivityDays" to "365"),
      )
    assertTrue(AlertRuleValidation.errors(inactivity).isEmpty())
    for (value in listOf("0", "366", "1.5", "1e2")) assertTrue(
      AlertRuleValidation.errors(
          inactivity.copy(values = inactivity.values + ("inactivityDays" to value))
        )
        .isNotEmpty()
    )
    assertTrue(
      AlertRuleValidation.errors(inactivity.copy(locationId = id)).containsKey("locationId")
    )
  }

  @Test
  fun existingRuleCannotChangeTypeOrScope() {
    val original =
      AlertRule(
        id,
        null,
        "",
        "Curso asignado",
        AlertType.TRAINING_ASSIGNED,
        AlertConfiguration(),
        true,
        false,
        false,
        4,
      )
    val f = AlertRuleForm.from(original)
    assertTrue(AlertRuleValidation.errors(f).isEmpty())
    assertTrue(
      AlertRuleValidation.errors(f.copy(type = AlertType.CHECKLIST_MISSING)).containsKey("type")
    )
    assertTrue(AlertRuleValidation.errors(f.copy(locationId = id)).containsKey("locationId"))
  }
}
