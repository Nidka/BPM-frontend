@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.ExperimentalUnsignedTypes::class)

package pe.bpm.features.media.data

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreCrypto.CC_SHA256

internal actual suspend fun photoSha256(bytes: ByteArray): String = withContext(Dispatchers.Default) {
    val digest = UByteArray(32)
    digest.usePinned { output ->
        if (bytes.isEmpty()) CC_SHA256(null, 0u, output.addressOf(0))
        else bytes.usePinned { input -> CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0)) }
    }
    digest.joinToString("") { it.toString(16).padStart(2, '0') }
}
