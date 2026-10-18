package pe.bpm.features.identity.data

/** Stores a refresh credential only. Implementations must never log its contents. */
interface SessionStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** Browser and JVM: no storage, cookies or persistence across application restarts. */
class EphemeralSessionStore : SessionStore {
    override fun read(): String? = null
    override fun write(value: String) = Unit
    override fun clear() = Unit
}
