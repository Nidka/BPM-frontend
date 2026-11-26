package pe.bpm.core.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android: el selector del sistema (SAF) deja elegir dónde crear el archivo; no requiere permisos de almacenamiento. */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    // Un único guardado a la vez: los bytes y la devolución de llamada se retienen hasta el resultado.
    val pending = remember { arrayOfNulls<Pair<ByteArray, (FileSaveResult) -> Unit>>(1) }
    val deliver: (android.net.Uri?) -> Unit = deliver@{ uri ->
        val job = pending[0]
        pending[0] = null
        if (job == null) return@deliver
        if (uri == null) { job.second(FileSaveResult.CANCELLED); return@deliver }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val stream = resolver.openOutputStream(uri, "wt")
                    if (stream == null) FileSaveResult.FAILED else {
                        stream.use { it.write(job.first) }
                        FileSaveResult.SAVED
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FileSaveResult.FAILED
                }
            }
            job.second(result)
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), deliver)
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf"), deliver)
    return remember(csvLauncher, pdfLauncher) {
        object : TextFileSaver {
            private fun start(fileName: String, bytes: ByteArray, pdf: Boolean, onResult: (FileSaveResult) -> Unit) {
                if (pending[0] != null) { onResult(FileSaveResult.FAILED); return }
                pending[0] = bytes to onResult
                try { (if (pdf) pdfLauncher else csvLauncher).launch(safeFileName(fileName)) } catch (_: Exception) { pending[0] = null; onResult(FileSaveResult.FAILED) }
            }
            override fun save(fileName: String, mimeType: String, text: String, onResult: (FileSaveResult) -> Unit) =
                start(fileName, text.toByteArray(Charsets.UTF_8), false, onResult)
            override fun saveBytes(fileName: String, mimeType: String, bytes: ByteArray, onResult: (FileSaveResult) -> Unit) =
                start(fileName, bytes, mimeType == "application/pdf", onResult)
        }
    }
}
