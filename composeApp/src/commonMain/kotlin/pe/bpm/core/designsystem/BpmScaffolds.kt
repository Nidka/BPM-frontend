package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/*
 * Una sola anatomía para las pantallas de DETALLE, AJUSTES y CONFIGURACIÓN de todos los roles:
 *
 *  - BpmDetailScaffold: «Volver» · antetítulo · título · ficha(s) de estado · datos clave · secciones · acción principal fija.
 *  - BpmSettingsScaffold: título + frase en lenguaje simple · secciones agrupadas (BpmSettingsSection) con filas de ajuste
 *    (interruptor, elección o enlace) que cuentan su estado en palabras, no solo con color.
 *  - BpmFormScaffold: lo mismo que el detalle pero para formularios de configuración (descripción y acciones al pie).
 *
 * Todas miden hasta 720 dp (BpmPatternRules.readingWidth), se alinean a la izquierda bajo el título del shell y en
 * clase compacta fijan la acción principal al pie (BpmActionBar). Dentro de un panel de lista–detalle se adaptan al panel.
 */

/**
 * Líneas máximas de un título de fila: con el texto grande (×1,3 o más, del sistema o de «Ajustes») se duplican para que
 * un nombre largo no se corte con «…» justo a quien más necesita leerlo completo.
 */
@Composable
fun bpmTitleLines(base: Int = 2): Int = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f) base * 2 else base

/** Ficha de estado de un detalle («ABIERTA», «VENCIDA»…): siempre texto, nunca solo color. */
data class BpmDetailChip(val text: String, val tone: BpmPlayTone)

/** Dato clave de un detalle: rótulo pequeño y valor en negrita («Sede» · «Cocina Andina»). */
data class BpmDetailFact(val label: String, val value: String)

/**
 * Plantilla de pantalla de detalle. [onBack] nulo oculta «Volver» (cuando el panel ya tiene su propio retroceso).
 * [actions] son las acciones del registro (un botón principal y los demás secundarios) en línea bajo el encabezado;
 * [primaryAction] es una sola acción que va fija al pie en compacta y en línea al final en el resto.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmDetailScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Volver",
    eyebrow: String? = null,
    subtitle: String? = null,
    chips: List<BpmDetailChip> = emptyList(),
    facts: List<BpmDetailFact> = emptyList(),
    actions: (@Composable () -> Unit)? = null,
    primaryAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        onBack?.let { BpmBackLink(label = backLabel, onClick = it) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            eyebrow?.let { Text(it.uppercase(), style = BpmPlayType.kicker, color = BpmPlayColors.action.face) }
            Text(title, style = BpmPlayType.h1, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.muted) }
        }
        if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { BpmPlayChip(it.text, it.tone) }
        }
        if (facts.isNotEmpty()) BpmPlayCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                facts.forEach { fact ->
                    Column(Modifier.widthIn(min = 120.dp).semantics(mergeDescendants = true) { contentDescription = "${fact.label}: ${fact.value}" }) {
                        Text(fact.label.uppercase(), style = BpmPlayType.statLabel, color = BpmPlayColors.muted)
                        Text(fact.value, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
                    }
                }
            }
        }
        // Acciones del registro: pasan a la línea siguiente si no caben (360 dp), nunca se cortan ni desbordan.
        actions?.let { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { it() } }
        content()
        primaryAction?.let { action -> BpmActionBar { Column(Modifier.fillMaxWidth()) { action() } } }
    }
}

/** Plantilla de formulario de configuración: «Volver», título, descripción en lenguaje simple, secciones y acciones al pie. */
@Composable
fun BpmFormScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    description: String? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = BpmDetailScaffold(title, modifier, onBack, subtitle = description, primaryAction = actions, content = content)

/** Plantilla de ajustes: título, frase simple y secciones ([BpmSettingsSection]) con filas de ajuste. */
@Composable
fun BpmSettingsScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) = BpmDetailScaffold(title, modifier, onBack, subtitle = subtitle, content = content)

/** Grupo de ajustes con título y una frase que dice para qué sirve. */
@Composable
fun BpmSettingsSection(title: String, modifier: Modifier = Modifier, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    BpmPlayCard(modifier) {
        Text(title, style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        description?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.muted) }
        content()
    }
}

/** Interruptor: toda la fila conmuta, mínimo 56 dp, y el estado se dice en palabras («Activado»/«Desactivado») al lector de pantalla. */
@Composable
fun BpmSettingToggle(label: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clip(RoundedCornerShape(12.dp))
            .semantics { stateDescription = if (checked) "Activado" else "Desactivado" }
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
            detail?.let { Text(it, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted) }
        }
        Text(if (checked) "SÍ" else "NO", style = BpmPlayType.label, color = if (checked) BpmPlayColors.action.face else BpmPlayColors.muted)
        Switch(checked, null, colors = SwitchDefaults.colors(checkedTrackColor = BpmPlayColors.action.face, checkedThumbColor = Color.White,
            uncheckedTrackColor = BpmPlayColors.lock, uncheckedThumbColor = BpmPlayColors.muted, uncheckedBorderColor = BpmPlayColors.muted))
    }
}

/** Elección entre pocas opciones (tamaño del texto, idioma): fichas en fila que pasan a la línea siguiente si no caben. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> BpmSettingChoice(label: String, detail: String?, options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
        detail?.let { Text(it, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) -> BpmFilterChip(value == selected, { onSelect(value) }, label = { Text(text) }) }
        }
    }
}

/** Fila que lleva a otra pantalla (dispositivos, cerrar sesión): icono, nombre, detalle y flecha; toda la fila es el botón (48 dp). */
@Composable
fun BpmSettingLink(label: String, detail: String?, onClick: () -> Unit, modifier: Modifier = Modifier, symbol: BpmSymbol? = null) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = BpmSpacing.touch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        symbol?.let { BpmIcon(it, tint = BpmPlayColors.action.face, modifier = Modifier.size(22.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink)
            detail?.let { Text(it, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted) }
        }
        BpmIcon(BpmSymbol.ChevronRight, tint = BpmPlayColors.action.face, modifier = Modifier.size(20.dp))
    }
}
