@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.training.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import pe.bpm.features.training.domain.PickedVideo
import pe.bpm.features.training.domain.TrainingVideoRules
import pe.bpm.features.training.domain.VideoSelection
import pe.bpm.features.training.domain.VideoSelectionFailure

/** Web: `<input type="file">` temporal filtrado a MP4/WebM; los bytes se copian en bloques de 4 bytes. */
@Composable
actual fun rememberLessonVideoPicker(maxBytes: Int, onResult: (VideoSelection) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onResult)
    val picker = remember {
        createVideoPicker { name, mime, buffer, failure, limit ->
            val result = if (buffer != null) {
                try {
                    val length = videoBufferLength(buffer)
                    val bytes = ByteArray(length)
                    var index = 0
                    while (index < length) {
                        val packed = videoBufferWord(buffer, index)
                        bytes[index] = packed.toByte()
                        if (index + 1 < length) bytes[index + 1] = (packed shr 8).toByte()
                        if (index + 2 < length) bytes[index + 2] = (packed shr 16).toByte()
                        if (index + 3 < length) bytes[index + 3] = (packed shr 24).toByte()
                        index += 4
                    }
                    if (length > limit) VideoSelection.Failed(VideoSelectionFailure.TOO_LARGE)
                    else if (TrainingVideoRules.validate(name, mime, bytes) != null) VideoSelection.Failed(VideoSelectionFailure.UNSUPPORTED)
                    else VideoSelection.Selected(PickedVideo(name, mime, bytes))
                } catch (_: Exception) {
                    VideoSelection.Failed(VideoSelectionFailure.READ_FAILED)
                }
            } else when (failure) {
                "CANCELLED" -> VideoSelection.Cancelled
                "TOO_LARGE" -> VideoSelection.Failed(VideoSelectionFailure.TOO_LARGE)
                "UNSUPPORTED" -> VideoSelection.Failed(VideoSelectionFailure.UNSUPPORTED)
                "UNAVAILABLE" -> VideoSelection.Failed(VideoSelectionFailure.UNAVAILABLE)
                else -> VideoSelection.Failed(VideoSelectionFailure.READ_FAILED)
            }
            callback.value(result)
        }
    }
    DisposableEffect(picker) { onDispose { disposeVideoPicker(picker) } }
    return remember(picker, maxBytes) { { launchVideoPicker(picker, maxBytes) } }
}

// Cada composición conserva solo su controlador; no se agregan objetos a window ni document.
@JsFun("""(callback) => {
    let disposed = false;
    let pending = null;
    let busy = false;
    const accepted = ['video/mp4', 'video/webm'];
    const disposeInput = () => {
        if (!pending) return;
        pending.input.removeEventListener('change', pending.change);
        pending.input.removeEventListener('cancel', pending.cancel);
        pending.input.remove();
        pending = null;
    };
    return {
        launch(limit) {
            if (disposed || busy) return;
            if (limit <= 0 || !globalThis.document || !globalThis.File) { callback('', '', null, 'UNAVAILABLE', limit); return; }
            busy = true;
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = 'video/mp4,video/webm,.mp4,.webm';
            input.multiple = false;
            input.style.display = 'none';
            const finish = (name, mime, bytes, failure) => {
                disposeInput();
                busy = false;
                if (!disposed) callback(name, mime, bytes, failure, limit);
            };
            const cancel = () => finish('', '', null, 'CANCELLED');
            const change = async () => {
                const file = input.files && input.files[0];
                disposeInput();
                if (!file) { finish('', '', null, 'CANCELLED'); return; }
                const lower = file.name.toLowerCase();
                const mime = file.type || (lower.endsWith('.mp4') ? 'video/mp4' : lower.endsWith('.webm') ? 'video/webm' : '');
                if (!accepted.includes(mime)) { finish('', '', null, 'UNSUPPORTED'); return; }
                if (file.size > limit) { finish('', '', null, 'TOO_LARGE'); return; }
                try {
                    const bytes = new Uint8Array(await file.arrayBuffer());
                    if (disposed) return;
                    if (bytes.length > limit) { finish('', '', null, 'TOO_LARGE'); return; }
                    finish(file.name, mime, bytes, '');
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
private external fun createVideoPicker(callback: (String, String, JsAny?, String, Int) -> Unit): JsAny

@JsFun("(picker, limit) => picker.launch(limit)")
private external fun launchVideoPicker(picker: JsAny, limit: Int)

@JsFun("(picker) => picker.dispose()")
private external fun disposeVideoPicker(picker: JsAny)

@JsFun("(buffer) => buffer.length")
private external fun videoBufferLength(buffer: JsAny): Int

// Empaqueta cuatro bytes consecutivos en un entero; fuera de rango aporta ceros.
@JsFun("(b, i) => (b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24))")
private external fun videoBufferWord(buffer: JsAny, index: Int): Int
