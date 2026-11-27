package pe.bpm.features.incidents.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.InputRules
import pe.bpm.features.incidents.domain.IncidentCatalog
import pe.bpm.features.incidents.domain.IncidentsFailure
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done
import pe.bpm.resources.mascot_fix

/**
 * Reportar un problema (modo enfoque, pantalla completa; Personal, Jefe de cocina y Administrador), en dos pasos con
 * [BpmStepDots]: **qué tipo de problema es** (una ficha grande con icono por categoría; las integradas van siempre, así
 * que nadie queda bloqueado porque la administración no creó categorías, y «No sé qué tipo es» lleva a «Otro problema»)
 * → **cuéntanos qué pasó** (texto libre, lugar opcional y, si se quiere, foto) → «Enviar reporte». La sede se elige sola
 * cuando hay una sola. El envío es el de siempre ([IncidentsViewModel.editor]: borrador protegido, cola sin conexión y
 * clave estable).
 *
 * La foto de una incidencia se adjunta a la incidencia ya creada: si la persona marcó «Tomar una foto», al confirmarse el
 * envío se abre el flujo de fotos de la incidencia ([onEvidence]); sin conexión el reporte queda en el dispositivo y la
 * foto se agrega después desde «Ver mis reportes» ([onOpenList]). La X guarda el borrador y sale ([onClose]).
 * Con [startNew] en falso no abre el formulario solo (lo usa quien muestra primero «Mis reportes»).
 */
