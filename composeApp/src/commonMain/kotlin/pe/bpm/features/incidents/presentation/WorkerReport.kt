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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.InputRules
import pe.bpm.features.incidents.domain.IncidentCatalog
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done
import pe.bpm.resources.mascot_fix

/**
 * Reportar un problema del personal (modo enfoque, pantalla completa), en tres pasos con [BpmStepDots]: **qué problema**
 * (el tomate pregunta y una ficha por categoría del catálogo de incidencias de la organización, con icono por palabras
 * clave: [reportIcon]) → **foto** («Tomar foto» o «Ahora no») → **dónde y una nota corta** → «Enviar reporte».
 * El envío es el de siempre ([IncidentsViewModel.editor]: borrador protegido, cola sin conexión y clave estable).
 *
 * La foto de una incidencia se adjunta a la incidencia ya creada: si el personal eligió «Tomar foto», al confirmarse el
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
        ReportSent(s, wantPhoto, onClose, onEvidence, modifier)
        return
    }
    if (form == null) {
        BpmPlayStage(modifier, onClose = onClose, centered = true) { Text("Preparando el reporte…", style = BpmPlayType.sub, color = BpmPlayColors.muted) }
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
    val step = if (e.pending != null) ReportStep.Place else ReportStep.valueOf(stepName)
    val selectedId = values["catalogId"]
    val selected = s.catalogChoices.rows.firstOrNull { it.id == selectedId }
    val busy = e.busy
    fun go(target: ReportStep) { stepName = target.name }
    fun send() {
        if (e.pending == null) {
            editor.edit("description", reportDescription(place, note))
            editor.review()
        }
        // Marca «enviando» solo si el envío realmente arranca (sin errores de validación).
        if (editor.state.value.reviewing || e.pending != null) { onSending(); editor.submit() }
    }
    val canSend = place.isNotBlank() || note.isNotBlank() || e.pending != null
    fun close() { if (e.pending != null) viewModel.prepareLeave(onClose) else editor.close(onClose) }

    BpmPlayStage(
        modifier.bpmPlayKeys(0, {}, {
            when (step) {
                ReportStep.Category -> if (selectedId != null) go(ReportStep.Photo)
                ReportStep.Photo -> Unit
                ReportStep.Place -> if (canSend && !busy) send()
            }
        }, focusKey = step),
        onClose = ::close, closeDescription = "Salir del reporte", steps = 3, step = step.ordinal + 1, scrollKey = step,
        footer = {
            when (step) {
                ReportStep.Category -> BpmChunkyButton("Continuar", { go(ReportStep.Photo) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, enabled = selectedId != null)
                ReportStep.Photo -> {
                    BpmChunkyButton("Tomar foto", { setWantPhoto(true); go(ReportStep.Place) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, icon = BpmSymbol.Camera)
                    BpmChunkyButton("Ahora no", { setWantPhoto(false); go(ReportStep.Place) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                }
                ReportStep.Place -> BpmChunkyButton(
                    if (busy) "Enviando…" else if (e.pending != null) "Reintentar el mismo envío" else "Enviar reporte",
                    ::send, Modifier.fillMaxWidth(), tone = BpmChunkyTone.Report, enabled = canSend && !busy,
                )
            }
        },
    ) {
        if (step != ReportStep.Category && e.pending == null) ReportBack { go(ReportStep.entries[step.ordinal - 1]) }
        when (step) {
            ReportStep.Category -> CategoryStep(s, selectedId, { viewModel.editor.selectCatalog(it) }, viewModel, onOpenList)
            ReportStep.Photo -> PhotoStep(selected)
            ReportStep.Place -> PlaceStep(e, place, note, !busy, { place = it }, { note = it })
        }
    }
}

/** Primer paso: el tomate pregunta y una ficha por categoría (dos por fila). */
@Composable
private fun CategoryStep(s: IncidentsState, selectedId: String?, onPick: (IncidentCatalog) -> Unit, viewModel: IncidentsViewModel, onOpenList: () -> Unit) {
    BpmMascotRow(Res.drawable.mascot_fix, "¿Qué problema encontraste?", imageWidth = 72.dp)
    val choices = s.catalogChoices
    when {
        choices.failure != null -> {
            Text(incidentFailureText(choices.failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmChunkyButton("Volver a intentar", { viewModel.picker(IncidentPicker.CATALOG) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        choices.rows.isEmpty() && choices.loading -> Text("Buscando las categorías…", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        choices.rows.isEmpty() -> Text("Tu organización todavía no configuró categorías de incidencias. Pide a tu administrador que las cree.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        else -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            choices.rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { catalog ->
                        val icon = reportIcon(catalog.name)
                        BpmChoiceTile(catalog.name, icon.symbol, icon.tone, catalog.id == selectedId, { onPick(catalog) }, Modifier.weight(1f).fillMaxHeight())
                    }
                    if (pair.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
            if (choices.next.isNotEmpty() || choices.previous.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (choices.previous.isNotEmpty()) BpmChunkyButton("Anteriores", viewModel::pickerPrevious, Modifier.weight(1f), tone = BpmChunkyTone.White, enabled = !choices.loading)
                if (choices.next.isNotEmpty()) BpmChunkyButton("Más categorías", viewModel::pickerNext, Modifier.weight(1f), tone = BpmChunkyTone.White, enabled = !choices.loading)
            }
        }
    }
    Row(
        Modifier.heightIn(min = BpmSpacing.touch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpenList).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text("Ver mis reportes", style = BpmPlayType.key, color = BpmPlayColors.action.face) }
}

/** Segundo paso: foto del problema (se adjunta justo después de enviar). */
@Composable
private fun PhotoStep(selected: IncidentCatalog?) {
    Text("Toma una foto del problema", style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink)
    Text("Ayuda a que lo resuelvan más rápido. La foto se adjunta al reporte justo después de enviarlo.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    val line = BpmPlayColors.navBorder
    Column(
        Modifier.fillMaxWidth().heightIn(min = 160.dp).clip(RoundedCornerShape(18.dp)).background(BpmPlayColors.streak.soft)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawRoundRect(line, Offset(stroke / 2f, stroke / 2f), Size(size.width - stroke, size.height - stroke), CornerRadius(18.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
            }
            .semantics { contentDescription = "La foto se toma después de enviar el reporte" }.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        BpmIcon(BpmSymbol.Camera, tint = BpmPlayColors.streak.face, modifier = Modifier.size(40.dp))
        Text(selected?.name ?: "Foto del problema", style = BpmPlayType.h2, color = BpmPlayColors.streak.shadow)
    }
    if (selected?.evidenceRequired == true) Text("Esta categoría pide fotos para poder cerrarla.", style = BpmPlayType.sub, color = BpmPlayColors.gold.shadow)
}

/** Tercer paso: dónde está y una nota corta; al menos una de las dos. */
@Composable
private fun PlaceStep(e: IncidentEditorState, place: String, note: String, enabled: Boolean, onPlace: (String) -> Unit, onNote: (String) -> Unit) {
    Text("¿Dónde está?", style = BpmPlayType.h1.copy(fontSize = 24.sp, lineHeight = 27.sp), color = BpmPlayColors.ink)
    Text("Escribe el lugar y, si quieres, una nota corta.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
    BpmTextField(place, onPlace, "Lugar (cocina, almacén, salón…)", Modifier.fillMaxWidth(), enabled = enabled, rule = InputRules.text(0, 120))
    BpmTextField(note, onNote, "Nota (opcional)", Modifier.fillMaxWidth(), enabled = enabled, error = e.errors["description"], rule = InputRules.text(0, 1000))
    if (e.pending != null) Text("El envío aún no está confirmado. Reintenta para consultar su resultado.", style = BpmPlayType.sub, color = BpmPlayColors.gold.shadow)
    e.failure?.let { Text(incidentFailureText(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) }
    e.errors["catalogId"]?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow) }
}

/** «‹ Atrás» al inicio de los pasos 2 y 3. */
@Composable
private fun ReportBack(onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = BpmSpacing.touch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BpmIcon(BpmSymbol.Back, tint = BpmPlayColors.action.face, modifier = Modifier.size(20.dp))
        Text("Atrás", style = BpmPlayType.key, color = BpmPlayColors.action.face)
    }
}

/** Confirmación del envío; abre las fotos de la incidencia si se pidieron y ofrece agregarlas después. */
@Composable
private fun ReportSent(
    s: IncidentsState,
    wantPhoto: Boolean,
    onClose: () -> Unit,
    onEvidence: (String, String?, String, Boolean) -> Unit,
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
            if (id != null && !wantPhoto) BpmChunkyButton("Agregar foto", { onEvidence(id, null, "BEFORE", true) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, icon = BpmSymbol.Camera)
        },
    ) {
        BpmCelebration(Res.drawable.mascot_done, "¡Reporte enviado!", detail?.summary?.name, emptyList(), titleColor = BpmPlayColors.correct.shadow,
            middle = {
                BpmPlayCard {
                    Text(
                        s.notice ?: "Tu supervisor lo verá y asignará quién lo corrige. Gracias por avisar.",
                        style = BpmPlayType.sub, color = BpmPlayColors.muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            })
    }
}
