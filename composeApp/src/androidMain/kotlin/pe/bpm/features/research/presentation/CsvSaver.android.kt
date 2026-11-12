package pe.bpm.features.research.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android: selector "Crear documento" del sistema; el usuario decide dónde guardar el CSV. */
@Composable
actual fun rememberCsvSaver(onResult: (CsvSaveResult) -> Unit): (String, ByteArray) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    // Los bytes esperan en memoria solo mientras el usuario elige el destino.
    val holder = remember { arrayOfNulls<ByteArray>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val bytes = holder[0]
        holder[0] = null
        if (uri == null || bytes == null) {
            callback.value(CsvSaveResult.CANCELLED)
        } else scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val stream = resolver.openOutputStream(uri, "wt")
                    if (stream == null) CsvSaveResult.FAILED else {
                        stream.use { it.write(bytes) }
                        CsvSaveResult.SAVED
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    CsvSaveResult.FAILED
                }
            }
            callback.value(result)
        }
    }
    return remember(launcher) {
        { name, bytes ->
            holder[0] = bytes
            try {
                launcher.launch(name)
            } catch (_: Exception) {
                holder[0] = null
                callback.value(CsvSaveResult.FAILED)
            }
        }
    }
}
