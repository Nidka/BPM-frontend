package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
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
