package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.DecimalInput
import pe.bpm.core.domain.InputRule
import pe.bpm.features.checklists.domain.*

/** Un mismo formulario para la edición conectada y las revisiones preparadas sin conexión. */
@Composable
fun ChecklistAnswerEditor(items: List<ChecklistItem>, inputs: Map<String, RunAnswerInput>, enabled: Boolean,
    reviewing: Boolean, onEdit: (String, RunAnswerInput) -> Unit, evidence: @Composable (ChecklistItem) -> Unit = {}) {
    val navigation = rememberBpmFormNavigation(items.flatMap { item ->
        (if (item.responseType == ChecklistResponseType.COMPLIANCE) emptyList() else listOf("${item.id}.value")) + "${item.id}.observation"
    })
    items.forEachIndexed { index, item ->
        val input = inputs[item.id] ?: RunAnswerInput()
        if (reviewing) ChecklistAnswerReview(item, index, input, evidence)
        else BpmCard(Modifier.fillMaxWidth()) {
            Eyebrow(item.sectionName)
            Text("${index + 1}. ${item.label}", fontWeight = FontWeight.Bold)
            if (item.required) Text("Respuesta obligatoria", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            if (item.responseType in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE))
                Text("Criterio publicado: ${item.minimumValue} a ${item.maximumValue} ${item.unit}")
            if (item.evidenceRequiredOnFailure) Text("Si esta respuesta no cumple, adjunta una foto antes de finalizar.")
            if (item.responseType == ChecklistResponseType.COMPLIANCE) Column(Modifier.selectableGroup()) {
                complianceLabels.forEach { (value, label) ->
                    BpmChoiceRow(label, input.value == value, enabled) { onEdit(item.id, input.copy(value = value)) }
                }
            } else BpmTextField(input.value, { onEdit(item.id, input.copy(value = it)) },
                if (item.responseType == ChecklistResponseType.TEXT) "Respuesta" else "Valor ${item.unit}",
                enabled = enabled, navigation = navigation["${item.id}.value"],
                inputKind = if (item.responseType == ChecklistResponseType.TEXT) BpmInputKind.Text else BpmInputKind.Decimal,
                rule = if (item.responseType == ChecklistResponseType.TEXT) ChecklistInputRules.answer
                    else InputRule { if (it.isBlank()) null else DecimalInput.error(it) })
            BpmTextField(input.observation, { onEdit(item.id, input.copy(observation = it)) }, "Observación (opcional)",
                enabled = enabled, navigation = navigation["${item.id}.observation"], rule = ChecklistInputRules.answer)
            evidence(item)
        }
    }
}
