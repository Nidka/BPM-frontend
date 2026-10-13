package pe.bpm.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val colors = lightColorScheme(
    primary = Color(0xFF086C67),
    onPrimary = Color.White,
    secondary = Color(0xFFE86A52),
    primaryContainer = Color(0xFFE1F4EF),
    onPrimaryContainer = Color(0xFF193C3A),
    secondaryContainer = Color(0xFFFFE9DF),
    onSecondaryContainer = Color(0xFF63392F),
    background = Color(0xFFF7F9F6),
    surface = Color.White,
    onBackground = Color(0xFF193C3A),
    onSurface = Color(0xFF193C3A),
    onSurfaceVariant = Color(0xFF59706B),
    surfaceContainer = Color(0xFFEFF4EF),
    outline = Color(0xFF829B92),
    outlineVariant = Color(0xFFDCE6DD),
)

@Composable
fun BpmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(28.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
