package pe.bpm.core.presentation

import androidx.compose.runtime.Composable

/** Resultado de entregar el archivo a la plataforma. */
enum class FileSaveResult { SAVED, CANCELLED, FAILED }

/**
 * Guarda o comparte un archivo de texto UTF-8 generado por la app (por ejemplo un CSV). El texto
 * se conserva solo mientras dura la operación y el archivo nunca se escribe en un lugar que la
 * persona no haya elegido o conocido. [onResult] informa si se guardó, se canceló o falló.
 *
 * - Web: descarga mediante un Blob temporal.
 * - Android: selector de documentos del sistema (crear archivo).
 * - iOS: hoja de compartir/guardar en Archivos con un archivo temporal que se borra al terminar.
 * - JVM (solo desarrollo y pruebas): escribe en la carpeta temporal.
 */
interface TextFileSaver {
    fun save(fileName: String, mimeType: String, text: String, onResult: (FileSaveResult) -> Unit)
}

@Composable
expect fun rememberTextFileSaver(): TextFileSaver

/** Nombre seguro para cualquier sistema de archivos: sin rutas ni caracteres de control. */
fun safeFileName(name: String): String = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(120).ifBlank { "archivo.csv" }
