package pe.bpm.core.storage

/** Doble de disco compartido por varias instancias de repositorio y ViewModel. */
class TestDraftStorage : DraftStorage {
    val values = mutableMapOf<String, String>()
    var failWrites = false
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { check(!failWrites); values[key] = value }
    override suspend fun compareAndSet(key: String, expected: String?, value: String): Boolean {
        check(!failWrites)
        if (values[key] != expected) return false
        values[key] = value; return true
    }
    override suspend fun remove(key: String) { check(!failWrites); values.remove(key) }
}
