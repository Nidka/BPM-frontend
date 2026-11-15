package pe.bpm.core.offline

/** Archivo guardado en el almacén privado; [partial] es una descarga sin terminar. */
data class StoredFileInfo(val ref: String, val sizeBytes: Long, val partial: Boolean)

/** Referencias válidas: identificador local con extensión opcional; nunca rutas ni URL. */
val PRIVATE_FILE_REF = Regex("[A-Za-z0-9_-]{1,100}([.][A-Za-z0-9]{1,5})?")

/**
 * Archivos privados de la aplicación (fotos pendientes, videos descargados). Nunca contiene URL
 * firmadas. Los archivos grandes se escriben por bloques en una parte provisional ([appendPart]) que
 * se publica de forma atómica con [commitPart]: así no se carga un video completo en memoria y una
 * descarga cortada se puede reanudar desde [partialSize].
 */
interface PrivateFileStore {
    /** false cuando el host no puede conservar archivos entre reinicios (p. ej. navegador). */
    val durable: Boolean
    suspend fun save(ref: String, bytes: ByteArray)
    suspend fun read(ref: String): ByteArray?
    suspend fun delete(ref: String)
    suspend fun deleteAll()

    /** true si el host implementa la escritura por bloques (descarga de videos). */
    val supportsStreaming: Boolean get() = false
    /** Archivos completos y partes provisionales con su tamaño. */
    suspend fun list(): List<StoredFileInfo> = emptyList()
    /** Bytes ya escritos en la parte provisional de [ref]; 0 si no existe. */
    suspend fun partialSize(ref: String): Long = 0L
    /** Añade [length] bytes de [bytes] a la parte provisional. */
    suspend fun appendPart(ref: String, bytes: ByteArray, length: Int): Unit = throw UnsupportedOperationException("Sin escritura por bloques")
    /** Lee de la parte provisional desde [offset]; devuelve los bytes leídos o -1 al terminar. */
    suspend fun readPart(ref: String, offset: Long, buffer: ByteArray): Int = throw UnsupportedOperationException("Sin escritura por bloques")
    /** Publica la parte provisional como archivo final. */
    suspend fun commitPart(ref: String): Unit = throw UnsupportedOperationException("Sin escritura por bloques")
    suspend fun discardPart(ref: String) = Unit
    /** Tamaño del archivo final, o null si no existe. */
    suspend fun size(ref: String): Long? = null
    /** Recorta la parte provisional a [length] bytes (descarta lo que no se pudo confirmar). */
    suspend fun truncatePart(ref: String, length: Long) { if (length <= 0L) discardPart(ref) }
    /** URI `file://` del archivo final para el reproductor nativo; null si el host no la ofrece. */
    suspend fun localUri(ref: String): String? = null
    /**
     * Mueve el archivo final [ref] al almacén [target] con el nombre [targetRef] sin cargarlo en memoria.
     * Devuelve false si el origen no existe o los almacenes no son del mismo tipo (no se mueve nada).
     */
    suspend fun moveTo(ref: String, target: PrivateFileStore, targetRef: String): Boolean = false
}

class InMemoryPrivateFileStore(override val durable: Boolean = false) : PrivateFileStore {
    private val files = HashMap<String, ByteArray>()
    private val parts = HashMap<String, ByteArray>()
    override suspend fun save(ref: String, bytes: ByteArray) { files[ref] = bytes }
    override suspend fun read(ref: String) = files[ref]
    override suspend fun delete(ref: String) { files.remove(ref); parts.remove(ref) }
    override suspend fun deleteAll() { files.clear(); parts.clear() }

    override val supportsStreaming get() = true
    override suspend fun list() = files.map { StoredFileInfo(it.key, it.value.size.toLong(), false) } +
        parts.map { StoredFileInfo(it.key, it.value.size.toLong(), true) }
    override suspend fun partialSize(ref: String) = parts[ref]?.size?.toLong() ?: 0L
    override suspend fun appendPart(ref: String, bytes: ByteArray, length: Int) {
        parts[ref] = (parts[ref] ?: ByteArray(0)) + bytes.copyOf(length)
    }
    override suspend fun readPart(ref: String, offset: Long, buffer: ByteArray): Int {
        val part = parts[ref] ?: return -1
        if (offset >= part.size) return -1
        val count = minOf(buffer.size, part.size - offset.toInt())
        part.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
        return count
    }
    override suspend fun commitPart(ref: String) { files[ref] = checkNotNull(parts.remove(ref)) }
    override suspend fun discardPart(ref: String) { parts.remove(ref) }
    override suspend fun truncatePart(ref: String, length: Long) {
        val part = parts[ref] ?: return
        if (length <= 0) parts.remove(ref) else if (length < part.size) parts[ref] = part.copyOf(length.toInt())
    }
    override suspend fun size(ref: String) = files[ref]?.size?.toLong()
    override suspend fun moveTo(ref: String, target: PrivateFileStore, targetRef: String): Boolean {
        if (target !is InMemoryPrivateFileStore || target === this) return false
        val bytes = files.remove(ref) ?: return false
        target.files[targetRef] = bytes
        return true
    }
}
