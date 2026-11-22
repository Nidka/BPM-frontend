package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Opción de [BpmSegmentedTabs]: identificador estable, etiqueta y contador opcional. */
data class BpmTabOption(val id: String, val label: String, val count: Int? = null)

/**
 * Pestañas segmentadas: un contenedor gris redondeado con las opciones en fila a partes iguales; la seleccionada
 * va sobre fondo blanco con borde. El contador, si existe, se muestra en una píldora de acento suave.
 * Cada opción mide al menos 48 dp de alto y expone `Role.Tab` con su estado de selección.
 */
@Composable
fun BpmSegmentedTabs(
    options: List<BpmTabOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = RoundedCornerShape(16.dp)
    val tab = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(container).background(BpmNeutralSurface).padding(BpmSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs),
    ) {
        options.forEach { option ->
            val selected = option.id == selectedId
            BpmFeedback(MaterialTheme.colorScheme.primary) {
                Box(
                    Modifier.weight(1f).heightIn(min = BpmSpacing.touch).clip(tab)
                        .background(if (selected) MaterialTheme.colorScheme.surface else BpmNeutralSurface)
                        .then(if (selected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant), tab) else Modifier)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(option.id) })
                        .padding(horizontal = BpmSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(option.label, maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        option.count?.let { count ->
                            Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
                                .padding(horizontal = BpmSpacing.sm, vertical = 2.dp)) {
                                Text(count.toString(), color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = BpmType.labelStrong)
                            }
                        }
                    }
                }
            }
        }
    }
}
