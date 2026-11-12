package pe.bpm.core.presentation

import androidx.compose.runtime.Composable

/**
 * Guarda o comparte un archivo de texto UTF-8 generado por la app (por ejemplo un CSV). El texto
 * se conserva solo mientras dura la operación. [onResult] recibe verdadero si la persona completó el
 * guardado o el uso compartido y falso si lo canceló o falló.
 *
 * - Web: descarga mediante un Blob temporal.
 * - Android: selector de documentos del sistema (crear archivo).
 * - iOS: hoja de compartir/guardar en Archivos.
 * - JVM (solo desarrollo y pruebas): escribe en la carpeta temporal.
 */
interface TextFileSaver {
    fun save(fileName: String, mimeType: String, text: String, onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberTextFileSaver(): TextFileSaver

/** Nombre seguro para cualquier sistema de archivos: sin rutas ni caracteres de control. */
fun safeFileName(name: String): String = name.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(120).ifBlank { "archivo.csv" }
