package pe.bpm.core.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Filtro o selección de una opción, con el lenguaje de los botones gruesos: borde de 2 dp con 4 dp abajo, radio 14 y
 * objetivo táctil de 48 dp. Seleccionado = fondo teal suave, borde y texto primarios y marca de verificación; sin
 * seleccionar = blanco con borde gris. El foco por teclado engrosa el borde (WCAG 2.4.7). Toda pantalla usa este
 * componente en lugar de `FilterChip` de Material.
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
    val shape = RoundedCornerShape(14.dp)
    val border = if (selected && enabled) BpmPlayColors.selectedBorder else BpmPlayColors.line
    val fill = if (selected) BpmPlayColors.action.soft else Color.White
    val ink = when { !enabled -> BpmPlayColors.lockText; selected -> scheme.primary; else -> scheme.onSurface }
    BpmFeedback(scheme.primary) {
        // Alto total de 48 dp: 2 de borde arriba, 4 abajo y 42 de cuerpo.
        Row(
            modifier.bpmPlayFrame(border, fill, 14.dp)
                .then(if (focused && enabled) Modifier.border(3.dp, scheme.onSurface, shape) else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .selectable(selected = selected, enabled = enabled, interactionSource = interactions, role = Role.RadioButton, onClick = onClick)
                .defaultMinSize(minHeight = 42.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides ink) {
                ProvideTextStyle(BpmPlayType.key.copy(fontSize = 14.sp)) {
                    if (selected) BpmIcon(BpmSymbol.Check, tint = ink, modifier = Modifier.size(18.dp))
                    label()
                }
            }
        }
    }
}
