package pe.bpm.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val colors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    secondary = Color(0xFF5856D6),
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF636366),
    surfaceContainer = Color(0xFFF2F2F7),
    outline = Color(0xFFD1D1D6),
    outlineVariant = Color(0xFFE5E5EA),
)

@Composable
fun BpmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(28.dp)), content = content)
}
