@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pe.bpm.features.media.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.bpm.features.media.domain.EvidencePhotoSelectionRules
import pe.bpm.features.media.domain.PhotoSelection
import pe.bpm.features.media.domain.PhotoSelectionFailure
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSMutableData
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.appendData
import platform.Foundation.fileHandleForReadingFromURL
import platform.UniformTypeIdentifiers.UTType
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPresentationController
import platform.UIKit.UIViewController
import platform.UIKit.presentationController
import platform.darwin.NSObject

@Composable
actual fun rememberEvidencePhotoPicker(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): () -> Unit {
    val host = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    val picker = remember(host, scope) { IosEvidencePhotoPicker(host, scope) { callback.value(it) } }
    DisposableEffect(picker) { onDispose { picker.dispose() } }
    return remember(picker, maxBytes, allowedMimeTypes) { { picker.launch(maxBytes, allowedMimeTypes) } }
}

/** Retiene los delegados débiles de UIKit y descarta respuestas después de salir. */
private class IosEvidencePhotoPicker(
    private val host: UIViewController,
    private val scope: CoroutineScope,
    private val onResult: (PhotoSelection) -> Unit,
) {
    private var disposed = false
    private var busy = false
    private var picker: UIDocumentPickerViewController? = null
    private var delegate: DocumentDelegate? = null

    fun launch(maxBytes: Int, allowedMimeTypes: Set<String>) {
        if (disposed || busy) return
        val accepted = allowedMimeTypes.intersect(EvidencePhotoSelectionRules.supportedMimeTypes)
        val contentTypes = accepted.mapNotNull { UTType.typeWithMIMEType(it) }
        if (maxBytes <= 0 || contentTypes.isEmpty() || host.presentedViewController != null || host.view.window == null) {
            onResult(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE))
            return
        }
        busy = true
        val controller = UIDocumentPickerViewController(forOpeningContentTypes = contentTypes, asCopy = false)
        val retainedDelegate = DocumentDelegate { url ->
            picker?.delegate = null
            picker?.presentationController?.delegate = null
            picker = null
            delegate = null
            if (url == null) {
                complete(PhotoSelection.Cancelled)
            } else scope.launch {
                val result = try {
                    readPhoto(url, maxBytes, accepted)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
                }
                complete(result)
            }
        }
        picker = controller
        delegate = retainedDelegate
        controller.allowsMultipleSelection = false
        controller.delegate = retainedDelegate
        host.presentViewController(controller, animated = true, completion = null)
        controller.presentationController?.delegate = retainedDelegate
    }

    private fun complete(result: PhotoSelection) {
        busy = false
        if (!disposed) onResult(result)
    }

    fun dispose() {
        disposed = true
        picker?.delegate = null
        picker?.presentationController?.delegate = null
        picker?.dismissViewControllerAnimated(false, completion = null)
        picker = null
        delegate = null
    }
}

private class DocumentDelegate(private val complete: (NSURL?) -> Unit) : NSObject(),
    UIDocumentPickerDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {
    private var completed = false
    private fun finish(url: NSURL?) {
        if (!completed) {
            completed = true
            complete(url)
        }
    }
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        finish(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = finish(null)
    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) = finish(null)
}

private suspend fun readPhoto(url: NSURL, maxBytes: Int, allowedMimeTypes: Set<String>): PhotoSelection =
    withContext(Dispatchers.Default) {
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            var result: PhotoSelection = PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
            NSFileCoordinator().coordinateReadingItemAtURL(url, options = 0u, error = null) { coordinatedUrl ->
                val readableUrl = coordinatedUrl ?: return@coordinateReadingItemAtURL
                val name = readableUrl.lastPathComponent ?: "evidencia"
                val mime = when (readableUrl.pathExtension?.lowercase()) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    else -> ""
                }
                if (mime !in allowedMimeTypes) {
                    result = PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED)
                    return@coordinateReadingItemAtURL
                }
                val path = readableUrl.path ?: return@coordinateReadingItemAtURL
                val size = (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
                if (size != null && size > maxBytes.toLong()) {
                    result = PhotoSelection.Failed(PhotoSelectionFailure.TOO_LARGE)
                    return@coordinateReadingItemAtURL
                }
                val handle = NSFileHandle.fileHandleForReadingFromURL(readableUrl, null) ?: return@coordinateReadingItemAtURL
                try {
                    val data = NSMutableData()
                    // El archivo puede cambiar después de consultar su tamaño; la lectura también está acotada.
                    while (data.length.toLong() <= maxBytes.toLong()) {
                        if (!isActive) return@coordinateReadingItemAtURL
                        val count = minOf(8192L, maxBytes.toLong() - data.length.toLong() + 1).toULong()
                        val chunk = handle.readDataUpToLength(count, null) ?: return@coordinateReadingItemAtURL
                        if (chunk.length == 0uL) break
                        data.appendData(chunk)
                    }
                    result = if (data.length.toLong() > maxBytes.toLong()) {
                        PhotoSelection.Failed(PhotoSelectionFailure.TOO_LARGE)
                    } else {
                        val bytes = if (data.length == 0uL) byteArrayOf() else data.bytes!!.readBytes(data.length.toInt())
                        EvidencePhotoSelectionRules.validate(name, mime, bytes, maxBytes, allowedMimeTypes)
                    }
                } finally {
                    handle.closeAndReturnError(null)
                }
            }
            ensureActive()
            result
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }
