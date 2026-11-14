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
    // Un único guardado a la vez: el texto y la devolución de llamada se retienen hasta el resultado.
    val pending = remember { arrayOfNulls<Pair<String, (FileSaveResult) -> Unit>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val job = pending[0]
        pending[0] = null
        if (job == null) return@rememberLauncherForActivityResult
        if (uri == null) { job.second(FileSaveResult.CANCELLED); return@rememberLauncherForActivityResult }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val stream = resolver.openOutputStream(uri, "wt")
                    if (stream == null) FileSaveResult.FAILED else {
                        stream.use { it.write(job.first.toByteArray(Charsets.UTF_8)) }
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
    return remember(launcher) {
        object : TextFileSaver {
            override fun save(fileName: String, mimeType: String, text: String, onResult: (FileSaveResult) -> Unit) {
                if (pending[0] != null) { onResult(FileSaveResult.FAILED); return }
                pending[0] = text to onResult
                try { launcher.launch(safeFileName(fileName)) } catch (_: Exception) { pending[0] = null; onResult(FileSaveResult.FAILED) }
            }
        }
    }
}
