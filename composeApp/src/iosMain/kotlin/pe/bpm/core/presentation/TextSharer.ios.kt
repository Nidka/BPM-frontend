@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.*

/** iOS: hoja nativa de compartir y portapapeles solo local que caduca a los 10 minutos. */
@Composable
actual fun rememberTextSharer(): TextSharer {
    val host = LocalUIViewController.current
    return remember(host) {
        object : TextSharer {
            override fun share(title: String, text: String, onResult: (ShareResult) -> Unit) {
                if (host.presentedViewController != null || host.view.window == null) {
                    onResult(ShareResult.FAILED)
                    return
                }
                val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
                // En iPad la hoja es un popover y necesita un ancla.
                sheet.popoverPresentationController?.sourceView = host.view
                sheet.completionWithItemsHandler = { _, completed, _, error ->
                    onResult(if (error != null) ShareResult.FAILED else if (completed) ShareResult.SHARED else ShareResult.CANCELLED)
                }
                host.presentViewController(sheet, animated = true, completion = null)
            }

            override fun copy(text: String, onResult: (ShareResult) -> Unit) {
                val options = mapOf<Any?, Any?>(
                    UIPasteboardOptionLocalOnly to NSNumber(bool = true),
                    UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSinceNow(600.0),
                )
                UIPasteboard.generalPasteboard.setItems(listOf(mapOf<Any?, Any?>("public.utf8-plain-text" to text)), options)
                onResult(ShareResult.COPIED)
            }
        }
    }
}
