@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.media.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import pe.bpm.features.media.domain.EvidencePhotoSelectionRules
import pe.bpm.features.media.domain.PhotoSelection
import pe.bpm.features.media.domain.PhotoSelectionFailure

@Composable
actual fun rememberEvidencePhotoPicker(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    val picker = remember {
        createPhotoPicker { name, mime, buffer, failure, limit, accept ->
            val result = if (buffer != null) {
                try {
                    val bytes = ByteArray(photoBufferLength(buffer)) { photoBufferByte(buffer, it).toByte() }
                    EvidencePhotoSelectionRules.validate(name, mime, bytes, limit, accept.split(',').toSet())
                } catch (_: Exception) {
                    PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
                }
            } else when (failure) {
                "CANCELLED" -> PhotoSelection.Cancelled
                "TOO_LARGE" -> PhotoSelection.Failed(PhotoSelectionFailure.TOO_LARGE)
                "UNSUPPORTED" -> PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED)
                "UNAVAILABLE" -> PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE)
                else -> PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
            }
            callback.value(result)
        }
    }
    DisposableEffect(picker) { onDispose { disposePhotoPicker(picker) } }
    return remember(picker, maxBytes, allowedMimeTypes) {
        {
            val accept = allowedMimeTypes.intersect(EvidencePhotoSelectionRules.supportedMimeTypes).joinToString(",")
            launchPhotoPicker(picker, maxBytes, accept)
        }
    }
}

// Cada composición conserva solo su controlador; no se agregan objetos a window ni document.
@JsFun("""(callback) => {
    let disposed = false;
    let pending = null;
    let busy = false;
    const disposeInput = () => {
        if (!pending) return;
        pending.input.removeEventListener('change', pending.change);
        pending.input.removeEventListener('cancel', pending.cancel);
        pending.input.remove();
        pending = null;
    };
    return {
        launch(limit, accept) {
            if (disposed || busy) return;
            if (limit <= 0 || !accept || !globalThis.document || !globalThis.File) {
                callback('', '', null, 'UNAVAILABLE', limit, accept);
                return;
            }
            busy = true;
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = accept;
            input.multiple = false;
            input.style.display = 'none';
            const finish = (name, mime, bytes, failure) => {
                disposeInput();
                busy = false;
                if (!disposed) callback(name, mime, bytes, failure, limit, accept);
            };
            const cancel = () => finish('', '', null, 'CANCELLED');
            const change = async () => {
                const file = input.files && input.files[0];
                disposeInput();
                if (!file) { finish('', '', null, 'CANCELLED'); return; }
                if (file.size > limit) { finish('', '', null, 'TOO_LARGE'); return; }
                if (!accept.split(',').includes(file.type)) { finish('', '', null, 'UNSUPPORTED'); return; }
                try {
                    const bytes = new Uint8Array(await file.arrayBuffer());
                    if (disposed) return;
                    if (bytes.length > limit) { finish('', '', null, 'TOO_LARGE'); return; }
                    finish(file.name, file.type, bytes, '');
                } catch (_) { finish('', '', null, 'READ_FAILED'); }
            };
            pending = {input, change, cancel};
            input.addEventListener('change', change);
            input.addEventListener('cancel', cancel);
            try {
                document.body.appendChild(input);
                input.click();
            } catch (_) { finish('', '', null, 'UNAVAILABLE'); }
        },
        dispose() { disposed = true; disposeInput(); busy = false; }
    };
}""")
private external fun createPhotoPicker(callback: (String, String, JsAny?, String, Int, String) -> Unit): JsAny

@JsFun("(picker, limit, accept) => picker.launch(limit, accept)")
private external fun launchPhotoPicker(picker: JsAny, limit: Int, accept: String)

@JsFun("(picker) => picker.dispose()")
private external fun disposePhotoPicker(picker: JsAny)

@JsFun("(buffer) => buffer.length")
private external fun photoBufferLength(buffer: JsAny): Int

@JsFun("(buffer, index) => buffer[index]")
private external fun photoBufferByte(buffer: JsAny, index: Int): Int
