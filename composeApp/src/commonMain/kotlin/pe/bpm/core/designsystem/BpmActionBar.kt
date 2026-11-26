package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * Barra de acción fija de los flujos por pasos. En clase compacta la pantalla publica sus botones en una
 * ranura del shell, que los dibuja fijos al pie; desde la clase media la barra va en línea, al final del contenido.
 */

/** Ranura del shell donde una pantalla de pasos coloca su barra de acción. */
@Stable
class BpmActionBarSlot {
    internal var content: (@Composable () -> Unit)? by mutableStateOf(null)
}

/** Ranura vigente; sin shell (pruebas, vistas previas) la barra siempre va en línea. */
val LocalBpmActionBarSlot = compositionLocalOf<BpmActionBarSlot?> { null }

/**
 * Acciones de un flujo por pasos. Fijas al pie de la pantalla en clase compacta (si hay shell que las aloje);
 * en línea en el resto. El contenido se publica una sola vez y se actualiza solo, sin recomponer el shell.
 */
@Composable
fun BpmActionBar(content: @Composable () -> Unit) {
    val slot = LocalBpmActionBarSlot.current
    if (slot == null || currentWindowClass != BpmWindowClass.Compact) { content(); return }
    val current by rememberUpdatedState(content)
    DisposableEffect(slot) {
        slot.content = { current() }
        onDispose { slot.content = null }
    }
}

/** Dibuja la barra publicada en [slot] al pie, con el margen de la clase de ancho. Sin barra no ocupa espacio. */
@Composable
fun BpmActionBarHost(slot: BpmActionBarSlot) {
    val bar = slot.content ?: return
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Box(Modifier.padding(horizontal = BpmSpacing.gutter(currentWindowClass), vertical = BpmSpacing.md)) { bar() }
    }
}

/**
 * Fila de acciones en línea (regla G7). En clase compacta los botones se reparten el ancho de la fila (con
 * [compactSecondaryFixed] el secundario conserva su ancho y el principal ocupa el resto). Desde la clase media
 * cada botón mide a contenido (mínimo 160 dp) y el par va alineado a la derecha del bloque: el secundario a la
 * izquierda y el principal a la derecha, nunca a todo el ancho. [secondary] es opcional.
 */
@Composable
fun BpmInlineActions(
    modifier: Modifier = Modifier,
    compactSecondaryFixed: Boolean = false,
    secondary: (@Composable () -> Unit)? = null,
    primary: @Composable () -> Unit,
) {
    if (currentWindowClass == BpmWindowClass.Compact) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid), verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) {
                if (compactSecondaryFixed) secondary() else Box(Modifier.weight(0.95f)) { secondary() }
            }
            // La acción principal lleva más ancho que «Cancelar»: «Crear sede» no se parte en dos líneas a 360 dp.
            Box(Modifier.weight(if (secondary != null) 1.05f else 1f)) { primary() }
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) Box(Modifier.autoActionWidth()) { secondary() }
            Box(Modifier.autoActionWidth()) { primary() }
        }
    }
}

/** Ancho a contenido con un mínimo de 160 dp: el botón interior (que llena su contenedor) toma ese ancho. */
private fun Modifier.autoActionWidth(): Modifier = widthIn(min = 160.dp).width(IntrinsicSize.Max)
