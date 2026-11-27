package pe.bpm.core.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.offline.DeviceStore
import pe.bpm.core.offline.LocalStore

/** «Primeros pasos» de una cuenta: si ya vio la introducción, qué pasos marcó y si ocultó la lista. */
data class OnboardingState(
    val loaded: Boolean = false,
    val introSeen: Boolean = false,
    val done: Set<String> = emptySet(),
    val hidden: Boolean = false,
)

/**
 * Guarda el avance de «Primeros pasos». Los pasos y la lista viven en el `LocalStore` de la cuenta activa (colección
 * `preferences`, clave `onboarding`), que se borra al cerrar sesión. Que ya vio la introducción se guarda además:
 * - en el servidor ([OnboardingSync], `PUT`/`DELETE /me/onboarding`), para no repetirla en otro teléfono o navegador;
 * - en el dispositivo ([DeviceStore], por id de persona), que no se borra al cerrar sesión y sirve sin conexión.
 * La introducción solo sale si ninguno de los tres la marca como vista ([introAlreadySeen]). Sin almacenes sigue en memoria.
 */
class OnboardingPreferences(
    private val store: LocalStore? = null,
    private val device: DeviceStore? = null,
    private val sync: OnboardingSync? = null,
) {
    private val mutable = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = mutable.asStateFlow()
    private var userId: String? = null

    /**
     * Carga el avance de [userId]. [serverSeen] viene del perfil (`onboardingSeenAt`). Si este dispositivo ya la marcó como
     * vista y el servidor aún no (p. ej. se marcó sin conexión), se sube la marca.
     */
    suspend fun load(userId: String? = null, serverSeen: Boolean = false) {
        this.userId = userId
        val saved = runCatching { store?.get(COLLECTION, KEY)?.json }.getOrNull()?.let(::decodeOnboarding) ?: OnboardingState()
        val deviceSeen = userId != null && device?.get(DEVICE_COLLECTION, userId) == SEEN
        val seen = introAlreadySeen(serverSeen, saved.introSeen, deviceSeen)
        mutable.value = saved.copy(introSeen = seen, loaded = true)
        if (seen && !saved.introSeen) runCatching { store?.put(COLLECTION, KEY, encodeOnboarding(mutable.value)) }
        if (seen && !deviceSeen && userId != null) device?.put(DEVICE_COLLECTION, userId, SEEN)
        if (seen && !serverSeen) runCatching { sync?.setSeen(true) }
    }

    /** Terminó u omitió («Saltar») la introducción: se marca aquí, en el dispositivo y en el servidor. */
    suspend fun markIntroSeen() {
        update(mutable.value.copy(introSeen = true))
        userId?.let { device?.put(DEVICE_COLLECTION, it, SEEN) }
        runCatching { sync?.setSeen(true) }
    }

    /** «Ver primeros pasos otra vez»: la introducción vuelve a mostrarse y la lista reaparece (también en otros dispositivos). */
    suspend fun replay() {
        update(mutable.value.copy(introSeen = false, hidden = false))
        userId?.let { device?.remove(DEVICE_COLLECTION, it) }
        runCatching { sync?.setSeen(false) }
    }

    suspend fun markStep(id: String) { if (id !in mutable.value.done) update(mutable.value.copy(done = mutable.value.done + id)) }

    suspend fun hide(hidden: Boolean) = update(mutable.value.copy(hidden = hidden))

    fun reset() { mutable.value = OnboardingState(); userId = null }

    private suspend fun update(next: OnboardingState) {
        mutable.value = next
        runCatching { store?.put(COLLECTION, KEY, encodeOnboarding(next)) }
    }

    private companion object {
        const val COLLECTION = "preferences"
        const val KEY = "onboarding"
        const val DEVICE_COLLECTION = "onboarding-seen"
        const val SEEN = "true"
    }
}

/** La introducción ya se vio si lo dice el servidor, la caché de la cuenta o la del dispositivo. */
internal fun introAlreadySeen(serverSeen: Boolean, accountSeen: Boolean, deviceSeen: Boolean): Boolean = serverSeen || accountSeen || deviceSeen

internal fun encodeOnboarding(state: OnboardingState): String =
    JsonObject(mapOf("intro" to JsonPrimitive(state.introSeen), "hidden" to JsonPrimitive(state.hidden),
        "done" to JsonArray(state.done.sorted().map { JsonPrimitive(it) }))).toString()

/** Lee lo guardado; un campo ausente o un texto dañado valen «nada visto» y nunca lanzan. */
internal fun decodeOnboarding(json: String): OnboardingState = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    OnboardingState(
        introSeen = root["intro"]?.jsonPrimitive?.booleanOrNull ?: false,
        hidden = root["hidden"]?.jsonPrimitive?.booleanOrNull ?: false,
        done = root["done"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
    )
}.getOrDefault(OnboardingState())
