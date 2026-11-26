package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** JVM es host de pruebas: escribe el archivo en la carpeta temporal del sistema. */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver = remember {
    object : TextFileSaver {
        override fun save(fileName: String, mimeType: String, text: String, onResult: (FileSaveResult) -> Unit) {
            val saved = try {
                java.io.File(System.getProperty("java.io.tmpdir"), safeFileName(fileName)).writeText(text, Charsets.UTF_8); true
            } catch (_: Exception) { false }
            onResult(if (saved) FileSaveResult.SAVED else FileSaveResult.FAILED)
        }
        override fun saveBytes(fileName: String, mimeType: String, bytes: ByteArray, onResult: (FileSaveResult) -> Unit) {
            val saved = try { java.io.File(System.getProperty("java.io.tmpdir"), safeFileName(fileName)).writeBytes(bytes); true } catch (_: Exception) { false }
            onResult(if (saved) FileSaveResult.SAVED else FileSaveResult.FAILED)
        }
    }
}
