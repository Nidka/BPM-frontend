package pe.bpm.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Tokens de la biblioteca `bpm-design-system` (styles.css y playful.css).
 * Los valores de relieve equivalen a `color-mix` sobre el color primario.
 */
object BpmTokens {
    val primary = Color(0xFF005B59)
    val text = Color(0xFF233F4D)
    val muted = Color(0xFF58676D)
    val background = Color(0xFFF5F6F6)
    val soft = Color(0xFFEDF5F2)
    val accent = Color(0xFFED5700)
    val border = Color(0xFFD9DFE0)
    /** Borde de campos: contraste suficiente para componentes de interfaz (WCAG 1.4.11). */
    val fieldBorder = Color(0xFF859297)
    /** Sombra inferior del botón principal (primario oscurecido 28 %). */
    val primaryRelief = Color(0xFF003D3C)
    /** Sombra inferior del botón secundario (primario mezclado con 60 % de blanco). */
    val secondaryRelief = Color(0xFF99BDBC)
    val successText = Color(0xFF216242)
    val successSurface = Color(0xFFE7F4EC)
    val warningText = Color(0xFF765400)
    val warningSurface = Color(0xFFFFF2D1)
    val dangerText = Color(0xFFA42A24)
    val dangerSurface = Color(0xFFFFF0EE)
}

/**
 * Espaciado de la biblioteca `bpm-design-system` (prototype.css: cuerpo 28/22/20, rejilla 16–18,
 * tarjeta 18–24, objetivo táctil 48). Toda pantalla toma de aquí sus márgenes para que
 * cabecera, contenido y tarjetas compartan los mismos bordes. Ver `.claude/skills/bpm-spacing`.
 */
object BpmSpacing {
    /** Margen lateral común de cabecera y contenido: 32 dp en vista amplia y 20 dp en compacta. */
    fun gutter(wide: Boolean): Dp = if (wide) 32.dp else 20.dp
    /** Separación vertical entre secciones de una pantalla. */
    val section: Dp = 20.dp
    /** Separación entre tarjetas de una rejilla. */
    val grid: Dp = 16.dp
    /** Relleno interno de tarjeta. */
    val card: Dp = 24.dp
    /** Alto mínimo de cualquier control interactivo. */
    val touch: Dp = 48.dp
}

private val colors = lightColorScheme(
    primary = BpmTokens.primary,
    onPrimary = Color.White,
    // El acento naranja se reserva para detalles; no se usa como texto pequeño sobre blanco.
    secondary = BpmTokens.accent,
    primaryContainer = BpmTokens.soft,
    onPrimaryContainer = BpmTokens.text,
    secondaryContainer = Color(0xFFFFE8D9),
    onSecondaryContainer = Color(0xFF5C2A0D),
    background = BpmTokens.background,
    surface = Color.White,
    onBackground = BpmTokens.text,
    onSurface = BpmTokens.text,
    onSurfaceVariant = BpmTokens.muted,
    surfaceContainer = BpmTokens.soft,
    outline = BpmTokens.fieldBorder,
    outlineVariant = BpmTokens.border,
    error = BpmTokens.dangerText,
    errorContainer = BpmTokens.dangerSurface,
    onErrorContainer = BpmTokens.dangerText,
)

/** Los títulos usan peso 800 (`h1,h2,h3` en playful.css); la familia sigue la del sistema de cada plataforma. */
private fun bpmTypography(): Typography {
    val base = Typography()
    fun androidx.compose.ui.text.TextStyle.heavy() = copy(fontWeight = FontWeight.ExtraBold)
    return base.copy(
        headlineLarge = base.headlineLarge.heavy(), headlineMedium = base.headlineMedium.heavy(),
        headlineSmall = base.headlineSmall.heavy(), titleLarge = base.titleLarge.heavy(),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
fun BpmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = bpmTypography(), shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}
