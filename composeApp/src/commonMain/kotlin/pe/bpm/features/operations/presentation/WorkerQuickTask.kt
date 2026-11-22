package pe.bpm.features.operations.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.InputRules
import pe.bpm.core.offline.LocalOnline
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.operations.domain.CleaningExecution
import pe.bpm.features.operations.domain.CleaningFrequency
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.domain.CleaningResult
import pe.bpm.features.operations.domain.OperationsEntry
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done

/**
 * Registro rápido de una tarea del personal (modo enfoque, pantalla completa): **¿Cómo quedó?** (Bien, Con observación
 * o No se pudo hacer; «Bien» viene marcada) → **nota corta** solo si no quedó bien → **foto** («Tomar foto» o «Ahora no»).
 * Guardar usa el registro de limpieza de siempre ([OperationsViewModel.save]: validación, borrador protegido y cola
 * sin conexión); no hay otro camino de envío. Al terminar muestra la celebración «¡Tarea hecha!» con el avance del turno,
 * «Siguiente tarea» y [backLabel] («Volver a Hoy» / «Volver a Tareas»).
 *
 * La foto se adjunta a la limpieza ya guardada: «Tomar foto» guarda y abre el flujo de fotos de la limpieza
 * ([onCleaningPhotos]); sin conexión la limpieza queda en el dispositivo y la foto se agrega cuando se envíe.
 * La X descarta el borrador ([OperationsViewModel.cancelTask]). [initialStep] solo lo usan las capturas de pantalla.
 */
@Composable
internal fun WorkerQuickTask(
    viewModel: OperationsViewModel,
    onCleaningPhotos: ((CleaningExecution) -> Unit)?,
    backLabel: String,
    modifier: Modifier = Modifier,
    initialStep: QuickStep = QuickStep.Result,
) {
    val state by viewModel.state.collectAsState()
    val form = state.form
    // La foto se pide antes de guardar y se abre cuando el servidor confirma (la limpieza ya existe).
    var wantPhoto by remember(form?.id) { mutableStateOf(false) }
    var photoOpened by remember(form?.id) { mutableStateOf(false) }
    val saved = (state.saved as? OperationsEntry.ExecutionEntry)?.value
    LaunchedEffect(saved?.input?.id, wantPhoto) {
        if (wantPhoto && saved != null && !photoOpened) { photoOpened = true; onCleaningPhotos?.invoke(saved) }
    }
    // Un plan «por turno» exige turno: se elige el que el servidor marca para el plan o el que está abierto ahora.
    val plan = state.selectedPlan
    LaunchedEffect(form?.id, plan?.id, state.shifts, state.editable) {
        if (form != null && state.editable && plan?.frequency == CleaningFrequency.PER_SHIFT && form.values["shiftId"].isNullOrBlank()) {
            val pick = plan.today?.shift?.let { it.id to it.name } ?: state.shifts.firstOrNull { it.openNow }?.let { it.id to it.name }
            if (pick != null) { viewModel.field("shiftId", pick.first); viewModel.field("shiftName", pick.second) }
        }
    }
    val done = state.taskDone
    when {
        done != null -> TaskDoneScreen(state, done, wantPhoto, backLabel, viewModel, modifier)
        form != null -> TaskSteps(state, form.values, plan, initialStep, viewModel, onPhoto = { wantPhoto = true; viewModel.save() }, modifier)
        else -> BpmPlayStage(modifier, centered = true) { Text("Preparando la tarea…", style = BpmPlayType.sub, color = BpmPlayColors.muted) }
    }
}

