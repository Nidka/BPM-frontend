package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Filtro o selección de una opción, con el mismo lenguaje que los botones del sistema: borde de 2 dp,
 * radio del tema y objetivo táctil de 48 dp. Seleccionado = fondo teal suave, borde y texto primarios y
 * marca de verificación; sin seleccionar = superficie blanca con borde gris. El foco por teclado
 * engrosa el borde (WCAG 2.4.7). Toda pantalla usa este componente en lugar de `FilterChip` de Material.
 */
@Composable
fun BpmFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val border = when {
        focused && enabled -> BorderStroke(3.dp, scheme.onSurface)
        selected && enabled -> BorderStroke(2.dp, scheme.primary)
        else -> BorderStroke(2.dp, scheme.outlineVariant)
    }
    BpmFeedback(scheme.primary) {
        FilterChip(
            selected = selected, onClick = onClick, label = label, enabled = enabled,
            modifier = modifier.heightIn(min = BpmSpacing.touch),
            interactionSource = interactions,
            shape = MaterialTheme.shapes.small,
            leadingIcon = if (selected) ({ BpmIcon(BpmSymbol.Check, tint = scheme.primary, modifier = Modifier.size(18.dp)) }) else null,
            colors = FilterChipDefaults.filterChipColors(
                containerColor = scheme.surface, labelColor = scheme.onSurface,
                selectedContainerColor = scheme.primaryContainer, selectedLabelColor = scheme.primary,
                disabledContainerColor = scheme.surface, disabledLabelColor = scheme.onSurface.copy(alpha = 0.38f),
                disabledSelectedContainerColor = scheme.primaryContainer,
            ),
            border = border,
        )
    }
}
