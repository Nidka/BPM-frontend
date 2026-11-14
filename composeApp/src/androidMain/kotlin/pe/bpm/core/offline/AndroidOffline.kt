package pe.bpm.core.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import pe.bpm.core.offline.sql.OfflineDb

/**
 * Piezas offline de Android compartidas por la Activity y el trabajo en segundo plano. La base
 * SQLite vive en el almacenamiento privado de la aplicación (sin copia de seguridad: allowBackup=false).
 */
object AndroidOffline {
    @Volatile private var instance: OfflinePlatform? = null

    fun platform(context: Context): OfflinePlatform = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context) = OfflinePlatform(
        database = SqlOfflineDatabase(AndroidSqliteDriver(OfflineDb.Schema, app, "bpm-offline.db")),
        connectivity = AndroidConnectivityMonitor(app),
        files = AndroidPrivateFileStore(File(app.noBackupFilesDir, "offline-files")),
        scheduler = AndroidBackgroundSync(app),
    )
}

/**
 * Observa la red por defecto con ConnectivityManager. Se considera conectado con la capacidad
 * INTERNET (no se exige VALIDATED): un falso positivo solo provoca un intento fallido que ya se
 * gestiona, mientras que un falso negativo impediría usar la red disponible.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val state = MutableStateFlow(currentlyOnline())
    override val online: StateFlow<Boolean> = state.asStateFlow()

    private fun currentlyOnline(): Boolean {
        val network = manager?.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    init {
        try {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { state.value = true }
                override fun onLost(network: Network) { state.value = currentlyOnline() }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        } catch (_: SecurityException) {
            // Sin permiso de estado de red se asume conexión y los errores reales dirigen el reintento.
            state.value = true
        }
    }
}

/** Fotos pendientes en archivos privados sin copia de seguridad; el nombre es un identificador local. */
class AndroidPrivateFileStore(private val directory: File) : PrivateFileStore {
    override val durable = true
    private fun file(ref: String): File {
        require(ref.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(directory, ref)
    }
    override suspend fun save(ref: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val target = file(ref)
        val temporary = File(directory, "$ref.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
    }
    override suspend fun read(ref: String) = withContext(Dispatchers.IO) { file(ref).takeIf { it.isFile }?.readBytes() }
    override suspend fun delete(ref: String) { withContext(Dispatchers.IO) { file(ref).delete() } }
    override suspend fun deleteAll() { withContext(Dispatchers.IO) { directory.deleteRecursively() } }
}

/** Trabajo único con restricción de red y backoff exponencial: envía la Outbox con la app cerrada. */
class AndroidBackgroundSync(private val context: Context) : BackgroundSyncScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<pe.bpm.app.OfflineSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
    override fun cancel() { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
    companion object { const val WORK_NAME = "bpm-outbox-sync" }
}
