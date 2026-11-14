@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Web: descarga mediante un Blob temporal; la URL del objeto se revoca de inmediato. */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver = remember {
    object : TextFileSaver {
        override fun save(fileName: String, mimeType: String, text: String, onResult: (FileSaveResult) -> Unit) {
            val ok = try { downloadText(safeFileName(fileName), mimeType, text) } catch (_: Throwable) { false }
            onResult(if (ok) FileSaveResult.SAVED else FileSaveResult.FAILED)
        }
    }
}

// Se antepone una marca BOM UTF-8 (si falta) para que Excel interprete bien los acentos.
@JsFun("""(name, mime, text) => {
    try {
        if (!globalThis.document || !globalThis.Blob || !globalThis.URL) return false;
        const body = text.charCodeAt(0) === 0xFEFF ? text : '﻿' + text;
        const blob = new Blob([body], { type: mime + ';charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url; link.download = name; link.style.display = 'none';
        document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        return true;
    } catch (_) { return false; }
}""")
private external fun downloadText(name: String, mime: String, text: String): Boolean
