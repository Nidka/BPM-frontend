package pe.bpm.core.designsystem

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** Android: `TextToSpeech` del sistema en español. Nulo mientras el motor arranca o si el dispositivo no tiene voz en español. */
@Composable
actual fun rememberTextSpeaker(): TextSpeaker? {
    val context = LocalContext.current.applicationContext
    var ready by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            val tts = engine
            if (status == TextToSpeech.SUCCESS && tts != null) {
                val result = tts.setLanguage(Locale("es", "PE")).let { if (it < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale("es")) else it }
                if (result >= TextToSpeech.LANG_AVAILABLE) ready = tts
            }
        }
        onDispose { engine?.stop(); engine?.shutdown(); ready = null }
    }
    val tts = ready ?: return null
    return remember(tts) { AndroidSpeaker(tts) }
}

private class AndroidSpeaker(private val tts: TextToSpeech) : TextSpeaker {
    override fun speak(text: String, onDone: () -> Unit) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = onDone()
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = onDone()
        })
        // El motor limita el largo de una lectura; se parte en trozos de párrafo.
        text.chunked(MAX_CHARS).forEachIndexed { index, part ->
            tts.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "bpm-$index")
        }
    }
    override fun stop() { tts.stop() }

    private companion object { const val MAX_CHARS = 3500 }
}
