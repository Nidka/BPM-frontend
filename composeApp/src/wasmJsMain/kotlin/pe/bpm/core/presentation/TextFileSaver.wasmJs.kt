@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Web: descarga mediante un Blob temporal; la URL del objeto se revoca de inmediato. */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver = remember {
    object : TextFileSaver {
        override fun save(fileName: String, mimeType: String, text: String, onResult: (Boolean) -> Unit) {
            onResult(downloadText(safeFileName(fileName), mimeType, text))
        }
    }
}

@JsFun("""(name, mime, text) => {
    try {
        const blob = new Blob(['﻿' + text], { type: mime + ';charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url; link.download = name; link.style.display = 'none';
        document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        return true;
    } catch (_) { return false; }
}""")
private external fun downloadText(name: String, mime: String, text: String): Boolean
