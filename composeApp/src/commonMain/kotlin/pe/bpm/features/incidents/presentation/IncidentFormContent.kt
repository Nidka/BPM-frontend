package pe.bpm.features.incidents.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.InputRules
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.incidents.domain.*

private data class IncidentField(
    val key: String,
    val label: String,
    val min: Int,
    val max: Int,
    val placeholder: String? = null,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IncidentFormContent(
    s: IncidentsState,
    e: IncidentEditorState,
    vm: IncidentsViewModel,
) {
    val f = e.form ?: return
    val editor = vm.editor
    val title =
        when (f.kind) {
            "CATALOG" ->
                if (f.catalogId == null) "Crear criterio de incidencia"
                else "Crear versión ${f.expectedVersion!! + 1}"
            "REPORT" -> "Reportar incidencia"
            else -> incidentActionLabel(IncidentActionType.valueOf(f.kind))
        }
    BpmFormScaffold(title) {
        if (e.recovered)
            Text("Recuperamos tu copia local de esta sede. Revisa los datos antes de continuar.")
        val saveText = when (e.saveStatus) {
            DraftSaveStatus.SAVING -> "Guardando borrador…"
            DraftSaveStatus.SAVED -> "Borrador guardado en este teléfono"
            DraftSaveStatus.FAILED -> "No se pudo guardar el borrador"
            else -> ""
        }
        if (saveText.isNotEmpty()) Text(saveText, style = MaterialTheme.typography.bodySmall)
        e.failure?.let {
            // Al validar, un 403/409 suele significar que existe otra persona elegible para validar.
            val text = if (f.kind == "VERIFY" && (it == IncidentsFailure.DENIED || it == IncidentsFailure.CONFLICT))
                IncidentsAccess.SELF_VALIDATION_DENIED + ". " + incidentFailureText(it) else incidentFailureText(it)
            Text(text, color = MaterialTheme.colorScheme.error)
        }
        if (e.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (e.pending != null) {
            if (!e.busy) Text("El envío aún no está confirmado. Reintenta para consultar su resultado.")
            IncidentReview(f)
            BpmChunkyButton("Reintentar el mismo envío", editor::submit, Modifier.fillMaxWidth(), enabled = !e.busy)
            return@BpmFormScaffold
        }
        if (e.reviewing) {
            Text("Revisa antes de confirmar", style = MaterialTheme.typography.titleMedium)
            IncidentReview(f)
            if (f.kind == "VERIFY" && IncidentsAccess.isSelfVerification(s.detail?.actions.orEmpty(), s.userId))
                Text(
                    "Confirmar cerrará la incidencia. Debes haber revisado la corrección y las fotos. Si eres quien la corrigió, solo puedes validarla cuando no hay otra persona elegible; la nota es obligatoria y quedará marcada como autovalidada."
                )
            if (f.kind == "CATALOG" && f.catalogId != null)
                Text("La versión anterior conservará sus criterios en las incidencias ya reportadas.")
            BpmChunkyButton("Confirmar envío", editor::submit, Modifier.fillMaxWidth(), enabled = !e.busy)
            BpmChunkyButton("Volver a editar", editor::resumeEditing, tone = BpmChunkyTone.White, enabled = !e.busy)
        } else {
            val fields =
                when (f.kind) {
                    "CATALOG" ->
                        (if (f.catalogId == null)
                            listOf(IncidentField("code", "Código del criterio", 1, 40))
                        else emptyList()) +
                            listOf(
                                IncidentField("name", "Nombre", 2, 200),
                                IncidentField("area", "Área", 2, 100),
                                IncidentField(
                                    "normativeCriterion",
                                    "Criterio normativo aplicable",
                                    1,
                                    4000,
                                ),
                            )
                    "REPORT" -> listOf(IncidentField("description", "Describe lo observado", 1, 4000))
                    "ASSIGN" ->
                        listOf(
                            IncidentField("description", "Qué hay que hacer", 1, 2000),
                            IncidentField(
                                "dueDate",
                                "Fecha límite en la sede (yyyy-MM-dd)",
                                10,
                                10,
                                "yyyy-MM-dd",
                            ),
                            IncidentField("dueTime", "Hora límite en la sede (HH:mm)", 5, 5, "HH:mm"),
                        )
                    "RESOLVE" ->
                        listOf(
                            IncidentField("note", "¿Qué se corrigió y cuál fue el resultado?", 1, 2000)
                        )
                    "VERIFY" -> listOf(IncidentField("note", "Resultado de la revisión", 1, 2000))
                    "RETURN" ->
                        listOf(
                            IncidentField("note", "Motivo de devolución y qué falta corregir", 1, 2000)
                        )
                    else -> emptyList()
                }
            if (f.kind == "REPORT") {
                // Sin catálogo configurado también se puede reportar: las categorías integradas van siempre, junto a las de la organización.
                LaunchedEffect(s.authorized) { if (s.authorized && s.picker == null && s.catalogChoices.rows.isEmpty()) vm.picker(IncidentPicker.CATALOG) }
                Text("¿Qué tipo de problema es?", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    reportOptions(s.catalogChoices.rows).forEach { option ->
                        BpmFilterChip(
                            (f.values["catalogId"].orEmpty().ifEmpty { f.values["catalogCode"].orEmpty() }) == option.key,
                            { editor.selectReportOption(option) },
                            label = { Text(option.name) },
                            enabled = !e.busy,
                        )
                    }
                }
                e.errors["catalogId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (f.kind == "ASSIGN") {
                f.values["responsibleName"]?.let { BpmInfoRow(BpmSymbol.Team, "Responsable", it) } ?: Text("Elige quién lo va a corregir.")
                BpmChunkyButton(if (f.values["responsibleName"] == null) "Elegir responsable" else "Cambiar responsable", { vm.picker(IncidentPicker.ASSIGNEE) }, tone = BpmChunkyTone.White, enabled = !e.busy)
                e.errors["responsibleUserId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    if (s.timeZone.isBlank())
                        "La sede no tiene una zona horaria válida. Solicita que se configure antes de asignar un plazo."
                    else "Elige el plazo: fecha y hora de la sede.",
                    style = MaterialTheme.typography.bodySmall,
                )
                e.errors["dueAt"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            val navigation = rememberBpmFormNavigation(fields.map { it.key })
            fields.forEach { field ->
                if (field.key == "dueTime") {
                    BpmTimePicker(f.values["dueTime"].orEmpty(), { editor.edit("dueTime", it) }, "Hora límite en la sede", optional = false,
                        error = e.errors["dueTime"], enabled = !e.busy, supportingText = "Hora de la sede.")
                    return@forEach
                }
                if (field.key == "dueDate") {
                    // La fecha límite se elige en un calendario (no se escribe «yyyy-MM-dd»); la hora se elige con BpmTimePicker.
                    BpmDatePicker(f.values["dueDate"].orEmpty(), { editor.edit("dueDate", it) }, "Fecha límite en la sede", optional = false,
                        min = BpmCalendar.today().iso, error = e.errors["dueDate"], enabled = !e.busy)
                    return@forEach
                }
                BpmTextField(
                    f.values[field.key].orEmpty(),
                    { editor.edit(field.key, it) },
                    field.label,
                    error = e.errors[field.key],
                    enabled = !e.busy,
                    placeholder = field.placeholder,
                    singleLine = field.key !in setOf("normativeCriterion", "description", "note"),
                    rule = InputRules.text(field.min, field.max),
                    navigation = navigation[field.key],
                )
            }
            if (f.kind == "CATALOG") {
                Text("Nivel de riesgo", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IncidentRisk.entries.forEach { risk ->
                        BpmFilterChip(
                            f.values["riskLevel"] == risk.name,
                            { editor.edit("riskLevel", risk.name) },
                            label = { Text(incidentRiskLabel(risk)) },
                            enabled = !e.busy,
                        )
                    }
                }
                // BpmCheckboxRow: etiqueta pulsable, alineada con la casilla y con área táctil de 48 dp.
                BpmCheckboxRow(
                    "Exigir fotos antes y después para cerrar",
                    f.values["evidenceRequired"] == "true",
                    enabled = !e.busy,
                ) { editor.edit("evidenceRequired", it.toString()) }
            }
            if (f.kind == "START") {
                // Una sola confirmación: antes eran tres toques y dos pantallas casi vacías para un cambio de estado.
                Text("Confirma que empiezas a corregirlo ahora. Quedará registrado a tu nombre.")
                BpmChunkyButton("Sí, empezar ahora", { editor.review(); if (editor.state.value.reviewing) editor.submit() }, Modifier.fillMaxWidth(), enabled = !e.busy)
            } else BpmChunkyButton("Revisar antes de enviar", editor::review, Modifier.fillMaxWidth(), enabled = !e.busy)
        }
        BpmChunkyButton("Guardar y volver", { editor.close { vm.refresh() } }, tone = BpmChunkyTone.White, enabled = !e.busy)
        BpmTextButton(editor::discard, enabled = !e.busy) { Text("Descartar este borrador") }
    }
}

@Composable
private fun IncidentReview(f: IncidentForm) {
    val labels =
        mapOf(
            "code" to "Código",
            "name" to "Nombre",
            "area" to "Área",
            "normativeCriterion" to "Criterio normativo",
            "description" to (if (f.kind == "ASSIGN") "Qué hay que hacer" else "Qué pasó"),
            "note" to "Explicación",
            "catalogName" to "Tipo",
            "responsibleName" to "Responsable",
            "dueDate" to "Fecha límite",
            "dueTime" to "Hora límite",
            
        )
    labels.forEach { (key, label) ->
        f.values[key]?.takeIf { it.isNotBlank() }?.let { Text("$label: " + when (key) { "dueDate" -> BpmCalendar.parse(it)?.spoken ?: it; "dueTime" -> pe.bpm.core.presentation.displayClock(it); else -> it }) }
    }
    f.values["riskLevel"]?.let { Text("Riesgo: ${incidentRiskLabel(IncidentRisk.valueOf(it))}") }
    f.values["evidenceRequired"]?.let {
        Text(if (it == "true") "Requiere fotos antes y después." else "Fotos opcionales.")
    }
}
