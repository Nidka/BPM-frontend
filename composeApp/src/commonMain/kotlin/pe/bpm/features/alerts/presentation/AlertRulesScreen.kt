package pe.bpm.features.alerts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.*
import pe.bpm.features.alerts.domain.*

/** Lista, revisión y confirmación dentro del árbol principal también en web. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertRulesScreen(
  viewModel: AlertsViewModel,
  locations: List<AlertLocation> = emptyList(),
  modifier: Modifier = Modifier,
  locationSelector: (@Composable (String?, (AlertLocation?) -> Unit, Boolean) -> Unit)? = null,
) {
  val s by viewModel.state.collectAsState()
  val e by viewModel.editor.state.collectAsState()
  Column(
    modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("Reglas de avisos", style = MaterialTheme.typography.headlineSmall)
    Text(
      "Configura cuándo avisar y qué canales solicitar. Desactivar una regla detiene nuevas evaluaciones; los avisos anteriores conservan su información."
    )
    s.failure?.let { AlertError(it, viewModel::refresh) }
    if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (!AlertsAccess.canConfigure(s.role)) Text("Solo la administración puede configurar reglas.")
    else if (e.form != null) AlertRuleFormContent(viewModel, locations, locationSelector)
    else if (s.rulesAuthorized) {
      e.failure?.let {
        Text(alertFailureText(it), color = MaterialTheme.colorScheme.error)
        BpmSecondaryButton(viewModel::recoverDraft) { Text("Reintentar recuperación del borrador") }
      }
      if (e.hasDraft)
        RaisedButton(
          "Continuar borrador guardado",
          viewModel.editor::resume,
          Modifier.fillMaxWidth(),
        )
      else if (e.failure == null)
        RaisedButton(
          "Crear regla de avisos",
          { viewModel.editor.new() },
          Modifier.fillMaxWidth(),
          enabled = !s.loading,
        )
      BpmSearchField(
        s.rules.query,
        viewModel::query,
        "Buscar reglas por nombre",
        loading = s.loading,
      )
      Text("Filtrar por ámbito", style = MaterialTheme.typography.titleSmall)
      if (locationSelector != null)
        locationSelector(s.ruleLocationId, { viewModel.ruleLocation(it?.id) }, !s.loading)
      else
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          FilterChip(
            s.ruleLocationId == null,
            { viewModel.ruleLocation(null) },
            label = { Text("Empresa y todas las sedes") },
          )
          locations.forEach { l ->
            FilterChip(
              s.ruleLocationId == l.id,
              { viewModel.ruleLocation(l.id) },
              label = { Text(l.name) },
            )
          }
        }
      if (s.savedRevision > 0) Text("Regla guardada y confirmada por el servidor.")
      if (s.rulesLoaded && s.rules.rows.isEmpty() && !s.loading && s.failure == null)
        Text("No hay reglas para esta consulta.")
      s.rules.rows.forEach { r ->
        BpmCard(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.name, style = MaterialTheme.typography.titleMedium)
            Text(alertTypeLabel(r.type))
            Text(if (r.locationId == null) "Toda la empresa" else "Sede: ${r.locationName}")
            Text(if (r.active) "Activa" else "Inactiva")
            Text(ruleConfigurationText(r.type, r.configuration))
            Text(
              "Correo: ${if(r.emailEnabled)"solicitado" else "desactivado"} · Dispositivo: ${if(r.pushEnabled)"solicitado" else "desactivado"}"
            )
            BpmSecondaryButton({ viewModel.editRule(r) }, enabled = !s.loading) {
              Text("Editar regla")
            }
          }
        }
      }
      if (s.rulesLoaded)
        PageNavigation(
          s.rules.number,
          s.rules.previous.isNotEmpty(),
          s.rules.next.isNotEmpty(),
          s.loading,
          viewModel::previous,
          viewModel::next,
        )
    } else if (s.failure == null && !s.loading)
      BpmSecondaryButton({ viewModel.rules() }) { Text("Consultar reglas") }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertRuleFormContent(
  vm: AlertsViewModel,
  locations: List<AlertLocation>,
  locationSelector: (@Composable (String?, (AlertLocation?) -> Unit, Boolean) -> Unit)?,
) {
  val s by vm.editor.state.collectAsState()
  val f = s.form ?: return
  var discard by remember(f.id) { mutableStateOf(false) }
  val keys =
    listOf("name") +
      when (f.type) {
        AlertType.CHECKLIST_MISSING -> listOf("afterLocalTime")
        AlertType.TEMPERATURE_DANGER ->
          listOf("minimumTemperatureCelsius", "maximumTemperatureCelsius")
        AlertType.ORGANIZATION_INACTIVE -> listOf("inactivityDays")
        AlertType.TRAINING_ASSIGNED -> emptyList()
      }
  val nav = rememberBpmFormNavigation(keys)
  val enabled = !s.busy && s.pending == null
  Text(
    if (f.original == null) "Nueva regla" else "Editar ${f.original.name}",
    style = MaterialTheme.typography.titleLarge,
  )
  if (s.recovered) Text("Recuperamos tu borrador guardado. No se envió automáticamente.")
  DraftSaveNotice(s.saveStatus, vm.editor::retrySave)
  s.failure?.let { Text(alertFailureText(it), color = MaterialTheme.colorScheme.error) }
  if (s.pending != null)
    Text(
      "Hay un envío pendiente de confirmación. Reintenta la misma operación para conocer su resultado; sus datos permanecen protegidos."
    )
  if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  if (discard) {
    DiscardDraftConfirmation(
      { discard = false },
      {
        discard = false
        vm.editor.discard()
      },
      enabled = !s.busy,
    )
  } else if (s.reviewing || s.pending != null) {
    BpmCard(Modifier.fillMaxWidth()) {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Revisar antes de guardar", style = MaterialTheme.typography.titleMedium)
        Text(f.values["name"].orEmpty())
        Text(alertTypeLabel(f.type))
        Text(
          if (f.locationId == null) "Toda la empresa"
          else
            "Sede: ${locations.firstOrNull{it.id==f.locationId}?.name?:f.locationName.ifBlank{"Sede seleccionada"}}"
        )
        Text(ruleConfigurationText(f.type, AlertRuleValidation.configuration(f)))
        Text("Estado: ${if(f.active)"activa" else "inactiva"}")
        Text(
          "Correo: ${if(f.emailEnabled)"solicitado" else "desactivado"}; push: ${if(f.pushEnabled)"solicitado" else "desactivado"}."
        )
        Text(
          "La entrega externa requiere proveedores y dispositivos disponibles. Guardar una regla no confirma la entrega de un mensaje.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    RaisedButton(
      if (s.pending != null) "Reintentar envío pendiente" else "Confirmar y guardar regla",
      vm.editor::submit,
      Modifier.fillMaxWidth(),
      enabled = !s.busy && !s.staleVersion,
    )
    if (s.pending == null)
      BpmSecondaryButton(vm.editor::resumeEditing, enabled = !s.busy) { Text("Volver a editar") }
  } else {
    Text("Tipo de aviso", style = MaterialTheme.typography.titleSmall)
    if (f.original == null)
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        AlertType.entries.forEach { t ->
          FilterChip(
            f.type == t,
            { vm.editor.type(t) },
            enabled = enabled,
            label = { Text(alertTypeLabel(t)) },
          )
        }
      }
    else Text("${alertTypeLabel(f.type)} · El tipo y el ámbito se mantienen en esta regla.")
    BpmTextField(
      f.values["name"].orEmpty(),
      { vm.editor.edit("name", it) },
      "Nombre de la regla",
      error = s.errors["name"],
      enabled = enabled,
      navigation = nav["name"],
      rule = InputRules.text(2, 160),
    )
    Text("Ámbito", style = MaterialTheme.typography.titleSmall)
    if (f.original != null || f.type == AlertType.ORGANIZATION_INACTIVE)
      Text(
        if (f.locationId == null) "Toda la empresa"
        else "Sede: ${f.original?.locationName.orEmpty()}"
      )
    else if (locationSelector != null)
      locationSelector(f.locationId, vm.editor::selectLocation, enabled)
    else
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        FilterChip(
          f.locationId == null,
          { vm.editor.location(null) },
          enabled = enabled,
          label = { Text("Toda la empresa") },
        )
        locations.forEach { l ->
          FilterChip(
            f.locationId == l.id,
            { vm.editor.location(l.id) },
            enabled = enabled,
            label = { Text(l.name) },
          )
        }
      }
    s.errors["locationId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    fun fieldLabel(key: String) =
      when (key) {
        "afterLocalTime" -> "Hora local de evaluación (HH:mm)"
        "minimumTemperatureCelsius" -> "Temperatura mínima de peligro (°C)"
        "maximumTemperatureCelsius" -> "Temperatura máxima de peligro (°C)"
        else -> "Días sin actividad"
      }
    keys.drop(1).forEach { key ->
      BpmTextField(
        f.values[key].orEmpty(),
        { vm.editor.edit(key, it) },
        fieldLabel(key),
        error = s.errors[key],
        enabled = enabled,
        navigation = nav[key],
        inputKind =
          if (key in setOf("minimumTemperatureCelsius", "maximumTemperatureCelsius"))
            BpmInputKind.Decimal
          else if (key == "inactivityDays") BpmInputKind.Integer else BpmInputKind.Text,
        rule =
          if (key in setOf("minimumTemperatureCelsius", "maximumTemperatureCelsius")) DecimalInput
          else null,
      )
    }
    Text(
      when (f.type) {
        AlertType.CHECKLIST_MISSING ->
          "Se aplica a controles diarios publicados y asignados. La hora usa la zona de cada sede."
        AlertType.TEMPERATURE_DANGER ->
          "El intervalo incluye ambos límites. Define los valores según la norma aplicable; no hay un rango predefinido."
        AlertType.ORGANIZATION_INACTIVE ->
          "Entre 1 y 365 días. Se usa la última actividad empresarial confirmada por el servidor."
        AlertType.TRAINING_ASSIGNED ->
          "Avisa sobre asignaciones activas de cursos publicados creadas después de la regla."
      },
      style = MaterialTheme.typography.bodySmall,
    )
    RowSwitch("Regla activa", f.active, enabled, vm.editor::active)
    RowSwitch("Correo electrónico", f.emailEnabled, enabled, vm.editor::email)
    RowSwitch("Notificación al dispositivo", f.pushEnabled, enabled, vm.editor::push)
    Text(
      "Cada persona debe activar los avisos en su dispositivo. Los avisos siempre aparecen también en su bandeja.",
      style = MaterialTheme.typography.bodySmall,
    )
    RaisedButton(
      "Revisar regla",
      {
        vm.editor.review()
        nav.focusFirstError(vm.editor.state.value.errors.keys)
      },
      Modifier.fillMaxWidth(),
      enabled = enabled && !s.staleVersion,
    )
  }
  BpmSecondaryButton(vm.editor::close, enabled = !s.busy) {
    Text("Guardar borrador y volver a reglas")
  }
  if (s.pending == null)
    BpmTextButton({ discard = true }, enabled = !s.busy) { Text("Descartar borrador") }
}

@Composable
private fun RowSwitch(
  label: String,
  checked: Boolean,
  enabled: Boolean,
  onChange: (Boolean) -> Unit,
) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, Modifier.weight(1f))
    Switch(
      checked,
      onChange,
      enabled = enabled,
      modifier = Modifier.semantics { contentDescription = label },
    )
  }
}

private fun ruleConfigurationText(type: AlertType, c: AlertConfiguration) =
  when (type) {
    AlertType.CHECKLIST_MISSING -> "A partir de las ${c.afterLocalTime} en la sede."
    AlertType.TEMPERATURE_DANGER ->
      "Intervalo de peligro: ${c.minimumTemperatureCelsius?.let(DecimalInput::format)} a ${c.maximumTemperatureCelsius?.let(DecimalInput::format)} °C (inclusive)."
    AlertType.ORGANIZATION_INACTIVE -> "Sin actividad durante ${c.inactivityDays} días."
    AlertType.TRAINING_ASSIGNED -> "Nueva capacitación asignada."
  }
