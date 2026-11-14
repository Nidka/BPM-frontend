package pe.bpm.features.research.presentation

import androidx.compose.runtime.Composable

/** Resultado de entregar el archivo a la plataforma. */
enum class CsvSaveResult { SAVED, CANCELLED, FAILED }

/**
 * Devuelve una acción que entrega un CSV al usuario; se invoca tras una descarga confirmada. El
 * archivo nunca se escribe en un lugar que el usuario no haya elegido o conocido.
 *
 * - Web: descarga de archivo mediante un enlace temporal `blob:`.
 * - Android: selector del sistema "Crear documento" (SAF); el usuario elige dónde guardar.
 * - iOS: hoja de compartir (Guardar en Archivos, AirDrop, etc.) con un archivo temporal.
 * - JVM (solo pruebas): escribe en la carpeta temporal.
 */
@Composable
expect fun rememberCsvSaver(onResult: (CsvSaveResult) -> Unit): (fileName: String, bytes: ByteArray) -> Unit
