package pe.bpm.core.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Android: el selector del sistema (SAF) deja elegir dónde crear el archivo; no requiere permisos de almacenamiento. */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver {
    val resolver = LocalContext.current.contentResolver
    // Un único guardado a la vez: el texto y la devolución de llamada se retienen hasta el resultado.
    val pending = remember { arrayOfNulls<Pair<String, (Boolean) -> Unit>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val job = pending[0]
        pending[0] = null
        if (job == null) return@rememberLauncherForActivityResult
        if (uri == null) { job.second(false); return@rememberLauncherForActivityResult }
        val saved = try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(job.first.toByteArray(Charsets.UTF_8)); true } ?: false
        } catch (_: Exception) { false }
        job.second(saved)
    }
    return remember(launcher) {
        object : TextFileSaver {
            override fun save(fileName: String, mimeType: String, text: String, onResult: (Boolean) -> Unit) {
                if (pending[0] != null) { onResult(false); return }
                pending[0] = text to onResult
                try { launcher.launch(safeFileName(fileName)) } catch (_: Exception) { pending[0] = null; onResult(false) }
            }
        }
    }
}
