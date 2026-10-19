package pe.bpm.core.storage

/** Doble de disco compartido por varias instancias de repositorio y ViewModel. */
class TestDraftStorage : DraftStorage {
    val values = mutableMapOf<String, String>()
    var failWrites = false
    override suspend fun read(key: String) = values[key]
    override suspend fun write(key: String, value: String) { check(!failWrites); values[key] = value }
    override suspend fun remove(key: String) { check(!failWrites); values.remove(key) }
}
