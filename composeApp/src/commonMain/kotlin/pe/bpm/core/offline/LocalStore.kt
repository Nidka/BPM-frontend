package pe.bpm.core.offline

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Cuenta activa en el dispositivo; la fija el flujo de sesión y la limpia el cierre de sesión. */
class ActiveScope {
    private val state = MutableStateFlow<OfflineScope?>(null)
    val flow: StateFlow<OfflineScope?> = state.asStateFlow()
    val current: OfflineScope? get() = state.value
    fun set(scope: OfflineScope?) { state.value = scope }
}

/** Señal de cambios compartida: las consultas observables se reevalúan al cambiar la base. */
class OfflineChanges {
    private val counter = MutableStateFlow(0L)
    val flow: StateFlow<Long> = counter.asStateFlow()
    fun notifyChanged() { counter.value += 1 }
}

/**
 * Fuente de verdad local de lectura. Guarda JSON por colección y clave dentro de la cuenta activa.
 * Sin cuenta activa no lee ni escribe nada.
 */
interface LocalStore {
    suspend fun get(collection: String, key: String): StoredRecord?
    suspend fun list(collection: String): List<StoredRecord>
    suspend fun put(collection: String, key: String, json: String)
    suspend fun remove(collection: String, key: String)
    suspend fun removeCollection(collection: String)
    fun observe(collection: String, key: String): Flow<StoredRecord?>
    fun observeAll(collection: String): Flow<List<StoredRecord>>
}

class DefaultLocalStore(
    private val database: OfflineDatabase,
    private val scope: ActiveScope,
    private val changes: OfflineChanges,
    private val clock: () -> Long,
) : LocalStore {
    private fun id() = scope.current?.id

    override suspend fun get(collection: String, key: String) = id()?.let { database.getRecord(it, collection, key) }
    override suspend fun list(collection: String) = id()?.let { database.listRecords(it, collection) }.orEmpty()
    override suspend fun put(collection: String, key: String, json: String) {
        val id = id() ?: return
        database.putRecord(id, collection, StoredRecord(key, json, clock()))
        changes.notifyChanged()
    }
    override suspend fun remove(collection: String, key: String) {
        val id = id() ?: return
        database.deleteRecord(id, collection, key)
        changes.notifyChanged()
    }
    override suspend fun removeCollection(collection: String) {
        val id = id() ?: return
        database.deleteCollection(id, collection)
        changes.notifyChanged()
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(collection: String, key: String): Flow<StoredRecord?> =
        combine(scope.flow, changes.flow) { _, _ -> }.mapLatest { get(collection, key) }.distinctUntilChanged()
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(collection: String): Flow<List<StoredRecord>> =
        combine(scope.flow, changes.flow) { _, _ -> }.mapLatest { list(collection) }.distinctUntilChanged()
}

/** Valor local con la marca de su última sincronización (para «Datos de hace X»). */
data class Cached<T>(val value: T, val syncedAt: Long)

val OfflineJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

suspend fun <T> LocalStore.getTyped(collection: String, key: String, serializer: KSerializer<T>): Cached<T>? =
    get(collection, key)?.let { r -> runCatching { Cached(OfflineJson.decodeFromString(serializer, r.json), r.updatedAt) }.getOrNull() }

suspend fun <T> LocalStore.putTyped(collection: String, key: String, value: T, serializer: KSerializer<T>) =
    put(collection, key, OfflineJson.encodeToString(serializer, value))

/** Estado de un recurso cache-then-network que emite la UI. */
data class CachedSnapshot<T>(
    val value: T?,
    /** Instante (ms) de la última sincronización correcta, o null si nunca hubo. */
    val syncedAt: Long?,
    val refreshing: Boolean,
    /** La última actualización desde la red falló; se muestra lo local. */
    val refreshFailed: Boolean = false,
)

/**
 * Patrón de lectura cache-then-network: emite de inmediato lo local, refresca desde la red cuando
 * hay conexión y vuelve a emitir al terminar. Sin red solo se emite lo local.
 */
class CachedResource(
    private val store: LocalStore,
    private val connectivity: ConnectivityMonitor,
) {
    fun <T> observe(
        collection: String,
        key: String,
        serializer: KSerializer<T>,
        fetch: suspend () -> T?,
    ): Flow<CachedSnapshot<T>> = channelFlow {
        val refreshing = MutableStateFlow(false)
        val failed = MutableStateFlow(false)
        launch {
            combine(store.observe(collection, key), refreshing, failed) { record, busy, bad ->
                val cached = record?.let { r ->
                    runCatching { OfflineJson.decodeFromString(serializer, r.json) }.getOrNull()?.let { it to r.updatedAt }
                }
                CachedSnapshot(cached?.first, cached?.second, busy, bad)
            }.collect { send(it) }
        }
        connectivity.online.collectLatest { online ->
            if (!online) return@collectLatest
            refreshing.value = true
            val fresh = try {
                fetch()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (fresh != null) {
                store.putTyped(collection, key, fresh, serializer)
                failed.value = false
            } else failed.value = true
            refreshing.value = false
        }
    }
}
