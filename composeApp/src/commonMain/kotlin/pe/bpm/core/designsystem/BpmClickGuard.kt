package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.time.TimeSource

/** Ventana en la que un segundo toque sobre el mismo botón se ignora (dedo que rebota, toque doble sin querer). */
const val BPM_CLICK_GUARD_MS = 600L

/**
 * Filtro de toques repetidos: deja pasar el primero y descarta los que llegan antes de [windowMs].
 * Separado de Compose para poder probarlo; [now] da milisegundos monótonos.
 */
class BpmClickGuard(private val windowMs: Long = BPM_CLICK_GUARD_MS, private val now: () -> Long = monotonicMillis()) {
    private var last: Long? = null
    fun accept(): Boolean {
        val t = now()
        val previous = last
        if (previous != null && t - previous < windowMs) return false
        last = t
        return true
    }
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}

/** Un toque = una acción: para botones que navegan, abren hojas, la cámara o la galería. */
@Composable
fun rememberGuardedClick(onClick: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(onClick)
    val guard = remember { BpmClickGuard() }
    return remember { { if (guard.accept()) latest() } }
}
