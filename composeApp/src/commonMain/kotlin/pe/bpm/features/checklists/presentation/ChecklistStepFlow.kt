package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.DecimalInput
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.checklists.domain.ChecklistInputRules
import pe.bpm.features.checklists.domain.ChecklistItem
import pe.bpm.features.checklists.domain.ChecklistResponseType
import pe.bpm.features.checklists.domain.RunAnswerInput
import pe.bpm.features.checklists.domain.RunFailure
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done

/**
 * Ejecución de un control del jefe de cocina, **una pregunta por pantalla** (modo enfoque): barra de avance con «3/6», la
 * sección y la pregunta, tres fichas **Cumple / No cumple / No aplica** (o un número grande con su criterio, o un texto),
 * la observación cuando no cumple (con la foto si la plantilla la pide) y «Anterior / Siguiente» al pie. Tras la última
 * pregunta, «Revisar» guarda las respuestas (si hay cambios) y abre la revisión, donde se confirma y finaliza.
 *
 * Es solo una vista: las respuestas viven en [ChecklistRunState.inputs] y se guardan, revisan y envían con la misma
 * lógica de `ChecklistRunViewModel` (borrador local, operación pendiente, conflicto de versión); no hay otro camino de
 * envío. [notices] va arriba del contenido (aviso de salida con cambios, avisos del borrador sin conexión).
 */
@Composable
internal fun ChecklistStepFlow(
    state: ChecklistRunState,
    onEdit: (String, RunAnswerInput) -> Unit,
    onSave: () -> Unit,
    onReview: () -> Unit,
    onSubmit: () -> Unit,
    onBackToEdit: () -> Unit,
    onRetry: () -> Unit,
    onProtectDraft: () -> Unit,
    onClose: () -> Unit,
    onFinished: () -> Unit,
    notices: @Composable ColumnScope.() -> Unit,
    evidence: @Composable (ChecklistItem) -> Unit,
    modifier: Modifier = Modifier,
    /** «Trabajar sin conexión»: prepara el control en el dispositivo (solo en la primera pregunta, sin cambios por guardar). */
    onWorkOffline: (() -> Unit)? = null,
) {
    val run = state.run ?: return
    if (run.status == "SUBMITTED" && !state.dirty) {
        FinishedStage(run.templateName, run.compliancePercent, onFinished, modifier)
        return
    }
    val items = run.items
    if (items.isEmpty()) return
    var index by rememberSaveable(run.id) { mutableIntStateOf(firstPendingStep(items, state.inputs)) }
    val current = index.coerceIn(0, items.lastIndex)
    // «Revisar» guarda primero (si hay cambios) y abre la revisión cuando el servidor confirma; un fallo cancela la espera.
    var reviewAfterSave by remember(run.id) { mutableStateOf(false) }
    LaunchedEffect(reviewAfterSave, state.dirty, state.busy, state.pending, state.failure) {
        if (reviewAfterSave && !state.busy) {
            if (state.failure != null || state.pending) reviewAfterSave = false
            else if (!state.dirty) { reviewAfterSave = false; onReview() }
        }
    }
    val stateNotices: @Composable ColumnScope.() -> Unit = {
        notices()
        StateNotices(state, onRetry, onProtectDraft)
    }
    if (state.reviewing) {
        ReviewStage(state, items, onEdit, onSubmit, onBackToEdit, onClose, stateNotices, evidence, goTo = { index = it; onBackToEdit() }, modifier)
        return
    }
    QuestionStage(state, items, current, onEdit, onClose, stateNotices, evidence, modifier, onWorkOffline,
        onPrevious = { index = (current - 1).coerceAtLeast(0) },
        onNext = { index = (current + 1).coerceAtMost(items.lastIndex) },
        onGoTo = { index = it },
        onReview = { if (state.dirty) { reviewAfterSave = true; onSave() } else onReview() },
    )
}

