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
import pe.bpm.core.offline.LocalStore

/** «Primeros pasos» de una cuenta: si ya vio la introducción, qué pasos marcó y si ocultó la lista. */
data class OnboardingState(
    val loaded: Boolean = false,
    val introSeen: Boolean = false,
    val done: Set<String> = emptySet(),
    val hidden: Boolean = false,
)

/**
 * Guarda el avance de «Primeros pasos» en el `LocalStore` de la cuenta activa (colección `preferences`, clave `onboarding`): es
 * por persona **en este dispositivo** (no se envía al servidor; una cuenta nueva en otro teléfono la vuelve a ver, y se puede
 * repetir desde «Ayuda»). Sin almacén sigue funcionando en memoria.
 */
class OnboardingPreferences(private val store: LocalStore? = null) {
    private val mutable = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = mutable.asStateFlow()

    suspend fun load() {
        val saved = runCatching { store?.get(COLLECTION, KEY)?.json }.getOrNull()
        mutable.value = (saved?.let(::decodeOnboarding) ?: OnboardingState()).copy(loaded = true)
    }

    suspend fun markIntroSeen() = update(mutable.value.copy(introSeen = true))

    /** «Ver primeros pasos otra vez»: la introducción vuelve a mostrarse y la lista reaparece. */
    suspend fun replay() = update(mutable.value.copy(introSeen = false, hidden = false))

    suspend fun markStep(id: String) { if (id !in mutable.value.done) update(mutable.value.copy(done = mutable.value.done + id)) }

    suspend fun hide(hidden: Boolean) = update(mutable.value.copy(hidden = hidden))

    fun reset() { mutable.value = OnboardingState() }

    private suspend fun update(next: OnboardingState) {
        mutable.value = next
        runCatching { store?.put(COLLECTION, KEY, encodeOnboarding(next)) }
    }

    private companion object {
        const val COLLECTION = "preferences"
        const val KEY = "onboarding"
    }
}

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
