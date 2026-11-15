@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Web: `navigator.share` si existe (se invoca desde el gesto de la persona) y, si no, copia al portapapeles. */
@Composable
actual fun rememberTextSharer(): TextSharer = remember {
    object : TextSharer {
        override fun share(title: String, text: String, onResult: (ShareResult) -> Unit) {
            val mode = try { shareOrCopy(title, text) } catch (_: Throwable) { "failed" }
            onResult(when (mode) { "shared" -> ShareResult.SHARED; "copied" -> ShareResult.COPIED; else -> ShareResult.FAILED })
        }

        override fun copy(text: String, onResult: (ShareResult) -> Unit) {
            val ok = try { copyText(text) } catch (_: Throwable) { false }
            onResult(if (ok) ShareResult.COPIED else ShareResult.FAILED)
        }
    }
}

// Devuelve de inmediato qué camino se tomó; el resultado final de la promesa no cambia la interfaz.
@JsFun("""(title, text) => {
    try {
        if (globalThis.navigator && typeof navigator.share === 'function') {
            navigator.share({ title: title, text: text }).catch(() => {});
            return 'shared';
        }
        if (globalThis.navigator && navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
            navigator.clipboard.writeText(text).catch(() => {});
            return 'copied';
        }
        const area = document.createElement('textarea');
        area.value = text; area.setAttribute('readonly', ''); area.style.position = 'fixed'; area.style.opacity = '0';
        document.body.appendChild(area); area.select();
        const done = document.execCommand('copy');
        area.remove();
        return done ? 'copied' : 'failed';
    } catch (_) { return 'failed'; }
}""")
private external fun shareOrCopy(title: String, text: String): String

@JsFun("""(text) => {
    try {
        if (globalThis.navigator && navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
            navigator.clipboard.writeText(text).catch(() => {});
            return true;
        }
        const area = document.createElement('textarea');
        area.value = text; area.setAttribute('readonly', ''); area.style.position = 'fixed'; area.style.opacity = '0';
        document.body.appendChild(area); area.select();
        const done = document.execCommand('copy');
        area.remove();
        return done;
    } catch (_) { return false; }
}""")
private external fun copyText(text: String): Boolean
