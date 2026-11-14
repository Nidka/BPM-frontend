@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.research.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Web: descarga mediante un enlace temporal `blob:` que se revoca de inmediato. */
@Composable
actual fun rememberCsvSaver(onResult: (CsvSaveResult) -> Unit): (String, ByteArray) -> Unit {
    val callback = rememberUpdatedState(onResult)
    return remember {
        { name, bytes ->
            val ok = try { downloadCsv(name, bytes.decodeToString()) } catch (_: Throwable) { false }
            callback.value(if (ok) CsvSaveResult.SAVED else CsvSaveResult.FAILED)
        }
    }
}

@JsFun("""(name, text) => {
    if (!globalThis.document || !globalThis.Blob || !globalThis.URL) return false;
    const url = URL.createObjectURL(new Blob([text], {type: 'text/csv;charset=utf-8'}));
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
    return true;
}""")
private external fun downloadCsv(name: String, text: String): Boolean
