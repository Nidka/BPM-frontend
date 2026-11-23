package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clase de ancho de ventana (Material 3 adaptive). Se calcula **una sola vez** en `BpmAppShell` y baja por
 * [LocalBpmWindowClass]; ningún otro código mide la ventana. Los cortes son 600, 840 y 1200 dp.
 */
enum class BpmWindowClass(val margin: Dp) {
    /** Menos de 600 dp: barra inferior, una columna. */
    Compact(16.dp),
    /** 600–839 dp: riel lateral de 80 dp. */
    Medium(24.dp),
    /** 840–1199 dp: menú lateral de 240 dp. */
    Expanded(32.dp),
    /** 1200 dp o más: menú lateral y contenido centrado con tope de [BpmSpacing.contentMax]. */
    Large(32.dp);

    /** La navegación ocupa un lado de la pantalla (riel o menú); en compacta va abajo. */
    val sideNavigation: Boolean get() = this != Compact

    companion object {
        /** Corte inferior de cada clase, en dp. */
        const val MEDIUM_FROM = 600
        const val EXPANDED_FROM = 840
        const val LARGE_FROM = 1200

        fun fromWidth(width: Dp): BpmWindowClass = when {
            width >= LARGE_FROM.dp -> Large
            width >= EXPANDED_FROM.dp -> Expanded
            width >= MEDIUM_FROM.dp -> Medium
            else -> Compact
        }
    }
}

/** Forma de la navegación principal según la clase de ancho (y el alto disponible). */
enum class BpmNavigationMode(val width: Dp) {
    /** Barra inferior con un máximo de cinco destinos (hasta 839 dp, para todos los roles). */
    BottomBar(0.dp),
    /** Menú lateral de 230 dp ([BpmPlaySideMenu]) desde 840 dp, para todos los roles. */
    SideMenu(230.dp);

    companion object {
        /** Alto mínimo para una navegación lateral; por debajo (móvil horizontal) se usa la barra inferior. */
        val MIN_SIDE_HEIGHT: Dp = 480.dp

        fun of(windowClass: BpmWindowClass, height: Dp): BpmNavigationMode = when {
            height < MIN_SIDE_HEIGHT -> BottomBar
            windowClass == BpmWindowClass.Compact -> BottomBar
            windowClass == BpmWindowClass.Medium -> BottomBar
            else -> SideMenu
        }
    }
}

/** Clase de ancho vigente. Sin shell (pruebas, vistas previas) se asume compacta. */
val LocalBpmWindowClass = compositionLocalOf { BpmWindowClass.Compact }

/** Alto de la ventana vigente; sin shell se asume una ventana alta. */
val LocalBpmWindowHeight = compositionLocalOf { 800.dp }

/** Atajo de lectura para los patrones y componentes. */
val currentWindowClass: BpmWindowClass
    @Composable @ReadOnlyComposable get() = LocalBpmWindowClass.current

/** Alto de la ventana: las pantallas lo usan para decidir si hay poco espacio vertical (móvil horizontal). */
val currentWindowHeight: Dp
    @Composable @ReadOnlyComposable get() = LocalBpmWindowHeight.current

/** Publica [windowClass] (y el alto) para todo el contenido; solo lo llaman el shell y las páginas a pantalla completa. */
@Composable
fun ProvideBpmWindowClass(windowClass: BpmWindowClass, height: Dp = LocalBpmWindowHeight.current, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBpmWindowClass provides windowClass, LocalBpmWindowHeight provides height, content = content)
}

/**
 * Mide la ventana una sola vez y publica su clase y su alto. Es el único punto, junto al shell, que mide
 * el ancho de la ventana; las pantallas a página completa (ingreso) lo usan en lugar de `BoxWithConstraints`.
 */
@Composable
fun BpmWindowBox(modifier: Modifier = Modifier, content: @Composable BoxScope.(BpmWindowClass, Dp) -> Unit) {
    BoxWithConstraints(modifier) {
        val windowClass = BpmWindowClass.fromWidth(maxWidth)
        ProvideBpmWindowClass(windowClass, maxHeight) { content(windowClass, maxHeight) }
    }
}
