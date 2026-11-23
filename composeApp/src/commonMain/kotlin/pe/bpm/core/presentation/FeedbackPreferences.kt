package pe.bpm.core.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.designsystem.BpmFeedbackSettings
import pe.bpm.core.offline.LocalStore

/**
 * Preferencia local de sonido y vibración (interruptores de Perfil). Vive en memoria y se guarda en el `LocalStore` de
 * la cuenta activa (colección `preferences`): no es un token ni un dato de negocio, no se envía al servidor y se
 * borra con el cierre de sesión como el resto de lo local. Sin almacén (o sin cuenta activa) sigue funcionando solo
 * en memoria.
 */
class FeedbackPreferences(private val store: LocalStore? = null) {
    private val mutable = MutableStateFlow(BpmFeedbackSettings())
    val state: StateFlow<BpmFeedbackSettings> = mutable.asStateFlow()

    /** Lee lo guardado para la cuenta activa; sin registro vuelve a los valores por defecto (todo activo). */
    suspend fun load() {
        val saved = runCatching { store?.get(COLLECTION, KEY)?.json }.getOrNull()
        mutable.value = saved?.let(::decodeFeedbackSettings) ?: BpmFeedbackSettings()
    }

    suspend fun setSound(on: Boolean) = update(mutable.value.copy(sound = on))

    suspend fun setVibration(on: Boolean) = update(mutable.value.copy(vibration = on))

    /** Guarda de una vez las preferencias de Accesibilidad (tamaño del texto, alto contraste y «Escuchar»). */
    suspend fun setAccessibility(textScale: Float, highContrast: Boolean, readAloud: Boolean) =
        update(mutable.value.copy(textScale = textScale.coerceIn(1f, 1.3f), highContrast = highContrast, readAloud = readAloud))

    /** Guarda las preferencias completas (pantalla «Ajustes»); acota el tamaño del texto al rango válido. */
    suspend fun save(next: BpmFeedbackSettings) = update(next.copy(textScale = next.textScale.coerceIn(1f, 1.3f)))

    private suspend fun update(next: BpmFeedbackSettings) {
        mutable.value = next
        runCatching { store?.put(COLLECTION, KEY, encodeFeedbackSettings(next)) }
    }

    private companion object {
        const val COLLECTION = "preferences"
        const val KEY = "feedback"
    }
}

/** JSON guardado de las preferencias: `{"sound":true,"vibration":false,"textScale":1.15,"highContrast":false,"readAloud":true}`. */
internal fun encodeFeedbackSettings(settings: BpmFeedbackSettings): String =
    """{"sound":${settings.sound},"vibration":${settings.vibration},"textScale":${settings.textScale},"highContrast":${settings.highContrast},"readAloud":${settings.readAloud},"reduceMotion":${settings.reduceMotion},"captions":${settings.captions},"transcript":${settings.transcript}}"""

/** Lee el JSON guardado; un campo ausente o un texto dañado valen «activo» y nunca lanzan. */
internal fun decodeFeedbackSettings(json: String): BpmFeedbackSettings = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    BpmFeedbackSettings(
        sound = root["sound"]?.jsonPrimitive?.booleanOrNull ?: true,
        vibration = root["vibration"]?.jsonPrimitive?.booleanOrNull ?: true,
        textScale = (root["textScale"]?.jsonPrimitive?.floatOrNull ?: 1f).coerceIn(1f, 1.3f),
        highContrast = root["highContrast"]?.jsonPrimitive?.booleanOrNull ?: false,
        readAloud = root["readAloud"]?.jsonPrimitive?.booleanOrNull ?: true,
        reduceMotion = root["reduceMotion"]?.jsonPrimitive?.booleanOrNull ?: false,
        captions = root["captions"]?.jsonPrimitive?.booleanOrNull ?: true,
        transcript = root["transcript"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}.getOrDefault(BpmFeedbackSettings())