@Composable
private fun QuestionStage(
    state: ChecklistRunState,
    items: List<ChecklistItem>,
    index: Int,
    onEdit: (String, RunAnswerInput) -> Unit,
    onClose: () -> Unit,
    notices: @Composable ColumnScope.() -> Unit,
    evidence: @Composable (ChecklistItem) -> Unit,
    modifier: Modifier,
    onWorkOffline: (() -> Unit)?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoTo: (Int) -> Unit,
    onReview: () -> Unit,
) {
    val item = items[index]
    val input = state.inputs[item.id] ?: RunAnswerInput()
    val last = index == items.lastIndex
    val busy = !state.editable
    val canAdvance = stepCanAdvance(item, input)
    val missing = items.indexOfFirst { it.required && !stepAnswered(it, state.inputs[it.id] ?: RunAnswerInput()) }
    val compliance = item.responseType == ChecklistResponseType.COMPLIANCE
    // Las teclas 1–3 y Enter solo mientras no haya un campo de texto en pantalla (con «No cumple» aparece la observación).
    val keys = if (compliance && input.value != "NON_COMPLIANT") Modifier.bpmPlayKeys(3,
        { choice -> if (!busy) onEdit(item.id, input.copy(value = complianceLabels.keys.elementAt(choice))) },
        { if (canAdvance && !busy && !last) onNext() }, focusKey = index) else Modifier
    BpmPlayStage(
        modifier.then(keys),
        progress = (index + 1).toFloat() / items.size, counter = "${index + 1}/${items.size}",
        progressDescription = "Pregunta ${index + 1} de ${items.size}",
        onClose = onClose, closeDescription = "Salir del control", scrollKey = index,
        footer = {
            val hint = if (last && missing in 0 until index) "Te falta responder la pregunta ${missing + 1}." else null
            hint?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (index > 0) BpmChunkyButton("Anterior", onPrevious, Modifier.weight(1f), tone = BpmChunkyTone.White, enabled = !state.busy)
                when {
                    !last -> BpmChunkyButton("Siguiente", onNext, Modifier.weight(1f), enabled = canAdvance && !busy)
                    missing in 0 until index -> BpmChunkyButton("Ir a la pregunta ${missing + 1}", { onGoTo(missing) }, Modifier.weight(1f), enabled = !busy)
                    else -> BpmChunkyButton(if (state.busy) "Guardando…" else "Revisar", onReview, Modifier.weight(1f), tone = BpmChunkyTone.Correct, enabled = canAdvance && !busy)
                }
            }
        },
    ) {
        notices()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val place = listOf(item.sectionName, if (item.required) "" else "Opcional").filter { it.isNotBlank() }.joinToString(" · ")
            if (place.isNotBlank()) BpmPlayChip(place, BpmPlayTone.Action, Modifier.semantics { heading() }, uppercase = false)
            Text(item.label, style = BpmPlayType.h1.copy(fontSize = 26.sp, lineHeight = 30.sp), color = BpmPlayColors.ink)
            BpmListenButton(item.label + (stepCriterion(item)?.let { ". $it" } ?: ""), label = "Escuchar el paso")
        }
        when (item.responseType) {
            ChecklistResponseType.COMPLIANCE -> ComplianceTiles(input.value, !busy) { onEdit(item.id, input.copy(value = it)) }
            ChecklistResponseType.TEXT -> BpmTextField(input.value, { onEdit(item.id, input.copy(value = it)) }, "Respuesta", Modifier.fillMaxWidth(),
                enabled = !busy, singleLine = false, minLines = 3, rule = ChecklistInputRules.answer)
            else -> NumberAnswer(item, input.value, !busy) { onEdit(item.id, input.copy(value = it)) }
        }
        val failing = stepFailing(item, input)
        if (failing || compliance.not() && input.observation.isNotBlank()) ObservationField(item, input, failing, !busy, onEdit)
        if (failing) evidence(item)
        if (index == 0 && onWorkOffline != null) BpmChunkyButton("Trabajar sin conexión", onWorkOffline, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White,
            enabled = state.editable && !state.dirty && state.failure == null)
    }
}

