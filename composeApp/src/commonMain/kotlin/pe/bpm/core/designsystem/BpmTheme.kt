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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val border = Color(0xFFDCE3E3)
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
    /** Margen lateral por clase de ancho: 16 · 24 · 32 · 32 (ver [BpmWindowClass]). */
    fun gutter(windowClass: BpmWindowClass): Dp = windowClass.margin
    /** Separación vertical entre secciones de una pantalla. */
    val section: Dp = 20.dp
    /** Separación entre tarjetas de una rejilla. */
    val grid: Dp = 16.dp
    /** Relleno interno de tarjeta. */
    val card: Dp = 24.dp
    /** Alto mínimo de cualquier control interactivo. */
    val touch: Dp = 48.dp
    /** Alto mínimo del cuerpo de un botón (principal y secundario comparten el mismo tamaño). */
    val button: Dp = 52.dp
    /** Relieve inferior de los botones; igual en principal y secundario para que midan lo mismo. */
    val reliefDepth: Dp = 3.dp
    /** Ancho máximo único del contenido de toda pantalla alojada; en ventanas mayores queda alineado a la izquierda. */
    val contentMax: Dp = 1120.dp
    /** Ancho máximo de un campo de búsqueda: en pantallas amplias no debe ocupar toda la fila. */
    val searchMax: Dp = 560.dp

    // Escala única de separaciones: 4 · 8 · 12 · 16 · 24 · 32. Se retiran 2, 6, 10, 14 y 20.
    /** Entre líneas de un mismo dato. */
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    /** Dentro de una tarjeta. */
    val md: Dp = 12.dp
    /** Entre tarjetas. */
    val lg: Dp = 16.dp
    /** Entre secciones. */
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    /** Separaciones grandes de pantalla (columnas de patrón, zonas vacías). */
    val xxxl: Dp = 48.dp
    val huge: Dp = 64.dp

    /** Cuerpo del botón en talla compacta; el área táctil se mantiene en [touch]. */
    val buttonCompact: Dp = 44.dp
    /** Ancho mínimo de un botón a ancho de contenido. */
    val buttonMinWidth: Dp = 160.dp
    /** Relleno de una tarjeta compacta (listas y KPI). */
    val cardCompact: Dp = 16.dp
}

/** Talla de botón: *Normal* (52 dp) y *Compacta* (44 dp, solo en filas y tarjetas densas). */
enum class BpmButtonSize { Normal, Compact }

/** Variante de tarjeta: *Estándar* (relleno 24), *Compacta* (relleno 16) y *Destacada* (fondo teal suave, una por pantalla). */
enum class BpmCardVariant { Standard, Compact, Featured }

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

/** Esquema de alto contraste (Accesibilidad): texto de apoyo y bordes más oscuros, sin cambiar la paleta. */
private val highContrastColors = colors.copy(
    onSurfaceVariant = Color(0xFF2F3E44),
    outline = Color(0xFF3F4D52),
    outlineVariant = Color(0xFF7D8B90),
    onBackground = Color(0xFF14262F),
    onSurface = Color(0xFF14262F),
)

/**
 * Tipografía única de la app: **Baloo 2** (títulos, 800) y **Nunito** (todo lo demás), las mismas familias del estilo de
 * juego de Personal ([BpmPlayType]); así el ingreso y todos los roles se ven igual. Los tamaños conservan la escala de
 * Material (con el mínimo de 14 sp del RNF de usabilidad).
 *
 * Roles de texto (el peso viene del estilo; las pantallas no escriben `fontWeight`):
 * título de página `headlineMedium` (uno por pantalla) · sección `titleLarge` · título de tarjeta `titleMedium` ·
 * cuerpo `bodyMedium` · apoyo `bodySmall` (nunca como único texto de una tarjeta) · etiqueta `labelMedium` ·
 * texto de botón `labelLarge` (negrita).
 */
@Composable
private fun bpmTypography(): Typography {
    val base = Typography()
    val display = BpmPlayType.display
    val body = BpmPlayType.body
    fun TextStyle.baloo() = copy(fontFamily = display, fontWeight = FontWeight.ExtraBold)
    fun TextStyle.nunito(weight: FontWeight) = copy(fontFamily = body, fontWeight = weight)
    return base.copy(
        displayLarge = base.displayLarge.baloo(), displayMedium = base.displayMedium.baloo(), displaySmall = base.displaySmall.baloo(),
        headlineLarge = base.headlineLarge.baloo(),
        // Título de página: Baloo 2 800, 26 sp (`.h1` de la maqueta) con interlineado holgado para títulos de dos líneas.
        headlineMedium = base.headlineMedium.baloo().copy(fontSize = 26.sp, lineHeight = 32.sp),
        headlineSmall = base.headlineSmall.baloo(),
        titleLarge = base.titleLarge.baloo(),
        titleMedium = base.titleMedium.nunito(FontWeight.ExtraBold).copy(lineHeight = 22.sp),
        titleSmall = base.titleSmall.nunito(FontWeight.ExtraBold),
        bodyLarge = base.bodyLarge.nunito(FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.nunito(FontWeight.SemiBold),
        // RNF de usabilidad: ningún texto por debajo de 14 sp (apoyo, etiquetas y chips incluidos).
        bodySmall = base.bodySmall.nunito(FontWeight.SemiBold).copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.nunito(FontWeight.ExtraBold).copy(fontSize = 14.sp, lineHeight = 18.sp),
        labelSmall = base.labelSmall.nunito(FontWeight.ExtraBold).copy(fontSize = 14.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.nunito(FontWeight.Black).copy(fontSize = 15.sp, lineHeight = 20.sp),
    )
}

@Composable
fun BpmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (BpmContrast.high) highContrastColors else colors, typography = bpmTypography(), shapes = Shapes(
        small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp))) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

/**
 * Énfasis de texto del tema: el peso viene de aquí y no se escribe en las pantallas (regla R8).
 * [bodyStrong] reemplaza al `Text` por defecto (16 sp) en negrita; el resto, a los estilos de apoyo en negrita.
 */
object BpmType {
    val bodyStrong @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
    val bodyMediumStrong @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
    val bodySmallStrong @Composable get() = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
    val labelStrong @Composable get() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
}
