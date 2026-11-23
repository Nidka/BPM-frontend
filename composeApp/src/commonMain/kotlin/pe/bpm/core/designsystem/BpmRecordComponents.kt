package pe.bpm.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Componentes de listado, formulario y detalle (specs/LAYOUT.md §3–5). No conocen el dominio: reciben textos,
 * estados y lambdas, y ponen ellos la medida para que todas las pantallas se vean igual en Android, iOS y web.
 */

// ── Listado ──────────────────────────────────────────────────────────────────────────────────────────────

/** Ancho útil desde el que filtros y búsqueda comparten fila. */
private val toolbarRowMin = 600.dp

/**
 * Barra de un listado: [filters] (chips o pestañas) a la izquierda y [search] a la derecha cuando caben en una
 * fila (≥ 600 dp útiles); en compacta, filtros arriba y búsqueda debajo a todo el ancho.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmListToolbar(
    modifier: Modifier = Modifier,
    filters: (@Composable () -> Unit)? = null,
    search: (@Composable (Modifier) -> Unit)? = null,
) {
    if (filters == null && search == null) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= toolbarRowMin && filters != null && search != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) { filters() }
                search(Modifier.weight(1f))
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
            filters?.invoke()
            search?.invoke(Modifier.fillMaxWidth())
        }
    }
}

/** Chips de filtro de una sola elección en una fila que se parte en varias líneas si no caben. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> BpmFilterChips(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
        options.forEach { (value, label) -> BpmFilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled, label = { Text(label) }) }
    }
}

/**
 * Resultados paginados: un único bloque para cargando, error saneado con «Reintentar», vacío ([empty]) y
 * contenido, con la paginación «Anterior · Página N · Siguiente» al pie (sin inventar totales).
 */
@Composable
fun BpmPagedList(
    loading: Boolean,
    failure: String?,
    isEmpty: Boolean,
    pageNumber: Int,
    canPrevious: Boolean,
    canNext: Boolean,
    onRetry: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    loadingLabel: String = "Cargando…",
    empty: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
        when {
            loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(loadingLabel) }
            failure != null -> {
                Text(failure, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmChunkyButton("Reintentar", onRetry, tone = BpmChunkyTone.White)
            }
            isEmpty -> empty()
            else -> content()
        }
        PageNavigation(pageNumber, canPrevious, canNext, loading || failure != null, onPrevious, onNext)
    }
}

/** Estado de un registro: texto (siempre visible) y tono. */
data class BpmRecordStatus(val text: String, val tone: BpmTone = BpmTone.Info)

/**
 * Ítem de un listado (specs/LAYOUT.md §3.4): título de hasta 2 líneas con su [status] a la derecha, [subtitle],
 * hasta tres [details] de apoyo y, al pie, una acción de botón ([actionLabel]) más hasta dos de texto
 * ([textActions]). Con [onOpen] toda la tarjeta abre el detalle.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmRecordCard(
    title: String,
    modifier: Modifier = Modifier,
    status: BpmRecordStatus? = null,
    subtitle: String? = null,
    details: List<String> = emptyList(),
    onOpen: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    textActions: List<Pair<String, () -> Unit>> = emptyList(),
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    BpmCard(modifier, variant = BpmCardVariant.Compact, onClick = onOpen, onClickLabel = actionLabel ?: "Ver detalle", enabled = enabled, selected = selected) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.Top) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (status != null) BpmStatusChip(status.text, status.tone)
        }
        if (subtitle != null || details.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            details.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (actionLabel != null || textActions.isNotEmpty()) {
            if (actionLabel != null) BpmChunkyButton(actionLabel, onAction ?: onOpen ?: {}, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = enabled)
            // Apiladas: una fila que se parte (FlowRow) no informa bien su alto a la rejilla y la segunda acción quedaba recortada.
            textActions.take(2).forEach { (label, action) ->
                BpmTextButton(onClick = action, enabled = enabled, modifier = Modifier.heightIn(min = BpmSpacing.touch)) { Text(label) }
            }
        }
    }
}

// ── Formulario ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * Grupo de un formulario o de un detalle: tarjeta con título corto, descripción opcional y su contenido
 * (12 dp entre campos). Un formulario la repite por cada bloque de campos relacionados.
 */
@Composable
fun BpmSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BpmCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        content()
    }
}

/**
 * Acciones de un formulario o de una confirmación: [onCancel] («Cancelar») y la acción principal ([primaryLabel]).
 * Compacta: se reparten la fila; desde la clase media van a la derecha, a contenido (mínimo 160 dp). Mientras
 * [busy] la principal dice [busyLabel] y ambas se deshabilitan.
 */
@Composable
fun BpmFormActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "Guardando…",
    onCancel: (() -> Unit)? = null,
    cancelLabel: String = "Cancelar",
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BpmInlineActions(
            secondary = onCancel?.let { cancel -> { BpmChunkyButton(cancelLabel, cancel, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, enabled = !busy) } },
        ) { BpmChunkyButton(if (busy) busyLabel else primaryLabel, onPrimary, Modifier.fillMaxWidth(), enabled = enabled && !busy) }
    }
}

/**
 * Confirmación en línea (sin ventana emergente) para acciones con efectos que conviene entender: título,
 * explicación ([content]) y las mismas acciones de un formulario.
 */
@Composable
fun BpmConfirmPanel(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    confirmEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    BpmCard(modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
        BpmFormActions(confirmLabel, onConfirm, enabled = confirmEnabled, busy = busy, onCancel = onCancel)
    }
}

// ── Detalle ──────────────────────────────────────────────────────────────────────────────────────────────

/** Un dato del detalle; un valor vacío se muestra como «—». */
data class BpmDetailField(val label: String, val value: String?)

/**
 * Datos de un detalle: etiqueta gris encima del valor; una columna si el panel mide menos de 520 dp y dos
 * desde 520 (specs/LAYOUT.md §5.2).
 */
@Composable
fun BpmDetailFields(fields: List<BpmDetailField>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = BpmPatternRules.detailFieldColumns(maxWidth)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
            fields.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
                    row.forEach { field ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                            Text(field.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(field.value?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Detalle de un registro (specs/LAYOUT.md §5): «Volver a la lista» solo cuando ocupa la pantalla (no junto a
 * la lista), título con [status], [subtitle], [actions] del registro (un solo botón principal; el resto secundarios)
 * y sus secciones ([content]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmDetailPane(
    title: String,
    modifier: Modifier = Modifier,
    status: BpmRecordStatus? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Volver a la lista",
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
        if (onBack != null && !LocalBpmDetailBeside.current)
            BpmTextButton(onClick = onBack, modifier = Modifier.heightIn(min = BpmSpacing.touch)) {
                BpmIcon(BpmSymbol.Back, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(BpmSpacing.sm))
                Text(backLabel)
            }
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (status != null) BpmStatusChip(status.text, status.tone)
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // Las acciones del registro saltan de línea si no caben (360 dp): nunca se cortan ni desbordan.
        if (actions != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) { actions() }
        content()
    }
}
