package pe.bpm.core.offline

/**
 * Datos del dispositivo que sobreviven al cierre de sesión: una partición reservada de la base offline que nunca se poda ni
 * se borra al salir. Solo para marcas no sensibles por persona (p. ej. «ya vio la introducción»); nunca tokens ni datos de
 * negocio. Si el almacenamiento falla se comporta como vacío y nunca lanza.
 */
class DeviceStore(private val database: OfflineDatabase, private val clock: () -> Long) {
    suspend fun get(collection: String, key: String): String? =
        try { database.getRecord(SCOPE, collection, key)?.json } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { null }

    suspend fun put(collection: String, key: String, json: String) {
        try { database.putRecord(SCOPE, collection, StoredRecord(key, json, clock())) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* caché opcional */ }
    }

    suspend fun remove(collection: String, key: String) {
        try { database.deleteRecord(SCOPE, collection, key) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* caché opcional */ }
    }

    companion object {
        /** Partición reservada; no coincide con ningún [OfflineScope.id] (esos empiezan por la longitud del origen). */
        const val SCOPE = "__device"
        /** Prefijo de sus documentos en el almacenamiento web (ver DocumentOfflineDatabase); el borrado web lo conserva. */
        const val DOCUMENT_PREFIX = "offline-r/8:__device/"
    }
}
