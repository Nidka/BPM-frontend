package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.offline.serverActionBlocked

/** Acción secundaria: borde de 2 dp y relieve de 3 dp (`.secondary` en playful.css). */
@Composable
fun BpmSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: BpmButtonSize = BpmButtonSize.Normal, content: @Composable RowScope.() -> Unit) =
    BpmSecondaryButtonImpl(onClick, modifier, enabled && !serverActionBlocked(), size, content)

@Composable
private fun BpmSecondaryButtonImpl(onClick: () -> Unit, modifier: Modifier, enabled: Boolean,
    size: BpmButtonSize, content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    // Con «reducir movimiento» el hundimiento es instantáneo: el cambio de estado se ve, sin desplazamiento animado.
    val reduceMotion = rememberReduceMotion()
    val offset by animateDpAsState(if (pressed && enabled) 2.dp else 0.dp, if (reduceMotion) snap() else tween(100), label = "Presión del botón secundario")
    val shape = RoundedCornerShape(16.dp)
    val line = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    // Foco por teclado visible (WCAG 2.4.7): la capa del ripple (12 %) no basta; el borde se oscurece y engrosa.
    val border = if (focused && enabled) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(2.dp, line)
    // propagateMinConstraints: el botón ocupa todo el ancho solo si quien lo usa se lo pide al contenedor.
    Box(modifier.bpmRelief(if (enabled) BpmTokens.secondaryRelief else Color.Transparent, shape, BpmSpacing.reliefDepth),
        propagateMinConstraints = true) {
        BpmFeedback(MaterialTheme.colorScheme.primary) {
            OutlinedButton(onClick, Modifier.heightIn(min = if (size == BpmButtonSize.Compact) BpmSpacing.buttonCompact else BpmSpacing.button).offset(y = offset), enabled,
                shape = shape, interactionSource = interactions,
                border = border,
                // Mismo relleno y texto en negrita que RaisedButton: ambos botones miden y pesan igual.
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary),
                // El peso (negrita) viene de `labelLarge` en el tema.
                content = content)
        }
    }
}

/** Acción de texto compartida; no compite visualmente con el envío principal. */
@Composable
fun BpmTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        TextButton(onClick, modifier.heightIn(min = 48.dp), enabled,
            shape = RoundedCornerShape(12.dp), contentPadding = contentPadding,
            // Foco por teclado visible (WCAG 2.4.7), igual que en RaisedButton y el selector de organización.
            border = if (focused && enabled) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            interactionSource = interactions,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary), content = content)
    }
}

/**
 * Ancho del botón principal: a ancho completo en la clase compacta (objetivo táctil grande y énfasis) y a contenido,
 * con mínimo de 160 dp, desde la clase media. Las pantallas lo usan en lugar de fijar el ancho a mano (regla R7).
 */
@Composable
fun Modifier.bpmPrimaryButtonWidth(): Modifier =
    // `width(IntrinsicSize.Max)` hace que el botón mida su texto: sin él, el `fillMaxWidth` interno del botón
    // ocupaba toda la fila (en una cabecera aplastaba el título y en una fila de acciones empujaba las demás).
    if (currentWindowClass == BpmWindowClass.Compact) fillMaxWidth() else widthIn(min = BpmSpacing.buttonMinWidth).width(IntrinsicSize.Max)
