package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Selector de hora del sistema de diseño: en lugar de escribir «HH:mm» a mano, un campo que muestra la hora («08:30») y, al
 * tocarlo, abre un panel en línea (sin ventana emergente: igual en Android, iOS y web) con atajos y dos cuadrículas de botones grandes.
 *
 * - [value] y [onValueChange] usan `HH:mm` de 24 horas (vacío = sin hora). Con [optional] aparece «Quitar hora».
 * - Atajos: «Ahora» (hora actual del dispositivo, minutos exactos) y las horas de [presets] (por defecto 08:00, 12:00 y 18:00).
 * - Horas en dos bloques «a. m.» y «p. m.» (12, 1…11) en 6 columnas y minutos de 5 en 5 en 6 columnas; cada botón mide al menos 48 dp de alto, lleva su hora dicha en
 *   palabras como descripción para el lector de pantalla y dice «Elegido» además de teñirse.
 * - Al tocar un minuto el panel se cierra; «Listo» lo cierra con la hora ya elegida (las horas en punto no necesitan minutos).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmTimePicker(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    optional: Boolean = true,
    presets: List<String> = listOf("08:00", "12:00", "18:00"),
    error: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    initiallyOpen: Boolean = false,
) {
    val selected = BpmTime.parse(value)
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(Color.White)
                .border(2.dp, if (error != null) BpmPlayColors.error.face else if (open) BpmPlayColors.action.face else BpmTokens.fieldBorder, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = { open = !open })
                .semantics { stateDescription = if (open) "Selector de hora abierto" else "Selector de hora cerrado" }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                Text(selected?.display ?: if (optional) "Sin hora" else "Elige una hora", style = BpmPlayType.rowTitle,
                    color = if (selected != null) BpmPlayColors.ink else BpmPlayColors.muted,
                    modifier = Modifier.semantics { selected?.let { contentDescription = it.text + ", " + it.spoken } })
            }
            BpmIcon(BpmSymbol.Clock, tint = BpmPlayColors.action.face, modifier = Modifier.size(22.dp))
        }
        if (open) Column(
            Modifier.fillMaxWidth().clip(shape).background(Color.White).border(2.dp, BpmPlayColors.line, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmFilterChip(false, { BpmTime.now().let { onValueChange(it.text); open = false } },
                    { Text("Ahora", modifier = Modifier.semantics { contentDescription = "Ahora, la hora actual del dispositivo" }) }, enabled = enabled)
                presets.mapNotNull(BpmTime::parse).forEach { t ->
                    BpmFilterChip(selected == t, { onValueChange(t.text); open = false },
                        { Text(t.display, modifier = Modifier.semantics { contentDescription = t.text + ", " + t.spoken }) }, enabled = enabled)
                }
            }
            Text("Hora (a. m. = mañana · p. m. = tarde y noche)", style = BpmPlayType.label, color = BpmPlayColors.muted)
            Text("Mañana, a. m.", style = BpmPlayType.label, color = BpmPlayColors.ink)
            Grid(12) { h ->
                TimeCell((if (h == 0) 12 else h).toString(), "$h horas de la mañana", selected?.hour == h, enabled, Modifier.weight(1f)) {
                    onValueChange(TimeOfDay(h, selected?.minute ?: 0).text)
                }
            }
            Text("Tarde y noche, p. m.", style = BpmPlayType.label, color = BpmPlayColors.ink)
            Grid(12) { i ->
                val h = 12 + i
                TimeCell((if (h == 12) 12 else h - 12).toString(), "$h horas", selected?.hour == h, enabled, Modifier.weight(1f)) {
                    onValueChange(TimeOfDay(h, selected?.minute ?: 0).text)
                }
            }
            Text("Minutos", style = BpmPlayType.label, color = BpmPlayColors.muted)
            Grid(12) { i ->
                val m = BpmTime.minuteSteps[i]
                TimeCell(m.toString().padStart(2, '0'), "$m minutos", selected?.minute == m, enabled, Modifier.weight(1f)) {
                    onValueChange(TimeOfDay(selected?.hour ?: BpmTime.now().hour, m).text); open = false
                }
            }
            BpmChunkyButton("Listo", { open = false }, Modifier.fillMaxWidth(), enabled = enabled)
            if (optional && selected != null) BpmChunkyButton("Quitar hora", { onValueChange(""); open = false }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = enabled)
        }
        (error ?: supportingText)?.let { Text(it, style = BpmPlayType.rowDetail, color = if (error != null) BpmPlayColors.error.shadow else BpmPlayColors.muted) }
    }
}

/** Cuadrícula de 6 columnas con [count] celdas; el espacio entre celdas es de 4 dp. */
@Composable
private fun Grid(count: Int, cell: @Composable androidx.compose.foundation.layout.RowScope.(Int) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count / 6) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { repeat(6) { col -> cell(row * 6 + col) } }
        }
    }
}

@Composable
private fun TimeCell(text: String, spoken: String, isSelected: Boolean, enabled: Boolean, modifier: Modifier, onPick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(if (isSelected) BpmPlayColors.action.face else Color.Transparent)
            .border(1.dp, if (isSelected) BpmPlayColors.action.face else BpmPlayColors.line, shape)
            .semantics(mergeDescendants = true) { contentDescription = spoken; this.selected = isSelected; stateDescription = if (isSelected) "Elegido" else "Disponible" }
            .clickable(enabled = enabled, role = Role.Button, onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = if (isSelected) BpmPlayType.pill else BpmPlayType.text, color = if (isSelected) Color.White else BpmPlayColors.ink, textAlign = TextAlign.Center)
    }
}
