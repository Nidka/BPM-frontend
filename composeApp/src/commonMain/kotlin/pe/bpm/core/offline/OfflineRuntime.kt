package pe.bpm.core.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Planificador de envío en segundo plano (WorkManager en Android). */
interface BackgroundSyncScheduler {
    /** Programa un trabajo único con restricción de red; es idempotente. */
    fun schedule()
    fun cancel()
}

object NoBackgroundSync : BackgroundSyncScheduler {
    override fun schedule() = Unit
    override fun cancel() = Unit
}

/** Piezas que aporta cada plataforma; los valores por defecto son seguros para pruebas y vista previa. */
class OfflinePlatform(
    val database: OfflineDatabase = InMemoryOfflineDatabase(),
    val connectivity: ConnectivityMonitor = ManualConnectivityMonitor(true),
    /** Archivos personales (fotos pendientes): se borran al cerrar sesión o cambiar de cuenta. */
    val files: PrivateFileStore = InMemoryPrivateFileStore(),
    /**
     * Videos de capacitación descargados: almacén del dispositivo, separado de los datos personales. Cerrar
     * sesión no lo toca; se vacía con «Borrar descargas» o por el límite de espacio.
     */
    val videos: PrivateFileStore = InMemoryPrivateFileStore(),
    val scheduler: BackgroundSyncScheduler = NoBackgroundSync,
)

/** Resumen para el indicador global y el aviso «Sin conexión». */
data class OfflineStatus(
    val online: Boolean = true,
    val pending: Int = 0,
    val attention: Int = 0,
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    /** Marca de la caché más antigua mostrada desde la última lectura en línea. */
    val staleSince: Long? = null,
)

/**
 * Composición del núcleo offline de una sesión de aplicación: base, almacén local, Outbox, motor y
 * disparadores. Los hosts nativos la crean también en segundo plano (WorkManager).
 */
class OfflineRuntime(
    val platform: OfflinePlatform,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val newId: () -> String = { randomId() },
) {
    val active = ActiveScope()
    val changes = OfflineChanges()
    val connectivity: ConnectivityMonitor get() = platform.connectivity
    val store: LocalStore = DefaultLocalStore(platform.database, active, changes, clock)
    private val queue = DefaultOutbox(platform.database, active, changes, clock)
    val outbox: Outbox get() = queue

    /** Manejadores por tipo de operación; las features registran el suyo al componerse. */
    private val handlers = mutableMapOf<String, OutboxHandler>()
    val engine: SyncEngine = DefaultSyncEngine(queue, handlers, platform.connectivity, clock)
    val http = OfflineHttpRuntime(active, store, queue, platform.connectivity, clock, newId)

    fun register(kind: String, handler: OutboxHandler) { handlers[kind] = handler }

    private val beforeSync = mutableListOf<suspend () -> Unit>()
    private val wipeHooks = mutableListOf<suspend () -> Unit>()

    /** Trabajo previo a cada envío (p. ej. migrar formatos antiguos a la Outbox). Nunca bloquea el envío si falla. */
    fun beforeSync(block: suspend () -> Unit) { beforeSync += block }

    /** Limpieza adicional en el cierre de sesión explícito (p. ej. borradores heredados). */
    fun onWipe(block: suspend () -> Unit) { wipeHooks += block }

    val status: Flow<OfflineStatus> = combine(
        platform.connectivity.online, outbox.pendingCount, outbox.attentionCount, engine.state, http.staleSince,
    ) { online, pending, attention, sync, stale -> OfflineStatus(online, pending, attention, sync.running, sync.lastSuccessAt, stale) }

    /**
     * Fija la cuenta activa. La base está particionada por cuenta: la partición de otra cuenta se conserva
     * intacta mientras tenga cambios sin enviar (pendientes, en envío, en conflicto o rechazados) y no haya
     * pasado [OfflinePolicy.OTHER_ACCOUNT_RETENTION_MS] desde su última actividad; las demás se borran. La cuenta
     * nueva nunca ve ni envía la cola de otra: el motor trabaja solo con la partición activa.
     */
    suspend fun activate(scope: OfflineScope) {
        val hadOthers = platform.database.scopes().any { it != scope.id }
        val retained = prunePartitions(except = scope.id)
        // Los archivos personales (fotos pendientes) no están separados por cuenta: solo se borran si nada se conserva.
        if (hadOthers && !retained) platform.files.deleteAll()
        active.set(scope)
        changes.notifyChanged()
    }

    /**
     * Borra las particiones sin cambios pendientes o vencidas (distintas de [except]) y devuelve true si se
     * conserva alguna con cambios sin enviar.
     */
    private suspend fun prunePartitions(except: String?): Boolean {
        var retained = false
        for (id in platform.database.scopes().filter { it != except }) {
            val unresolved = platform.database.operations(id).filter { !it.resolved }
            val lastActivity = unresolved.maxOfOrNull { maxOf(it.updatedAt, it.clientCreatedAt) }
            if (lastActivity != null && clock() - lastActivity <= OfflinePolicy.OTHER_ACCOUNT_RETENTION_MS) retained = true
            else platform.database.clear(id)
        }
        return retained
    }

    /**
     * Cambios sin enviar de otras cuentas guardados en este dispositivo (nunca se envían con la cuenta activa).
     * Solo expone el recuento y el correo enmascarado.
     */
    suspend fun otherAccountsPending(): List<OtherAccountPending> {
        val current = active.current?.id
        return platform.database.scopes().filter { it != current }.mapNotNull { id ->
            val unresolved = platform.database.operations(id).filter { !it.resolved }
            if (unresolved.isEmpty()) return@mapNotNull null
            val email = platform.database.getRecord(id, RuntimeOfflineIdentity.PROFILE_COLLECTION, RuntimeOfflineIdentity.PROFILE_KEY)
                ?.let { runCatching { Json.parseToJsonElement(it.json).jsonObject["email"]?.jsonPrimitive?.content }.getOrNull() }
            OtherAccountPending(unresolved.size, email?.let(::maskEmail))
        }
    }

    /** Cambios sin enviar de otras cuentas, reevaluados cuando cambia la cuenta activa o la base. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val otherPending: Flow<List<OtherAccountPending>> =
        combine(active.flow, changes.flow) { _, _ -> }.mapLatest { otherAccountsPending() }.distinctUntilChanged()

    /** Oculta los datos sin borrarlos (sesión vencida): la misma cuenta los recupera al volver a entrar. */
    fun deactivate() { active.set(null); changes.notifyChanged() }

    /**
     * Cierre de sesión explícito: elimina los datos personales de este dispositivo. La interfaz solo llega aquí sin
     * cambios por enviar; las particiones de otras cuentas con cambios sin enviar se conservan (ver [activate]).
     */
    suspend fun wipe() {
        active.set(null)
        val retained = prunePartitions(except = null)
        if (!retained) platform.files.deleteAll()
        platform.scheduler.cancel()
        wipeHooks.forEach { hook -> try { hook() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* mejor esfuerzo */ } }
        changes.notifyChanged()
    }

    /**
     * Arranca los disparadores mientras la aplicación está abierta: recuperación de red, cambios en la
     * cola y ticker periódico. El primer plano y el botón «Sincronizar ahora» llaman a [syncNow].
     */
    fun start(scope: CoroutineScope, periodMillis: Long = 60_000) {
        scope.launch {
            var previous = false
            platform.connectivity.online.collect { online ->
                if (online && !previous) syncNow(ignoreBackoff = true)
                previous = online
            }
        }
        scope.launch {
            outbox.pendingCount.collectLatest { pending ->
                if (pending > 0) {
                    platform.scheduler.schedule()
                    if (platform.connectivity.online.value) { delay(2_000); syncNow(ignoreBackoff = false) }
                }
            }
        }
        scope.launch {
            while (true) {
                delay(periodMillis)
                if (platform.connectivity.online.value && active.current != null) syncNow(ignoreBackoff = false)
            }
        }
    }

    suspend fun syncNow(ignoreBackoff: Boolean = true): SyncReport {
        if (active.current != null) beforeSync.forEach { hook ->
            try { hook() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* se reintenta en la próxima tanda */ }
        }
        return engine.syncNow(ignoreBackoff)
    }

    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun randomId(): String = Uuid.random().toString()
    }
}

