package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Selector de fecha del sistema de diseño: en lugar de escribir «AAAA-MM-DD» a mano, un campo que muestra la fecha en palabras
 * («5 de noviembre de 2026») y, al tocarlo, abre un calendario en línea (sin ventana emergente: funciona igual en Android, iOS y web).
 *
 * - [value] y [onValueChange] usan `AAAA-MM-DD` (vacío = sin fecha). Con [optional] aparece «Quitar fecha».
 * - [min] (`AAAA-MM-DD`) deshabilita los días anteriores. Atajos «Hoy», «Mañana» y «En 1 semana».
 * - Cada día mide al menos 44 dp (en 358 dp de ancho, 7 columnas ≈ 51 dp), lleva su fecha completa como descripción para el
 *   lector de pantalla, dice «Elegido» además de teñirse, y el día de hoy lleva borde y la palabra «hoy» en su descripción.
 * - Mes anterior/siguiente son botones de 48 dp y el título del mes es un encabezado.
 */
@Composable
fun BpmDatePicker(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    optional: Boolean = true,
    min: String? = null,
    error: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    initiallyOpen: Boolean = false,
) {
    val selected = BpmCalendar.parse(value)
    val minDate = min?.let(BpmCalendar::parse)
    val today = remember { BpmCalendar.today() }
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    var shown by rememberSaveable(selected?.iso) { mutableStateOf((selected ?: minDate ?: today).let { it.year to it.month }) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(androidx.compose.ui.graphics.Color.White)
                .border(2.dp, if (error != null) BpmPlayColors.error.face else if (open) BpmPlayColors.action.face else BpmTokens.fieldBorder, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = { open = !open })
                .semantics { stateDescription = if (open) "Calendario abierto" else "Calendario cerrado" }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                Text(selected?.spoken ?: if (optional) "Sin fecha" else "Elige una fecha", style = BpmPlayType.rowTitle,
                    color = if (selected != null) BpmPlayColors.ink else BpmPlayColors.muted)
            }
            BpmIcon(BpmSymbol.Clock, tint = BpmPlayColors.action.face, modifier = Modifier.size(22.dp))
        }
        if (open) Column(
            Modifier.fillMaxWidth().clip(shape).background(androidx.compose.ui.graphics.Color.White).border(2.dp, BpmPlayColors.line, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val (year, month) = shown
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonthArrow(BpmSymbol.Back, "Mes anterior", enabled) { shown = BpmCalendar.previousMonth(year, month) }
                Text("${BpmCalendar.monthNames[month - 1].replaceFirstChar { it.uppercase() }} $year", style = BpmPlayType.h2, color = BpmPlayColors.ink, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { heading() })
                MonthArrow(BpmSymbol.ChevronRight, "Mes siguiente", enabled) { shown = BpmCalendar.nextMonth(year, month) }
            }
            Row(Modifier.fillMaxWidth()) {
                BpmCalendar.weekdayInitials.forEachIndexed { i, initial ->
                    Text(initial, Modifier.weight(1f).semantics { contentDescription = BpmCalendar.weekdayNames[i] }, style = BpmPlayType.label, color = BpmPlayColors.muted, textAlign = TextAlign.Center)
                }
            }
            val blanks = BpmCalendar.leadingBlanks(year, month)
            val days = BpmCalendar.daysInMonth(year, month)
            val cells = blanks + days
            repeat((cells + 6) / 7) { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(7) { col ->
                        val day = week * 7 + col - blanks + 1
                        if (day < 1 || day > days) Box(Modifier.weight(1f).heightIn(min = 44.dp))
                        else DayCell(CivilDate(year, month, day), selected, today, minDate, enabled, Modifier.weight(1f)) { onValueChange(it.iso); open = false }
                    }
                }
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Shortcut("Hoy", today, minDate, enabled) { onValueChange(it.iso); open = false }
                Shortcut("Mañana", today.plusDays(1), minDate, enabled) { onValueChange(it.iso); open = false }
                Shortcut("En 1 semana", today.plusDays(7), minDate, enabled) { onValueChange(it.iso); open = false }
            }
            if (optional && selected != null) BpmChunkyButton("Quitar fecha", { onValueChange(""); open = false }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = enabled)
        }
        (error ?: supportingText)?.let { Text(it, style = BpmPlayType.rowDetail, color = if (error != null) BpmPlayColors.error.shadow else BpmPlayColors.muted) }
    }
}

@Composable
private fun DayCell(date: CivilDate, selected: CivilDate?, today: CivilDate, min: CivilDate?, enabled: Boolean, modifier: Modifier, onPick: (CivilDate) -> Unit) {
    val isSelected = date == selected
    val isToday = date == today
    val allowed = enabled && (min == null || date >= min)
    val shape = RoundedCornerShape(12.dp)
    val spoken = date.spoken + (if (isToday) ", hoy" else "") + (if (!allowed) ", no disponible" else "")
    Box(
        modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (isSelected) BpmPlayColors.action.face else androidx.compose.ui.graphics.Color.Transparent)
            .then(if (isToday && !isSelected) Modifier.border(2.dp, BpmPlayColors.action.face, shape) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = spoken; this.selected = isSelected; stateDescription = if (isSelected) "Elegido" else if (allowed) "Disponible" else "No disponible" }
            .clickable(enabled = allowed, role = Role.Button) { onPick(date) },
        contentAlignment = Alignment.Center,
    ) {
        Text(date.day.toString(), style = if (isSelected || isToday) BpmPlayType.pill else BpmPlayType.text,
            color = when { isSelected -> androidx.compose.ui.graphics.Color.White; !allowed -> BpmPlayColors.lockText; else -> BpmPlayColors.ink })
    }
}

@Composable
private fun Shortcut(text: String, date: CivilDate, min: CivilDate?, enabled: Boolean, onPick: (CivilDate) -> Unit) {
    // Un atajo no «está elegido»: se dibuja como ficha neutra (texto normal, sin cortar palabras) y dice la fecha que pone.
    BpmFilterChip(false, { onPick(date) }, { Text(text, modifier = Modifier.semantics { contentDescription = "$text, ${date.spoken}" }) }, enabled = enabled && (min == null || date >= min))
}

/** Flecha de mes: caja de 48 dp con el icono y su nombre para el lector de pantalla. */
@Composable
private fun MonthArrow(symbol: BpmSymbol, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BpmIcon(symbol, tint = BpmPlayColors.action.face, modifier = Modifier.size(24.dp)) }
}
