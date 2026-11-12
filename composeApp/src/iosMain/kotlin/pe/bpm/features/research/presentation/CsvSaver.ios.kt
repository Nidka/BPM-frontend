@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pe.bpm.features.research.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController

/** iOS: hoja de compartir con un archivo temporal (Guardar en Archivos, AirDrop, Mail…). */
@Composable
actual fun rememberCsvSaver(onResult: (CsvSaveResult) -> Unit): (String, ByteArray) -> Unit {
    val host = LocalUIViewController.current
    val callback = rememberUpdatedState(onResult)
    return remember(host) {
        { name, bytes ->
            val safeName = name.filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "respuestas.csv" }
            val path = NSTemporaryDirectory().trimEnd('/') + "/" + safeName
            val written = bytes.isNotEmpty() && bytes.usePinned { pinned ->
                NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()).writeToFile(path, atomically = true)
            }
            if (!written || host.presentedViewController != null || host.view.window == null) {
                callback.value(CsvSaveResult.FAILED)
            } else {
                val url = NSURL.fileURLWithPath(path)
                val controller = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
                // En iPad la hoja necesita un ancla; se usa la vista raíz.
                controller.popoverPresentationController?.sourceView = host.view
                controller.completionWithItemsHandler = { _, completed, _, error ->
                    // El archivo temporal se borra al terminar, haya sido compartido o no.
                    NSFileManager.defaultManager.removeItemAtPath(path, null)
                    callback.value(if (error != null) CsvSaveResult.FAILED else if (completed) CsvSaveResult.SAVED else CsvSaveResult.CANCELLED)
                }
                host.presentViewController(controller, animated = true, completion = null)
            }
        }
    }
}
