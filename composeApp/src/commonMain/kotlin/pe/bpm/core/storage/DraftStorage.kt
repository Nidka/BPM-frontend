package pe.bpm.core.storage

/** Almacenamiento atómico de documentos locales. No almacena credenciales ni concede permisos. */
interface DraftStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}

const val MAX_DRAFT_BYTES = 1_048_576

/** Solo para hosts sin adaptador: comunica el fallo, nunca aparenta persistencia. */
class UnavailableDraftStorage : DraftStorage {
    override suspend fun read(key: String): String? = error("Almacenamiento local no disponible")
    override suspend fun write(key: String, value: String): Unit = error("Almacenamiento local no disponible")
    override suspend fun remove(key: String): Unit = error("Almacenamiento local no disponible")
}

/** Longitudes explícitas impiden colisiones entre cuentas, empresas y entornos. */
fun draftKey(origin: String, user: String, organization: String, document: String): String =
    listOf(origin, user, organization, document).joinToString("") { "${it.length}:$it" }
