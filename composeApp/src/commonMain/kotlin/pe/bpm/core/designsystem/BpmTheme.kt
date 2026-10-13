package pe.bpm.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = lightColorScheme(
    primary = Color(0xFF125D66),
    onPrimary = Color.White,
    secondary = Color(0xFFAD4C25),
    background = Color(0xFFF5F8F7),
    surface = Color.White,
    onSurface = Color(0xFF17363B),
    onSurfaceVariant = Color(0xFF4F6568),
)

@Composable
fun BpmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
