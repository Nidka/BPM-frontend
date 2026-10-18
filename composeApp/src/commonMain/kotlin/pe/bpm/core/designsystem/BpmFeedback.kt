package pe.bpm.core.designsystem

import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** Capas suaves por control; conserva la respuesta Material sin imponer un color global a todos los fondos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmFeedback(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(color = color,
        rippleAlpha = RippleAlpha(draggedAlpha = .08f, focusedAlpha = .12f, hoveredAlpha = .04f, pressedAlpha = .08f)),
        content = content)
}
