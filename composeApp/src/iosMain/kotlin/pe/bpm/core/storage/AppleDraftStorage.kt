@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.ExperimentalUnsignedTypes::class, kotlinx.cinterop.BetaInteropApi::class)

package pe.bpm.core.storage

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.*
import platform.CoreCrypto.CC_SHA256

/** Documentos del contenedor privado, con protección completa de iOS y excluidos de backup. */
class AppleDraftStorage : DraftStorage {
    private val mutex = Mutex()
    private fun directory(): NSURL {
        val base = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)!!
        val directory = base.URLByAppendingPathComponent("bpm-drafts-v1", true)!!
        check(NSFileManager.defaultManager.createDirectoryAtURL(directory, true, null, null))
        check(directory.setResourceValue(NSNumber.numberWithBool(true), NSURLIsExcludedFromBackupKey, null))
        return directory
    }
    private fun url(key: String): NSURL {
        val bytes = key.encodeToByteArray()
        val digest = UByteArray(32)
        bytes.usePinned { input -> digest.usePinned { output -> CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0)) } }
        return directory().URLByAppendingPathComponent(digest.joinToString("") { it.toString(16).padStart(2, '0') } + ".json")!!
    }
    override suspend fun read(key: String): String? = withContext(Dispatchers.Default) { mutex.withLock {
        val url = url(key)
        if (!NSFileManager.defaultManager.fileExistsAtPath(url.path!!)) return@withLock null
        val data = NSData.dataWithContentsOfURL(url) ?: error("No se pudo leer el borrador")
        require(data.length.toLong() in 1..MAX_DRAFT_BYTES.toLong())
        data.bytes!!.readBytes(data.length.toInt()).decodeToString()
    } }
    override suspend fun write(key: String, value: String) = withContext(Dispatchers.Default) { mutex.withLock {
        val bytes = value.encodeToByteArray(); require(bytes.size in 1..MAX_DRAFT_BYTES)
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToURL(url(key), NSDataWritingAtomic or NSDataWritingFileProtectionComplete, null))
    } }
    override suspend fun remove(key: String) = withContext(Dispatchers.Default) { mutex.withLock {
        val url = url(key)
        if (NSFileManager.defaultManager.fileExistsAtPath(url.path!!)) check(NSFileManager.defaultManager.removeItemAtURL(url, null))
    } }
}
