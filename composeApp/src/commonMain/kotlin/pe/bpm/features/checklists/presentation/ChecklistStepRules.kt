package pe.bpm.features.checklists.presentation

import pe.bpm.core.domain.DecimalInput
import pe.bpm.features.checklists.domain.ChecklistItem
import pe.bpm.features.checklists.domain.ChecklistResponseType
import pe.bpm.features.checklists.domain.RunAnswerInput
import pe.bpm.features.checklists.domain.RunFailure

/*
 * Reglas puras de la ejecución de un control «una pregunta por pantalla». No guardan nada: las respuestas siguen en
 * `ChecklistRunState.inputs` y se guardan, revisan y envían con la misma lógica de siempre (`ChecklistRunViewModel`).
 */

/**
 * La ejecución usa la pantalla por pasos: el control está en borrador y es de la persona, o ya se finalizó (celebración).
 * Un conflicto de versión conserva la pantalla de siempre, que sabe resolverlo.
 */
internal fun usesStepStage(state: ChecklistRunState, userId: String?, runId: String?): Boolean {
    val run = state.run ?: return false
    if (state.userId != userId || (runId != null && run.id != runId)) return false
    if (state.conflicted || state.failure == RunFailure.CONFLICT) return false
    return run.status == "SUBMITTED" || run.status == "DRAFT" && run.performedBy == userId
}

/** Respuesta completa de una pregunta: ficha elegida, número válido o texto no vacío. */
internal fun stepAnswered(item: ChecklistItem, input: RunAnswerInput): Boolean = when (item.responseType) {
    ChecklistResponseType.COMPLIANCE -> input.value in complianceLabels.keys
    ChecklistResponseType.TEXT -> input.value.isNotBlank()
    else -> DecimalInput.parse(input.value) != null
}

/** Se puede pasar a la siguiente: respondida, o vacía si la pregunta no es obligatoria. */
internal fun stepCanAdvance(item: ChecklistItem, input: RunAnswerInput): Boolean =
    stepAnswered(item, input) || (!item.required && input.value.isBlank())

/** La respuesta incumple el criterio: «No cumple» o un valor fuera del rango publicado. */
internal fun stepFailing(item: ChecklistItem, input: RunAnswerInput): Boolean = when (item.responseType) {
    ChecklistResponseType.COMPLIANCE -> input.value == "NON_COMPLIANT"
    ChecklistResponseType.TEXT -> false
    else -> DecimalInput.parse(input.value)?.let { value ->
        item.minimumValue != null && value < item.minimumValue || item.maximumValue != null && value > item.maximumValue
    } ?: false
}

/** «Criterio publicado: 0 a 5 °C»; nulo en preguntas sin rango. */
internal fun stepCriterion(item: ChecklistItem): String? =
    if (item.responseType in setOf(ChecklistResponseType.NUMBER, ChecklistResponseType.TEMPERATURE) && item.minimumValue != null && item.maximumValue != null)
        "Criterio publicado: ${number(item.minimumValue)} a ${number(item.maximumValue)}${item.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}"
    else null

private fun number(value: Double): String = (DecimalInput.format(value) ?: value.toString()).replace('.', ',')

/** Primera pregunta sin responder (para retomar un borrador); la última si ya están todas. */
internal fun firstPendingStep(items: List<ChecklistItem>, inputs: Map<String, RunAnswerInput>): Int =
    items.indexOfFirst { !stepAnswered(it, inputs[it.id] ?: RunAnswerInput()) }.takeIf { it >= 0 } ?: (items.size - 1).coerceAtLeast(0)

/** Preguntas respondidas, para el contador de avance. */
internal fun answeredSteps(items: List<ChecklistItem>, inputs: Map<String, RunAnswerInput>): Int =
    items.count { stepAnswered(it, inputs[it.id] ?: RunAnswerInput()) }

/** Todas las preguntas obligatorias tienen respuesta: se puede pasar a revisar. */
internal fun stepsReadyToReview(items: List<ChecklistItem>, inputs: Map<String, RunAnswerInput>): Boolean =
    items.all { !it.required || stepAnswered(it, inputs[it.id] ?: RunAnswerInput()) }
