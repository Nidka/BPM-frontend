package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** La fila completa comparte etiqueta, estado y área de interacción con la casilla. */
@Composable fun BpmCheckboxRow(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
            Text(label, Modifier.weight(1f))
        }
    }
}
