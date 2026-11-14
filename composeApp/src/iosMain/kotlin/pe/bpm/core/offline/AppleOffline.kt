@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.ExperimentalUnsignedTypes::class, kotlinx.cinterop.BetaInteropApi::class)

package pe.bpm.core.offline

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import pe.bpm.core.offline.sql.OfflineDb
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionComplete
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_global_queue
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.fwrite
import platform.posix.truncate

/**
 * Piezas offline de iOS: SQLite nativo en el contenedor privado, NWPathMonitor para la red y
 * archivos con protección completa. No hay planificador en segundo plano (BGTaskScheduler necesita
 * registro en el proyecto Xcode): la Outbox se envía en primer plano, al recuperar red y periódicamente.
 */
object AppleOffline {
    fun platform() = OfflinePlatform(
        database = SqlOfflineDatabase(NativeSqliteDriver(OfflineDb.Schema, "bpm-offline.db")),
        connectivity = AppleConnectivityMonitor(),
        files = ApplePrivateFileStore(),
    )
}

/** NWPathMonitor: conectado cuando el estado del camino es «satisfied». */
class AppleConnectivityMonitor : ConnectivityMonitor {
    private val state = MutableStateFlow(true)
    override val online: StateFlow<Boolean> = state.asStateFlow()
    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_update_handler(monitor) { path -> state.value = nw_path_get_status(path) == nw_path_status_satisfied }
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(0x11, 0u))
        nw_path_monitor_start(monitor)
    }
}

/** Fotos pendientes y videos en Application Support, excluidos de copia de seguridad y con protección completa. */
class ApplePrivateFileStore : PrivateFileStore {
    override val durable = true
    private fun directory(): NSURL {
        val base = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)!!
        val directory = base.URLByAppendingPathComponent("bpm-offline-files-v1", true)!!
        check(NSFileManager.defaultManager.createDirectoryAtURL(directory, true, null, null))
        check(directory.setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null))
        return directory
    }
    private fun url(ref: String): NSURL {
        require(ref.matches(PRIVATE_FILE_REF))
        return directory().URLByAppendingPathComponent(ref)!!
    }
    private fun partUrl(ref: String): NSURL {
        require(ref.matches(PRIVATE_FILE_REF))
        return directory().URLByAppendingPathComponent("$ref.part")!!
    }
    private val manager get() = NSFileManager.defaultManager
    private fun exists(target: NSURL) = manager.fileExistsAtPath(target.path!!)
    private fun length(target: NSURL): Long =
        (manager.attributesOfItemAtPath(target.path!!, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
    private fun bytesToData(bytes: ByteArray, count: Int): NSData =
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = count.toULong()) }

    override suspend fun save(ref: String, bytes: ByteArray) = withContext(Dispatchers.Default) {
        require(bytes.isNotEmpty())
        val data = bytesToData(bytes, bytes.size)
        check(data.writeToURL(url(ref), NSDataWritingAtomic or NSDataWritingFileProtectionComplete, null))
    }
    override suspend fun read(ref: String): ByteArray? = withContext(Dispatchers.Default) {
        val target = url(ref)
        if (!exists(target)) null
        else NSData.dataWithContentsOfURL(target)?.let { it.bytes!!.readBytes(it.length.toInt()) }
    }
    override suspend fun delete(ref: String) = withContext(Dispatchers.Default) {
        listOf(url(ref), partUrl(ref)).forEach { if (exists(it)) manager.removeItemAtURL(it, null) }
        Unit
    }
    override suspend fun deleteAll() = withContext(Dispatchers.Default) {
        val target = directory()
        if (exists(target)) manager.removeItemAtURL(target, null)
        Unit
    }

    override val supportsStreaming get() = true
    override suspend fun list(): List<StoredFileInfo> = withContext(Dispatchers.Default) {
        val base = directory()
        (manager.contentsOfDirectoryAtPath(base.path!!, null) ?: emptyList<Any?>()).mapNotNull { it as? String }.filter { !it.endsWith(".tmp") }.map { name ->
            val size = length(base.URLByAppendingPathComponent(name)!!)
            if (name.endsWith(".part")) StoredFileInfo(name.removeSuffix(".part"), size, true) else StoredFileInfo(name, size, false)
        }
    }
    override suspend fun partialSize(ref: String): Long = withContext(Dispatchers.Default) { partUrl(ref).let { if (exists(it)) length(it) else 0L } }
    override suspend fun appendPart(ref: String, bytes: ByteArray, length: Int) = withContext(Dispatchers.Default) {
        val target = partUrl(ref)
        val data = bytesToData(bytes, length)
        if (!exists(target)) {
            check(manager.createFileAtPath(target.path!!, data, mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionComplete)))
        } else {
            // POSIX en modo "ab": agrega al final sin cargar el archivo; conserva la protección del archivo.
            if (length > 0) {
                val file = checkNotNull(fopen(target.path!!, "ab")) { "No se pudo abrir el archivo parcial" }
                try {
                    val written = bytes.usePinned { fwrite(it.addressOf(0), 1.convert(), length.convert(), file) }
                    check(written.toLong() == length.toLong()) { "Escritura incompleta del archivo parcial" }
                } finally {
                    fclose(file)
                }
            }
        }
    }
    override suspend fun readPart(ref: String, offset: Long, buffer: ByteArray): Int = withContext(Dispatchers.Default) {
        val target = partUrl(ref)
        if (!exists(target)) return@withContext -1
        if (buffer.isEmpty()) return@withContext 0
        val file = fopen(target.path!!, "rb") ?: return@withContext -1
        try {
            if (fseeko(file, offset.convert(), SEEK_SET) != 0) return@withContext -1
            val count = buffer.usePinned { fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file) }.toInt()
            if (count <= 0) -1 else count
        } finally {
            fclose(file)
        }
    }
    override suspend fun commitPart(ref: String) = withContext(Dispatchers.Default) {
        val final = url(ref)
        if (exists(final)) manager.removeItemAtURL(final, null)
        check(manager.moveItemAtURL(partUrl(ref), final, null))
        Unit
    }
    override suspend fun discardPart(ref: String) = withContext(Dispatchers.Default) {
        partUrl(ref).let { if (exists(it)) manager.removeItemAtURL(it, null) }
        Unit
    }
    override suspend fun truncatePart(ref: String, length: Long) = withContext(Dispatchers.Default) {
        val target = partUrl(ref)
        if (!exists(target)) return@withContext
        if (length <= 0) { manager.removeItemAtURL(target, null); return@withContext }
        check(truncate(target.path!!, length.convert()) == 0) { "No se pudo recortar el archivo parcial" }
    }
    override suspend fun size(ref: String): Long? = withContext(Dispatchers.Default) { url(ref).let { if (exists(it)) length(it) else null } }
    override suspend fun localUri(ref: String): String? = withContext(Dispatchers.Default) { url(ref).let { if (exists(it)) it.absoluteString else null } }
}
