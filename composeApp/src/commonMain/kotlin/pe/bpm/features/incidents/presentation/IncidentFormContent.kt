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
    Text(title, style = MaterialTheme.typography.titleLarge)
    if (e.recovered)
        Text("Recuperamos tu copia local de esta sede. Revisa los datos antes de continuar.")
    Text(
        when (e.saveStatus) {
            DraftSaveStatus.SAVING -> "Guardando copia local…"
            DraftSaveStatus.SAVED -> "Copia local guardada"
            DraftSaveStatus.FAILED -> "No se pudo guardar la copia local"
            else -> "Los cambios se guardan en este dispositivo."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    e.failure?.let {
        // Al validar, un 403/409 suele significar que existe otra persona elegible para validar.
        val text = if (f.kind == "VERIFY" && (it == IncidentsFailure.DENIED || it == IncidentsFailure.CONFLICT))
            IncidentsAccess.SELF_VALIDATION_DENIED + ". " + incidentFailureText(it) else incidentFailureText(it)
        Text(text, color = MaterialTheme.colorScheme.error)
    }
    if (e.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (e.pending != null) {
        Text("El envío aún no está confirmado. Reintenta para consultar su resultado.")
        IncidentReview(f)
        BpmChunkyButton("Reintentar el mismo envío", editor::submit, Modifier.fillMaxWidth(), enabled = !e.busy)
        return
    }
    if (e.reviewing) {
        Text("Revisa antes de confirmar", style = MaterialTheme.typography.titleMedium)
        IncidentReview(f)
        if (f.kind == "VERIFY")
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
                        IncidentField("description", "Plan de acción correctiva", 1, 2000),
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
            Text(f.values["responsibleName"] ?: "Selecciona una persona activa de esta sede.")
            BpmChunkyButton("Elegir responsable", { vm.picker(IncidentPicker.ASSIGNEE) }, tone = BpmChunkyTone.White, enabled = !e.busy)
            e.errors["responsibleUserId"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(
                if (s.timeZone.isBlank())
                    "La sede no tiene una zona horaria válida. Solicita que se configure antes de asignar un plazo."
                else "Fecha y hora local de la sede (${s.timeZone}).",
                style = MaterialTheme.typography.bodySmall,
            )
            e.errors["dueAt"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        val navigation = rememberBpmFormNavigation(fields.map { it.key })
        fields.forEach { field ->
            if (field.key == "dueDate") {
                // La fecha límite se elige en un calendario (no se escribe «yyyy-MM-dd»); la hora sigue siendo un campo HH:mm.
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
        if (f.kind == "START") Text("Confirma que comenzarás a atender la acción asignada.")
        BpmChunkyButton("Revisar antes de enviar", editor::review, Modifier.fillMaxWidth(), enabled = !e.busy)
    }
    BpmChunkyButton("Guardar y volver", { editor.close { vm.refresh() } }, tone = BpmChunkyTone.White, enabled = !e.busy)
    BpmTextButton(editor::discard, enabled = !e.busy) { Text("Descartar este borrador") }
}

@Composable
private fun IncidentReview(f: IncidentForm) {
    val labels =
        mapOf(
            "code" to "Código",
            "name" to "Nombre",
            "area" to "Área",
            "normativeCriterion" to "Criterio normativo",
            "description" to "Descripción",
            "note" to "Explicación",
            "catalogName" to "Criterio",
            "responsibleName" to "Responsable",
            "dueDate" to "Fecha límite",
            "dueTime" to "Hora límite",
            "dueZone" to "Zona horaria de la sede",
        )
    labels.forEach { (key, label) ->
        f.values[key]?.takeIf { it.isNotBlank() }?.let { Text("$label: $it") }
    }
    f.values["riskLevel"]?.let { Text("Riesgo: ${incidentRiskLabel(IncidentRisk.valueOf(it))}") }
    f.values["evidenceRequired"]?.let {
        Text(if (it == "true") "Requiere fotos antes y después." else "Fotos opcionales.")
    }
}
