@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.UIKit.*

/** iOS: escribe un archivo temporal y abre la hoja de compartir (incluye "Guardar en Archivos"). */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver {
    val host = LocalUIViewController.current
    return remember(host) {
        object : TextFileSaver {
            private fun present(path: String, written: Boolean, onResult: (FileSaveResult) -> Unit) {
                if (!written || host.presentedViewController != null || host.view.window == null) {
                    NSFileManager.defaultManager.removeItemAtPath(path, null)
                    onResult(FileSaveResult.FAILED)
                    return
                }
                val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
                // En iPad la hoja es un popover y necesita un ancla.
                sheet.popoverPresentationController?.sourceView = host.view
                sheet.completionWithItemsHandler = { _, completed, _, error ->
                    // El archivo temporal se borra al terminar, haya sido compartido o no.
                    NSFileManager.defaultManager.removeItemAtPath(path, null)
                    onResult(if (error != null) FileSaveResult.FAILED else if (completed) FileSaveResult.SAVED else FileSaveResult.CANCELLED)
                }
                host.presentViewController(sheet, animated = true, completion = null)
            }
            override fun save(fileName: String, mimeType: String, text: String, onResult: (FileSaveResult) -> Unit) {
                val path = NSTemporaryDirectory().trimEnd('/') + "/" + safeFileName(fileName)
                present(path, text.isNotEmpty() && (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null), onResult)
            }
            override fun saveBytes(fileName: String, mimeType: String, bytes: ByteArray, onResult: (FileSaveResult) -> Unit) {
                val path = NSTemporaryDirectory().trimEnd('/') + "/" + safeFileName(fileName)
                val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                present(path, bytes.isNotEmpty() && data.writeToFile(path, atomically = true), onResult)
            }
        }
    }
}
