@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController

/** iOS: escribe un archivo temporal y abre la hoja de compartir (incluye "Guardar en Archivos"). */
@Composable
actual fun rememberTextFileSaver(): TextFileSaver {
    val controller = LocalUIViewController.current
    return remember(controller) {
        object : TextFileSaver {
            override fun save(fileName: String, mimeType: String, text: String, onResult: (Boolean) -> Unit) {
                val path = NSTemporaryDirectory() + safeFileName(fileName)
                val written = (text as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
                if (!written) { onResult(false); return }
                val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(path)), applicationActivities = null)
                sheet.completionWithItemsHandler = { _, completed, _, _ -> onResult(completed) }
                // En iPad la hoja es un popover y necesita un ancla.
                sheet.popoverPresentationController?.sourceView = controller.view
                controller.presentViewController(sheet, animated = true, completion = null)
            }
        }
    }
}
