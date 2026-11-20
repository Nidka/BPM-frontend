package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Fila-tarjeta de una tarea o elemento de lista: icono en un cuadro de 40 dp con el color del [tone], título en
 * negrita y [detail] atenuado. [trailing] (por ejemplo un [BpmStatusChip]) va a la derecha; si la fila es
 * clicable y no hay [trailing] se muestra una flecha. [selected] dibuja un borde primario de 2 dp.
 */
@Composable
fun BpmTaskRow(
    title: String,
    symbol: BpmSymbol,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: BpmTone = BpmTone.Info,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val (toneBackground, toneContent) = tone.colors()
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        Surface(modifier.fillMaxWidth().semantics { this.selected = selected }, shape = shape,
            color = MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, border)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = BpmSpacing.touch)
                    .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                    .padding(horizontal = BpmSpacing.md, vertical = BpmSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(toneBackground), contentAlignment = Alignment.Center) {
                    BpmIcon(symbol, tint = toneContent, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                when {
                    trailing != null -> trailing()
                    onClick != null -> BpmIcon(BpmSymbol.ChevronRight, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
