package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Componentes de estructura del sistema BPM. Se reutilizan en todas las pantallas para que cabecera,
 * KPI, accesos, filas y avisos midan y se coloquen igual. Ver docs/superpowers/plans/2026-11-18-loop-revision-diseno.md.
 */

/** Ancho desde el cual `BpmPageHeader` pone las acciones a la derecha del título. */
private val headerWide: Dp = 600.dp
/** Ancho desde el cual `BpmListRow` pasa de tarjeta a fila densa. */
private val listRowWide: Dp = 840.dp

/**
 * Encabezado de página: título (uno por pantalla) + descripción corta + acciones. Las acciones van siempre
 * en el mismo lugar: a la derecha del título desde 600 dp y debajo en pantallas compactas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= headerWide
        val titleBlock = @Composable { mod: Modifier ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                if (description != null)
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg),
                verticalAlignment = Alignment.CenterVertically) {
                titleBlock(Modifier.weight(1f))
                if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md),
                    verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
                titleBlock(Modifier.fillMaxWidth())
                if (actions != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(BpmSpacing.md), content = { actions() })
            }
        }
    }
}

/**
 * KPI con una cifra, una etiqueta y un apoyo opcional. Dos tamaños: normal (tarjeta compacta) y
 * destacado (tarjeta destacada con cifra mayor; pensado para ocupar 2 columnas de una rejilla bento).
 */
@Composable
fun BpmStatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    support: String? = null,
    featured: Boolean = false,
) {
    BpmCard(modifier, variant = if (featured) BpmCardVariant.Featured else BpmCardVariant.Compact) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (featured) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary)
        if (support != null)
            Text(support, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Acceso a un módulo: icono, título, una línea y flecha. Toda la fila es el botón; no lleva botón interior.
 */
@Composable
fun BpmActionTile(
    symbol: BpmSymbol,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BpmCard(modifier, variant = BpmCardVariant.Compact) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = BpmSpacing.touch).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                BpmIcon(symbol, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // La flecha «atrás» girada 180° indica avance sin añadir otro recurso gráfico.
            BpmIcon(BpmSymbol.Back, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).rotate(180f))
        }
    }
}

/**
 * Fila de una lista ancha: iniciales, nombre, detalle (correo), chip (rol) y acciones. Desde 840 dp es una
 * fila densa de una sola línea; en pantallas menores se muestra como tarjeta compacta.
 */
@Composable
fun BpmListRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    chip: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val avatar = @Composable {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Text(initialsOf(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        val names = @Composable { mod: Modifier ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val roleChip = @Composable {
            if (chip != null) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(chip, Modifier.padding(horizontal = BpmSpacing.md, vertical = BpmSpacing.xs),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (maxWidth >= listRowWide) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = BpmSpacing.lg, vertical = BpmSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    avatar()
                    names(Modifier.weight(1f))
                    roleChip()
                    if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
        } else {
            BpmCard(Modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
                Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    avatar()
                    names(Modifier.weight(1f))
                }
                roleChip()
                if (actions != null) Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/** Una o dos iniciales para el avatar de una fila. */
internal fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/**
 * Aviso de una sola línea con icono y acción «Entendido»; reemplaza a las tarjetas de aviso. Si [onDismiss]
 * es nulo no muestra la acción. Con [warning] usa los colores de advertencia.
 */
@Composable
fun BpmInlineNotice(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "Entendido",
    warning: Boolean = false,
) {
    val fill = if (warning) BpmTokens.warningSurface else MaterialTheme.colorScheme.primaryContainer
    val ink = if (warning) BpmTokens.warningText else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = fill) {
        Row(Modifier.padding(start = BpmSpacing.lg, top = BpmSpacing.xs, bottom = BpmSpacing.xs,
            end = if (onDismiss == null) BpmSpacing.lg else BpmSpacing.sm).heightIn(min = BpmSpacing.touch),
            horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            BpmIcon(BpmSymbol.Alert, tint = ink, modifier = Modifier.size(20.dp))
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = ink)
            if (onDismiss != null) BpmTextButton(onDismiss) { Text(dismissLabel, color = ink) }
        }
    }
}