@Composable
fun WorkerReportContent(
    viewModel: IncidentsViewModel,
    onClose: () -> Unit,
    onEvidence: (incidentId: String, actionId: String?, stage: String, writable: Boolean) -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
    startNew: Boolean = true,
) {
    val s by viewModel.state.collectAsState()
    val e by viewModel.editor.state.collectAsState()
    var sent by rememberSaveable { mutableStateOf(false) }
    var wantPhoto by rememberSaveable { mutableStateOf(false) }
    // El formulario de reporte se abre solo (o recupera el borrador guardado) cuando la sede ya está autorizada.
    LaunchedEffect(s.authorized, e.form == null, sent) {
        if (startNew && s.authorized && e.form == null && !sent) viewModel.newReport()
    }
    // Las categorías (catálogo de incidencias) se piden una vez con el selector del flujo de siempre.
    LaunchedEffect(s.authorized) {
        if (s.authorized && s.picker == null && s.catalogChoices.rows.isEmpty()) viewModel.picker(IncidentPicker.CATALOG)
    }
    val form = e.form
    // Enviado: el formulario se cerró tras confirmar (en línea) o tras guardarse en el dispositivo (sin conexión).
    if (sent && form == null) {
        ReportSent(s, wantPhoto, onClose, onEvidence, onOpenList, modifier)
        return
    }
    if (form == null) {
        BpmPlayStage(modifier, onClose = onClose, centered = true) {
            val failure = s.failure
            if (failure != null && !s.authorized) {
                // Sin sede autorizada no hay reporte: se dice qué pasó y se puede reintentar, nunca una barra sin fin.
                BpmMascotRow(Res.drawable.mascot_fix, "No pudimos preparar el reporte", imageWidth = 72.dp)
                Text(incidentFailureText(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmChunkyButton("Volver a intentar", viewModel::refresh, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report)
            } else BpmSkeletonCard(lines = 2, label = "Preparando el reporte")
        }
        return
    }
    ReportSteps(s, e, form.values, viewModel, onClose, onOpenList, wantPhoto, { wantPhoto = it }, { sent = true }, modifier)
}

@Composable
private fun ReportSteps(
    s: IncidentsState,
    e: IncidentEditorState,
    values: Map<String, String>,
    viewModel: IncidentsViewModel,
    onClose: () -> Unit,
    onOpenList: () -> Unit,
    wantPhoto: Boolean,
    setWantPhoto: (Boolean) -> Unit,
    onSending: () -> Unit,
    modifier: Modifier,
) {
    val editor = viewModel.editor
    var stepName by rememberSaveable { mutableStateOf(ReportStep.Category.name) }
    var place by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val step = if (e.pending != null) ReportStep.Describe else ReportStep.valueOf(stepName)
    val options = remember(s.catalogChoices.rows) { reportOptions(s.catalogChoices.rows) }
    val selectedKey = values["catalogId"].orEmpty().ifEmpty { values["catalogCode"].orEmpty() }
    val selected = options.firstOrNull { it.key == selectedKey }
    val busy = e.busy
    fun go(target: ReportStep) { stepName = target.name }
    fun send() {
        if (e.pending == null) {
            // Sin texto, la descripción es el nombre de la categoría: elegir el tipo ya es un reporte válido.
            editor.edit("description", reportDescription(place, note).ifBlank { selected?.name.orEmpty() })
            editor.review()
        }
        // Marca «enviando» solo si el envío realmente arranca (sin errores de validación).
        if (editor.state.value.reviewing || e.pending != null) { onSending(); editor.submit() }
    }
    val canSend = selected != null && (place.isNotBlank() || note.isNotBlank() || selected.code != "OTRO") || e.pending != null
    fun close() { if (e.pending != null) viewModel.prepareLeave(onClose) else editor.close(onClose) }

    BpmPlayStage(
        modifier.bpmPlayKeys(0, {}, {
            when (step) {
                ReportStep.Category -> if (selected != null) go(ReportStep.Describe)
                ReportStep.Describe -> if (canSend && !busy) send()
            }
        }, focusKey = step),
        onClose = ::close, closeDescription = "Salir del reporte", steps = 2, step = step.ordinal + 1, scrollKey = step,
        footer = {
            when (step) {
                ReportStep.Category -> {
                    BpmChunkyButton("Continuar", { go(ReportStep.Describe) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, enabled = selected != null)
                    BpmChunkyButton("No sé qué tipo es", { editor.selectReportOption(otherOption(options)); go(ReportStep.Describe) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                }
                ReportStep.Describe -> BpmChunkyButton(
                    if (busy) "Enviando…" else if (e.pending != null) "Reintentar el mismo envío" else "Enviar reporte",
                    ::send, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, enabled = canSend && !busy,
                )
            }
        },
    ) {
        if (step != ReportStep.Category && e.pending == null) BpmBackLink { go(ReportStep.Category) }
        when (step) {
            ReportStep.Category -> CategoryStep(options, selectedKey, { editor.selectReportOption(it) }, s.catalogChoices, viewModel, onOpenList)
            ReportStep.Describe -> {
                ReportPriority(values["priority"] == "URGENT", !busy && e.pending == null) { urgent -> editor.edit("priority", if (urgent) "URGENT" else "NORMAL") }
                DescribeStep(e, selected, place, note, wantPhoto, !busy, { place = it }, { note = it }, setWantPhoto)
            }
        }
    }
}

/** Primer paso: el tomate pregunta y una ficha por categoría (dos por fila); las integradas siempre están. */
@Composable
private fun CategoryStep(
    options: List<ReportOption>, selectedKey: String, onPick: (ReportOption) -> Unit, choices: IncidentPage<IncidentCatalog>,
    viewModel: IncidentsViewModel, onOpenList: () -> Unit,
) {
    BpmMascotRow(Res.drawable.mascot_fix, "¿Qué tipo de problema es?", imageWidth = 72.dp)
    BpmListenButton("¿Qué tipo de problema es? " + options.joinToString(". ") { it.name } + ".", label = "Escuchar las opciones")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { option ->
                    val icon = reportIcon(option.name)
                    BpmChoiceTile(option.name, icon.symbol, icon.tone, option.key == selectedKey, { onPick(option) }, Modifier.weight(1f).fillMaxHeight())
                }
                if (pair.size == 1) Column(Modifier.weight(1f)) {}
            }
        }
        if (choices.next.isNotEmpty() || choices.previous.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choices.previous.isNotEmpty()) BpmChunkyButton("Anteriores", viewModel::pickerPrevious, Modifier.weight(1f), tone = BpmChunkyTone.White, enabled = !choices.loading)
            if (choices.next.isNotEmpty()) BpmChunkyButton("Más categorías", viewModel::pickerNext, Modifier.weight(1f), tone = BpmChunkyTone.White, enabled = !choices.loading)
        }
    }
    Row(
        Modifier.heightIn(min = BpmSpacing.touch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpenList).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text("Ver mis reportes", style = BpmPlayType.key, color = BpmPlayColors.action.face) }
}

/** Segundo paso: qué pasó en texto libre, el lugar (opcional) y si se quiere una foto (se adjunta justo después de enviar). */
@Composable
private fun DescribeStep(
    e: IncidentEditorState, selected: ReportOption?, place: String, note: String, wantPhoto: Boolean, enabled: Boolean,
    onPlace: (String) -> Unit, onNote: (String) -> Unit, onPhoto: (Boolean) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Cuéntanos qué pasó", style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink, modifier = Modifier.weight(1f, fill = false))
        selected?.let { BpmPlayChip(it.name, BpmPlayTone.Streak, uppercase = false) }
    }
    val help = if (selected?.code == "OTRO") "Escribe con tus palabras lo que viste." else "Si quieres, agrega un detalle. Con elegir el tipo ya puedes enviarlo."
    Text(help, style = BpmPlayType.sub, color = BpmPlayColors.muted)
    BpmListenButton("Cuéntanos qué pasó. $help")
    BpmTextField(note, onNote, "Qué pasó", Modifier.fillMaxWidth(), enabled = enabled, error = e.errors["description"], rule = InputRules.text(0, 1000), singleLine = false)
    BpmTextField(place, onPlace, "Dónde (cocina, almacén, salón…) · opcional", Modifier.fillMaxWidth(), enabled = enabled, rule = InputRules.text(0, 120))
    BpmChoiceTile("Agregar una foto después de enviar", BpmSymbol.Camera, BpmPlayTone.Streak, wantPhoto, { onPhoto(!wantPhoto) }, Modifier.fillMaxWidth(), horizontal = true)
    if (selected?.evidenceRequired == true) Text("Esta categoría pide fotos para poder cerrarla.", style = BpmPlayType.sub, color = BpmPlayColors.gold.shadow)
    if (e.pending != null && !e.busy) Text("El envío aún no está confirmado. Reintenta para consultar su resultado.", style = BpmPlayType.sub, color = BpmPlayColors.gold.shadow)
    e.failure?.let {
        Text(incidentFailureText(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        // Hasta que el servidor entienda las categorías integradas, «Otro» no es una salida: se dice qué hacer en lugar de quedar atascado.
        if (it == IncidentsFailure.INVALID && selected?.catalogId == null) Text("Si tu administrador ya creó categorías, elige una de la lista; si no, avísale que el reporte no se pudo enviar.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    }
    e.errors["catalogId"]?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow) }
}

/** Confirmación del envío; abre las fotos de la incidencia si se pidieron y ofrece agregarlas después. */
@Composable
private fun ReportSent(
    s: IncidentsState,
    wantPhoto: Boolean,
    onClose: () -> Unit,
    onEvidence: (String, String?, String, Boolean) -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier,
) {
    val detail = s.detail
    val id = detail?.summary?.id
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(id, wantPhoto) { if (wantPhoto && id != null && !opened) { opened = true; onEvidence(id, null, "BEFORE", true) } }
    BpmPlayStage(
        modifier.bpmPlayKeys(0, {}, onClose, focusKey = "enviado"),
        centered = true,
        footer = {
            BpmChunkyButton("Listo", onClose, Modifier.fillMaxWidth())
            BpmChunkyButton("Ver mis reportes", onOpenList, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            // Siempre disponible: si se cerró el panel de fotos sin adjuntar nada, se puede volver a abrir.
            if (id != null) BpmChunkyButton(if (wantPhoto) "Agregar otra foto" else "Agregar foto", { onEvidence(id, null, "BEFORE", true) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, icon = BpmSymbol.Camera)
        },
    ) {
        BpmCelebration(Res.drawable.mascot_done, "¡Reporte enviado!", detail?.summary?.name, emptyList(), titleColor = BpmPlayColors.correct.shadow,
            middle = {
                BpmPlayCard {
                    Text("¿Qué pasa ahora?", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
                    Text(
                        s.notice ?: "Tu supervisor recibe el aviso y asigna quién lo corrige. Cuando lo resuelvan, lo revisan y lo cierran.",
                        style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Text("Puedes ver cómo va en «Ver mis reportes». Gracias por avisar.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
                }
            })
    }
}

/** ¿Es urgente? Urgente avisa al instante al teléfono del jefe; Normal lo deja en sus avisos. */
@Composable
private fun ReportPriority(urgent: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Text("¿Es urgente?", style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmChoiceTile("Normal", BpmSymbol.Check, BpmPlayTone.Action, !urgent, { if (enabled) onChange(false) }, Modifier.weight(1f).fillMaxHeight())
        BpmChoiceTile("Urgente", BpmSymbol.Alert, BpmPlayTone.Streak, urgent, { if (enabled) onChange(true) }, Modifier.weight(1f).fillMaxHeight())
    }
    Text(if (urgent) "Tu jefe recibe un aviso en su teléfono al instante." else "Tu jefe lo verá en sus avisos.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
}
