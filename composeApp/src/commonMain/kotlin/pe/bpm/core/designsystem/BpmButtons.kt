package pe.bpm.core.designsystem

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Acción secundaria: borde de 2 dp y relieve de 3 dp (`.secondary` en playful.css). */
@Composable
fun BpmSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val offset by animateDpAsState(if (pressed && enabled) 2.dp else 0.dp, tween(100), label = "Presión del botón secundario")
    val shape = RoundedCornerShape(16.dp)
    val line = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    // propagateMinConstraints: el botón ocupa todo el ancho solo si quien lo usa se lo pide al contenedor.
    Box(modifier.bpmRelief(if (enabled) BpmTokens.secondaryRelief else Color.Transparent, shape, 3.dp),
        propagateMinConstraints = true) {
        BpmFeedback(MaterialTheme.colorScheme.primary) {
            OutlinedButton(onClick, Modifier.heightIn(min = 48.dp).offset(y = offset), enabled,
                shape = shape, interactionSource = interactions,
                border = BorderStroke(2.dp, line),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary), content = content)
        }
    }
}

/** Acción de texto compartida; no compite visualmente con el envío principal. */
@Composable
fun BpmTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit) {
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        TextButton(onClick, modifier.heightIn(min = 48.dp), enabled,
            shape = RoundedCornerShape(12.dp), contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary), content = content)
    }
}
