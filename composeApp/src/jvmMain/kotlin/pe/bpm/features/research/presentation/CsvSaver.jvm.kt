package pe.bpm.features.research.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.File

/** JVM es el host de pruebas: escribe en la carpeta temporal del sistema. */
@Composable
actual fun rememberCsvSaver(onResult: (CsvSaveResult) -> Unit): (String, ByteArray) -> Unit {
    val callback = rememberUpdatedState(onResult)
    return remember {
        { name, bytes ->
            val result = try {
                File(System.getProperty("java.io.tmpdir"), name.filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "respuestas.csv" }).writeBytes(bytes)
                CsvSaveResult.SAVED
            } catch (_: Exception) {
                CsvSaveResult.FAILED
            }
            callback.value(result)
        }
    }
}
