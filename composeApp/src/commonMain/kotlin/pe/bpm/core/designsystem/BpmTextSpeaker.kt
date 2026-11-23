package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Lectura en voz alta («Escuchar»). Cada plataforma usa su síntesis de voz del sistema (Android: `TextToSpeech`; iOS:
 * `AVSpeechSynthesizer`; web: `speechSynthesis`) en español. Sin síntesis disponible [rememberTextSpeaker] devuelve nulo y
 * los botones «Escuchar» no se muestran.
 */
interface TextSpeaker {
    /** Lee [text] (corta lo que estuviera leyendo). [onDone] se llama al terminar o al detenerse. */
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
}

/** El lector de voz de la plataforma, o nulo si no existe. Se libera al salir de la composición. */
@Composable
expect fun rememberTextSpeaker(): TextSpeaker?

/**
 * Botón «Escuchar» / «Detener» para un texto. No se muestra si la plataforma no tiene síntesis de voz o si la persona
 * apagó «Escuchar» en Accesibilidad ([LocalBpmFeedbackSettings]). Lee [text] tal cual; [label] describe qué se lee.
 */
@Composable
fun BpmListenButton(text: String, modifier: Modifier = Modifier, label: String = "Escuchar") {
    val settings = LocalBpmFeedbackSettings.current
    val speaker = rememberTextSpeaker()
    if (speaker == null || !settings.readAloud || text.isBlank()) return
    var speaking by remember(text) { mutableStateOf(false) }
    DisposableEffect(text) { onDispose { if (speaking) speaker.stop() } }
    BpmChunkyButton(
        if (speaking) "Detener" else label,
        { if (speaking) { speaker.stop(); speaking = false } else { speaking = true; speaker.speak(text) { speaking = false } } },
        modifier.widthIn(min = 160.dp).heightIn(min = BpmSpacing.touch), tone = BpmChunkyTone.White,
        icon = if (speaking) BpmSymbol.Close else BpmSymbol.Play,
    )
}