@Composable
private fun TaskSteps(
    state: OperationsState,
    values: Map<String, String>,
    plan: CleaningPlan?,
    initialStep: QuickStep,
    viewModel: OperationsViewModel,
    onPhoto: () -> Unit,
    modifier: Modifier,
) {
    val result = values["result"]?.let { name -> CleaningResult.entries.firstOrNull { it.name == name } } ?: CleaningResult.COMPLETED
    val steps = quickSteps(result)
    var stepName by rememberSaveable(state.form?.id) { mutableStateOf(initialStep.name) }
    // Con un envío sin confirmar solo queda reintentarlo (paso de foto); «Bien» no tiene paso de nota.
    val step = if (state.pending != null) QuickStep.Photo else QuickStep.valueOf(stepName).takeIf { it in steps } ?: QuickStep.Photo
    val index = steps.indexOf(step)
    fun go(target: QuickStep) { stepName = target.name }
    val busy = state.busy || state.loading
    val note = values["observation"].orEmpty()
    val saveLabel = if (state.busy) "Guardando…" else if (state.pending != null) "Comprobar el mismo registro" else "Tomar foto"

    BpmPlayStage(
        modifier.bpmPlayKeys(0, {}, {
            when (step) {
                QuickStep.Result -> go(steps[1])
                QuickStep.Note -> if (note.isNotBlank()) go(QuickStep.Photo)
                QuickStep.Photo -> Unit
            }
        }, focusKey = step),
        onClose = viewModel::cancelTask, closeDescription = "Salir del registro", steps = steps.size, step = index + 1, scrollKey = step,
        footer = {
            when (step) {
                QuickStep.Result -> BpmChunkyButton("Continuar", { go(steps[1]) }, Modifier.fillMaxWidth(), enabled = !busy)
                QuickStep.Note -> BpmChunkyButton("Continuar", { go(QuickStep.Photo) }, Modifier.fillMaxWidth(), enabled = !busy && note.isNotBlank())
                QuickStep.Photo -> {
                    BpmChunkyButton(saveLabel, onPhoto, Modifier.fillMaxWidth(), icon = BpmSymbol.Camera, enabled = !busy)
                    // «Ahora no» guarda la tarea sin foto.
                    if (state.pending == null) BpmChunkyButton("Ahora no", viewModel::save, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !busy)
                }
            }
        },
    ) {
        if (index > 0 && state.pending == null) BackLink { go(steps[index - 1]) }
        // Aviso sin conexión desde el primer paso: la tarea se registra igual y se envía sola al volver la red.
        if (step != QuickStep.Photo && !LocalOnline.current) BpmPlayNotice("Sin conexión: puedes registrar la tarea; se guarda en el teléfono y se envía sola después.")
        when (step) {
            QuickStep.Result -> ResultStep(plan, values["planName"].orEmpty(), result) { viewModel.field("result", it.name) }
            QuickStep.Note -> NoteStep(result, note, state.fields["observation"], !busy) { viewModel.field("observation", it) }
            QuickStep.Photo -> PhotoStep(state, onPhoto, busy)
        }
    }
}

/** Primera pantalla: la tarea y «¿Cómo quedó?» con tres fichas; «Bien» ya viene marcada. */
@Composable
private fun ResultStep(plan: CleaningPlan?, planName: String, result: CleaningResult, onPick: (CleaningResult) -> Unit) {
    TaskHeader(plan, planName)
    Text("¿Cómo quedó?", style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmChoiceTile("Bien", BpmSymbol.Check, BpmPlayTone.Correct, result == CleaningResult.COMPLETED, { onPick(CleaningResult.COMPLETED) }, Modifier.weight(1f).fillMaxHeight())
        BpmChoiceTile("Con observación", BpmSymbol.Eye, BpmPlayTone.Gold, result == CleaningResult.OBSERVED, { onPick(CleaningResult.OBSERVED) }, Modifier.weight(1f).fillMaxHeight())
    }
    BpmChoiceTile("No se pudo hacer", BpmSymbol.Close, BpmPlayTone.Streak, result == CleaningResult.NOT_COMPLETED, { onPick(CleaningResult.NOT_COMPLETED) }, Modifier.fillMaxWidth(), horizontal = true)
}

