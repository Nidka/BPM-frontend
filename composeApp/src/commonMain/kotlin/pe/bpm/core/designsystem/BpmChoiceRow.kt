package pe.bpm.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Selección única accesible; etiqueta y radio comparten una sola acción. */
@Composable fun BpmChoiceRow(label: String, selected: Boolean, enabled: Boolean = true, supportingText: String? = null, onSelect: () -> Unit) {
    val container by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, tween(BpmMotion.SHORT), label = "Fondo de opción")
    val outline by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, tween(BpmMotion.SHORT), label = "Borde de opción")
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
            .background(container)
            .border(2.dp, outline, MaterialTheme.shapes.medium)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label)
                supportingText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
