@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.media.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual suspend fun photoSha256(bytes: ByteArray): String = suspendCancellableCoroutine { continuation ->
    val buffer = digestBuffer(bytes.size)
    bytes.forEachIndexed { index, value -> setDigestByte(buffer, index, value.toInt() and 0xff) }
    digestSha256(buffer, { hex -> if (continuation.isActive) continuation.resume(hex) }, {
        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SHA-256 no disponible"))
    })
}

@JsFun("(size) => new Uint8Array(size)")
private external fun digestBuffer(size: Int): JsAny
@JsFun("(buffer, index, value) => { buffer[index] = value; }")
private external fun setDigestByte(buffer: JsAny, index: Int, value: Int)
@JsFun("""(buffer, resolve, reject) => {
    if (!globalThis.crypto?.subtle) { buffer.fill(0); reject(); return; }
    crypto.subtle.digest('SHA-256', buffer).then(result => {
        buffer.fill(0);
        resolve(Array.from(new Uint8Array(result), b => b.toString(16).padStart(2, '0')).join(''));
    }).catch(() => { buffer.fill(0); reject(); });
}""")
private external fun digestSha256(buffer: JsAny, resolve: (String) -> Unit, reject: () -> Unit)
