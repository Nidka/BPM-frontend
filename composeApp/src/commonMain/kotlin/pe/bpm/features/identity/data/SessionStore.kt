package pe.bpm.features.identity.data

/** Almacena únicamente una credencial de renovación; las implementaciones no deben registrar su contenido. */
interface SessionStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** En navegador y JVM no almacena credenciales ni usa cookies; la sesión no persiste al reiniciar la aplicación. */
class EphemeralSessionStore : SessionStore {
    override fun read(): String? = null
    override fun write(value: String) = Unit
    override fun clear() = Unit
}
