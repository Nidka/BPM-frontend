package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** La fila completa comparte etiqueta, estado y área de interacción con la casilla. */
@Composable fun BpmCheckboxRow(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
            .background(if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            // Foco por teclado visible (WCAG 2.4.7): borde más grueso y oscuro, como en los botones.
            .border(if (focused) 3.dp else 2.dp,
                if (focused) MaterialTheme.colorScheme.onSurface else if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                MaterialTheme.shapes.medium)
            .toggleable(value = checked, interactionSource = interactions, indication = LocalIndication.current,
                enabled = enabled, role = Role.Checkbox, onValueChange = onChecked)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
            Text(label, Modifier.weight(1f))
        }
    }
}
