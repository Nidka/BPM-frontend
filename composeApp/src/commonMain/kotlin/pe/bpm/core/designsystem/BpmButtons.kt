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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import pe.bpm.core.offline.serverActionBlocked

/** Texto de los botones del sistema cuyo texto escribe cada pantalla: Nunito 900 de 16 sp, sin espaciado ni mayúsculas. */
@Composable
internal fun bpmButtonTextStyle(): TextStyle = BpmPlayType.button.copy(letterSpacing = 0.sp)

/**
 * Acción secundaria: botón grueso blanco con borde de 2 dp y relieve de 4 dp (`.btn.w` de la maqueta). El texto llega
 * como contenido (se ve como lo escribe la pantalla).
 */
@Composable
fun BpmSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    size: BpmButtonSize = BpmButtonSize.Normal, content: @Composable RowScope.() -> Unit) =
    BpmChunkyShell(onClick, modifier, BpmChunkyTone.White, enabled && !serverActionBlocked(), bpmButtonTextStyle(),
        compact = size == BpmButtonSize.Compact) { content() }

/** Acción de texto compartida (Nunito 900 en el color primario); no compite visualmente con el envío principal. */
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
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
            ProvideTextStyle(BpmPlayType.button.copy(fontSize = 15.sp, letterSpacing = 0.sp)) { content() }
        }
    }
}

/**
 * «‹ Atrás» al inicio de un paso (Nunito 900 en teal, 48 dp de alto): vuelve al paso anterior. Lo usan los flujos por
 * pasos de Personal; la X de la barra superior es la que cierra todo el flujo.
 */
@Composable
fun BpmBackLink(modifier: Modifier = Modifier, label: String = "Atrás", onClick: () -> Unit) {
    BpmTextButton(onClick, modifier, contentPadding = PaddingValues(start = 4.dp, end = 12.dp)) {
        BpmIcon(BpmSymbol.Back, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text(label)
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