/** Límites de conservación de datos de otras cuentas en un celular compartido. */
object OfflinePolicy {
    /** Una partición con cambios sin enviar de otra cuenta se conserva como máximo 30 días desde su última actividad. */
    const val OTHER_ACCOUNT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
}

/** Cambios sin enviar de otra cuenta del dispositivo; [maskedEmail] es nulo si no se conoce su perfil. */
data class OtherAccountPending(val count: Int, val maskedEmail: String?) {
    /** Aviso discreto, sin datos personales salvo el correo enmascarado. */
    fun notice(): String =
        "Hay $count ${if (count == 1) "cambio" else "cambios"} sin enviar de otra persona" +
            (maskedEmail?.let { " ($it)" } ?: "") + ". Se enviarán cuando esa persona vuelva a entrar"
}

/** `katherine@gmail.com` pasa a `ka•••@gmail.com`: dos letras del usuario (una si es corto) y el dominio. */
fun maskEmail(email: String): String {
    val local = email.substringBefore('@')
    val domain = email.substringAfter('@', "")
    if (domain.isEmpty()) return "•••"
    return local.take(if (local.length > 2) 2 else 1) + "•••@" + domain
}

/** Puente entre el flujo de sesión y los datos locales; identidad no conoce el almacenamiento. */
interface OfflineIdentityBinding {
    /** Fija la cuenta; si había datos de otra cuenta en el dispositivo, se borran. */
    suspend fun activate(userId: String)
    /** Oculta los datos sin borrarlos (sesión vencida o cambio de cuenta en curso). */
    fun deactivate()
    /** Cierre de sesión explícito: borra base local, archivos y trabajo en segundo plano. */
    suspend fun wipe()
    suspend fun saveProfile(json: String)
    suspend fun loadProfile(): StoredRecord?
}

class RuntimeOfflineIdentity(private val runtime: OfflineRuntime, private val origin: String) : OfflineIdentityBinding {
    override suspend fun activate(userId: String) = runtime.activate(OfflineScope(origin, userId))
    override fun deactivate() = runtime.deactivate()
    override suspend fun wipe() = runtime.wipe()
    override suspend fun saveProfile(json: String) = runtime.store.put(PROFILE_COLLECTION, PROFILE_KEY, json)
    override suspend fun loadProfile() = runtime.store.get(PROFILE_COLLECTION, PROFILE_KEY)

    companion object {
        const val PROFILE_COLLECTION = "profile"
        const val PROFILE_KEY = "me"
        /** Máximo de días que un dispositivo puede abrir sin conexión con el último perfil verificado. */
        const val OFFLINE_SESSION_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}