/** Las tres fichas de una pregunta de cumplimiento (la elegida se marca en teal). */
@Composable
private fun ComplianceTiles(selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmChoiceTile("Cumple", BpmSymbol.Check, BpmPlayTone.Correct, selected == "COMPLIANT", { if (enabled) onPick("COMPLIANT") }, Modifier.weight(1f).fillMaxHeight())
        BpmChoiceTile("No cumple", BpmSymbol.Close, BpmPlayTone.Error, selected == "NON_COMPLIANT", { if (enabled) onPick("NON_COMPLIANT") }, Modifier.weight(1f).fillMaxHeight())
        BpmChoiceTile("No aplica", BpmSymbol.Dots, BpmPlayTone.Action, selected == "NOT_APPLICABLE", { if (enabled) onPick("NOT_APPLICABLE") }, Modifier.weight(1f).fillMaxHeight())
    }
}

/** Observación de una respuesta que no cumple (o que ya la tiene); la foto se pide con el botón de evidencia que sigue. */
@Composable
private fun ObservationField(item: ChecklistItem, input: RunAnswerInput, failing: Boolean, enabled: Boolean, onEdit: (String, RunAnswerInput) -> Unit) {
    if (failing) Text("Cuéntanos qué pasó para corregirlo.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    BpmTextField(input.observation, { onEdit(item.id, input.copy(observation = it)) }, if (failing) "¿Qué observaste?" else "Observación (opcional)",
        Modifier.fillMaxWidth(), enabled = enabled, singleLine = false, minLines = 2, rule = ChecklistInputRules.answer)
}

/**
 * Respuesta numérica o de temperatura: el criterio publicado, el número en grande con su unidad y, al escribir, una
 * ficha que dice si está dentro o fuera del criterio. Acepta coma o punto y hasta dos decimales (misma regla de siempre).
 */
