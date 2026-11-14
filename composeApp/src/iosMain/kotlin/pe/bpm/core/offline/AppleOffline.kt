@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.ExperimentalUnsignedTypes::class, kotlinx.cinterop.BetaInteropApi::class)

package pe.bpm.core.offline

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlinx.cinterop.addressOf
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

/** Fotos pendientes en Application Support, excluidas de copia de seguridad y con protección completa. */
class ApplePrivateFileStore : PrivateFileStore {
    override val durable = true
    private fun directory(): NSURL {
        val base = NSFileManager.defaultManager.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)!!
        val directory = base.URLByAppendingPathComponent("bpm-offline-files-v1", true)!!
        check(NSFileManager.defaultManager.createDirectoryAtURL(directory, true, null, null))
        check(directory.setResourceValue(NSNumber.numberWithBool(true), NSURLIsExcludedFromBackupKey, null))
        return directory
    }
    private fun url(ref: String): NSURL {
        require(ref.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return directory().URLByAppendingPathComponent(ref)!!
    }
    override suspend fun save(ref: String, bytes: ByteArray) = withContext(Dispatchers.Default) {
        require(bytes.isNotEmpty())
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToURL(url(ref), NSDataWritingAtomic or NSDataWritingFileProtectionComplete, null))
    }
    override suspend fun read(ref: String): ByteArray? = withContext(Dispatchers.Default) {
        val target = url(ref)
        if (!NSFileManager.defaultManager.fileExistsAtPath(target.path!!)) null
        else NSData.dataWithContentsOfURL(target)?.let { it.bytes!!.readBytes(it.length.toInt()) }
    }
    override suspend fun delete(ref: String) = withContext(Dispatchers.Default) {
        val target = url(ref)
        if (NSFileManager.defaultManager.fileExistsAtPath(target.path!!)) NSFileManager.defaultManager.removeItemAtURL(target, null)
        Unit
    }
    override suspend fun deleteAll() = withContext(Dispatchers.Default) {
        val target = directory()
        if (NSFileManager.defaultManager.fileExistsAtPath(target.path!!)) NSFileManager.defaultManager.removeItemAtURL(target, null)
        Unit
    }
}