/** Segundo paso (solo si no quedó «Bien»): una nota corta obligatoria. */
@Composable
private fun NoteStep(result: CleaningResult, note: String, error: String?, enabled: Boolean, onChange: (String) -> Unit) {
    Text(if (result == CleaningResult.NOT_COMPLETED) "¿Por qué no se pudo?" else "¿Qué observaste?",
        style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink)
    Text("Cuéntalo en una frase corta. Tu supervisor la verá.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    BpmTextField(note, onChange, "Nota", Modifier.fillMaxWidth(), error = error, enabled = enabled, rule = InputRules.text(1, 2000))
}

/** Último paso: foto de cómo quedó, con el aviso de guardado sin conexión cuando corresponde. */
@Composable
private fun PhotoStep(state: OperationsState, onPhoto: () -> Unit, busy: Boolean) {
    Text("Toma una foto de cómo quedó", style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink)
    Text("Sirve como evidencia para la inspección.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    val line = BpmPlayColors.navBorder
    Column(
        Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(18.dp)).background(BpmPlayColors.action.soft)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawRoundRect(line, Offset(stroke / 2f, stroke / 2f), Size(size.width - stroke, size.height - stroke), CornerRadius(18.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
            }
            .clickable(enabled = !busy, role = Role.Button, onClick = onPhoto)
            .semantics { contentDescription = "Tomar foto y guardar la tarea" }.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        BpmIcon(BpmSymbol.Camera, tint = BpmPlayColors.action.face, modifier = Modifier.size(40.dp))
        Text("Tomar foto", style = BpmPlayType.h2, color = BpmPlayColors.action.face)
    }
    if (!LocalOnline.current) BpmPlayNotice("Sin conexión: se guarda en el teléfono y se envía solo después.")
    state.failure?.let { failure ->
        Text(failureText(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
    }
    state.fields["shiftId"]?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.fillMaxWidth()) }
}

/** «‹ Atrás» al inicio de los pasos 2 y 3 (la X cierra todo el registro). */
@Composable
private fun BackLink(onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = BpmSpacing.touch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BpmIcon(BpmSymbol.Back, tint = BpmPlayColors.action.face, modifier = Modifier.size(20.dp))
        Text("Atrás", style = BpmPlayType.key, color = BpmPlayColors.action.face)
    }
}

/** Cabecera con la tarea que se registra: icono, nombre y «Cocina · detalle» (como la fila de misión, sin círculo). */
@Composable
private fun TaskHeader(plan: CleaningPlan?, fallbackName: String) {
    val icon = plan?.let(::missionIcon) ?: MissionIcon(BpmSymbol.Spray, BpmPlayTone.Streak)
    val shade = BpmPlayColors.shade(icon.tone)
    val detail = plan?.let {
        listOfNotNull(it.area.takeIf(String::isNotBlank), it.instructions.lineSequence().firstOrNull()?.takeIf(String::isNotBlank) ?: it.productName.takeIf(String::isNotBlank)?.let { p -> "usa $p" })
            .joinToString(" · ")
    }.orEmpty()
    Row(
        Modifier.fillMaxWidth().bpmPlayFrame(BpmPlayColors.line, Color.White, 18.dp).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(shade.soft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            BpmIcon(icon.symbol, tint = shade.ink, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(plan?.task ?: fallbackName, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, maxLines = 2)
            if (detail.isNotBlank()) Text(detail, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted, maxLines = 2)
        }
    }
}

/** Confirmación «¡Tarea hecha!» con el avance del turno y las salidas: la siguiente tarea o volver. */
@Composable
private fun TaskDoneScreen(
    state: OperationsState,
    done: TaskDone,
    wantPhoto: Boolean,
    backLabel: String,
    viewModel: OperationsViewModel,
    modifier: Modifier,
) {
    val progress = shiftProgress(state.todayPlans, done.planId)
    val clock = clockOf(runCatching { displayDateTime(done.executedAt) }.getOrDefault(""))
    BpmPlayStage(
        modifier.bpmPlayKeys(0, {}, { viewModel.finishTask(progress.next) }, focusKey = done.planId),
        centered = true,
        footer = {
            if (progress.next != null) {
                BpmChunkyButton("Siguiente tarea", { viewModel.finishTask(progress.next) }, Modifier.fillMaxWidth())
                BpmChunkyButton(backLabel, { viewModel.finishTask(null) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            } else BpmChunkyButton(backLabel, { viewModel.finishTask(null) }, Modifier.fillMaxWidth())
        },
    ) {
        BpmCelebration(
            Res.drawable.mascot_done, "¡Tarea hecha!", listOf(done.planName, clock).filter { it.isNotBlank() }.joinToString(" · "), emptyList(),
            titleColor = BpmPlayColors.correct.shadow,
            middle = {
                BpmPlayCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(shiftTitle(state.todayPlans), style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.weight(1f))
                        if (progress.total > 0) BpmPlayChip(progress.label, BpmPlayTone.Correct)
                    }
                    if (progress.total > 0) BpmChunkyProgress(progress.progress, Modifier.fillMaxWidth(), contentDescription = "Avance del turno: ${progress.label}")
                    Text(
                        progress.next?.let { "Te falta: ${nextTaskText(it)}" } ?: "¡Terminaste las tareas del turno!",
                        style = BpmPlayType.sub, color = BpmPlayColors.muted,
                    )
                    if (done.queued) Text(
                        if (wantPhoto) "Guardada en el teléfono. Se enviará cuando haya conexión; la foto se agrega después."
                        else "Guardada en el teléfono. Se enviará cuando haya conexión.",
                        style = BpmPlayType.sub, color = BpmPlayColors.gold.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            },
        )
    }
}