@Composable
private fun NumberAnswer(item: ChecklistItem, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    stepCriterion(item)?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.muted) }
    val error = if (value.isBlank()) null else DecimalInput.error(value)
    val parsed = DecimalInput.parse(value)
    val failing = parsed != null && stepFailing(item, RunAnswerInput(value))
    val border = when { error != null || failing -> BpmPlayColors.error.face; parsed != null -> BpmPlayColors.correctBorder; else -> BpmPlayColors.line }
    Row(
        Modifier.fillMaxWidth().bpmPlayFrame(border, Color.White, 18.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BpmIcon(if (item.responseType == ChecklistResponseType.TEMPERATURE) BpmSymbol.Thermometer else BpmSymbol.Target,
            tint = BpmPlayColors.action.face, modifier = Modifier.size(32.dp))
        BasicTextField(
            value, { next -> onChange(next.filter { it.isDigit() || it == ',' || it == '.' || it == '-' }.take(12)) },
            Modifier.weight(1f).semantics { contentDescription = "Valor medido${item.unit.takeIf { it.isNotBlank() }?.let { " en $it" }.orEmpty()}" },
            enabled = enabled, singleLine = true,
            textStyle = BpmPlayType.celebration.copy(fontSize = 40.sp, lineHeight = 44.sp, color = BpmPlayColors.ink),
            cursorBrush = SolidColor(BpmPlayColors.action.face),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            decorationBox = { inner ->
                if (value.isEmpty()) Text("0", style = BpmPlayType.celebration.copy(fontSize = 40.sp, lineHeight = 44.sp), color = BpmPlayColors.lock)
                inner()
            },
        )
        if (item.unit.isNotBlank()) Text(item.unit, style = BpmPlayType.celebration.copy(fontSize = 22.sp, lineHeight = 26.sp), color = BpmPlayColors.muted)
    }
    if (error != null) Text(error, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    else if (parsed != null) BpmPlayChip(if (failing) "Fuera del criterio" else "Dentro del criterio", if (failing) BpmPlayTone.Error else BpmPlayTone.Correct)
}

/** Revisión antes de finalizar: cada respuesta (tocarla vuelve a su pregunta), confirmar y finalizar o volver. */
@Composable
private fun ReviewStage(
    state: ChecklistRunState,
    items: List<ChecklistItem>,
    onEdit: (String, RunAnswerInput) -> Unit,
    onSubmit: () -> Unit,
    onBackToEdit: () -> Unit,
    onClose: () -> Unit,
    notices: @Composable ColumnScope.() -> Unit,
    evidence: @Composable (ChecklistItem) -> Unit,
    goTo: (Int) -> Unit,
    modifier: Modifier,
) {
    BpmPlayStage(
        modifier, progress = 1f, counter = "${items.size}/${items.size}", progressDescription = "Todas las preguntas respondidas",
        onClose = onClose, closeDescription = "Salir del control", scrollKey = "review",
        footer = {
            BpmChunkyButton(if (state.busy) "Finalizando…" else "Confirmar y finalizar", onSubmit, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Correct, enabled = state.editable)
            BpmChunkyButton("Volver a las respuestas", onBackToEdit, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !state.busy && !state.pending)
        },
    ) {
        notices()
        Text("Revisa antes de finalizar", style = BpmPlayType.h1.copy(fontSize = 26.sp, lineHeight = 30.sp), color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        Text("Al finalizar, las respuestas quedarán cerradas y se mostrará tu resultado.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        items.forEachIndexed { number, item ->
            val input = state.inputs[item.id] ?: RunAnswerInput()
            val failing = stepFailing(item, input)
            BpmPlayCard(Modifier.clickable(enabled = state.editable, role = Role.Button, onClickLabel = "Cambiar la respuesta ${number + 1}") { goTo(number) }) {
                Text("${number + 1}. ${item.label}", style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                val answer = complianceLabels[input.value] ?: input.value.ifBlank { "Sin respuesta" } + item.unit.takeIf { input.value.isNotBlank() && it.isNotBlank() }?.let { " $it" }.orEmpty()
                BpmPlayChip(answer, when { input.value.isBlank() -> BpmPlayTone.Gold; failing -> BpmPlayTone.Error; else -> BpmPlayTone.Correct }, uppercase = false)
                if (input.observation.isNotBlank()) Text("Observación: ${input.observation}", style = BpmPlayType.sub, color = BpmPlayColors.muted)
                if (failing) evidence(item)
            }
        }
    }
}

/** Avisos del estado del control: fallo (con su salida), operación pendiente y fallo del borrador local. */
@Composable
private fun StateNotices(state: ChecklistRunState, onRetry: () -> Unit, onProtectDraft: () -> Unit) {
    state.notice?.takeIf { state.failure == null }?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.action.face, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    state.failure?.let { failure ->
        Text(runFailureMessage(failure), style = BpmPlayType.sub, color = if (failure == RunFailure.QUEUED) BpmPlayColors.goldText else BpmPlayColors.error.shadow,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (state.pending) {
        if (state.failure == null) Text("Hay una operación sin confirmar. Compruébala antes de seguir.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        BpmChunkyButton(if (state.failure == null) "Comprobar operación pendiente" else "Reintentar operación", onRetry, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !state.busy)
    }
    if (state.localStatus == DraftSaveStatus.FAILED) {
        Text("No pudimos conservar el borrador en este dispositivo. Evita cerrar la aplicación hasta guardarlo.", style = BpmPlayType.sub, color = BpmPlayColors.error.shadow,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        BpmChunkyButton("Reintentar protección del borrador", onProtectDraft, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}

/** Control finalizado: el tomate, el cumplimiento y la salida. */
@Composable
private fun FinishedStage(templateName: String, compliance: Double?, onFinished: () -> Unit, modifier: Modifier) {
    val percent = compliance?.let { (DecimalInput.format(it) ?: it.toString()).replace('.', ',') }
    BpmPlayStage(modifier, centered = true, footer = { BpmChunkyButton("Volver a Controles", onFinished, Modifier.fillMaxWidth()) }) {
        BpmCelebration(
            Res.drawable.mascot_done, "¡Control finalizado!", templateName.ifBlank { null },
            percent?.let { listOf(BpmStatTileSpec("Cumplimiento", "$it %", BpmPlayTone.Correct, BpmSymbol.Check)) }.orEmpty(),
            titleColor = BpmPlayColors.correct.shadow,
        )
        Text("Las respuestas enviadas ya no se pueden editar.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    }
}
