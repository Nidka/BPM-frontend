package pe.bpm.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Un dato de [BpmSummaryStrip]: [value] destacado («6 días»), [label] atenuado («Racha») y un icono opcional. */
data class BpmSummaryItem(val value: String, val label: String, val symbol: BpmSymbol? = null)

/**
 * Franja de resumen en una sola tarjeta compacta: hasta tres datos en columnas iguales (por ejemplo racha · puntos ·
 * insignias). Sustituye a las tarjetas grandes de racha y logros cuando no hay panel de apoyo (clase compacta y
 * media); con [onClick] toda la franja abre el detalle.
 */
@Composable
fun BpmSummaryStrip(items: List<BpmSummaryItem>, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    BpmCard(modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        if (item.symbol != null) BpmIcon(item.symbol, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(item.value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(item.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onClick != null) BpmIcon(BpmSymbol.ChevronRight, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
