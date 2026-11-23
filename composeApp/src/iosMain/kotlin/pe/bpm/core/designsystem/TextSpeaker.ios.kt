package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.darwin.NSObject

/** iOS: `AVSpeechSynthesizer` con voz en español. */
@Composable
actual fun rememberTextSpeaker(): TextSpeaker? {
    val speaker = remember { IosSpeaker() }
    DisposableEffect(speaker) { onDispose { speaker.stop() } }
    return speaker
}

/** Avisa al terminar o cancelarse una lectura (el delegado de Objective-C no puede ser también la interfaz de Kotlin). */
private class SpeechDelegate : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    var done: () -> Unit = {}

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) = done()
    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) = done()
}

@OptIn(ExperimentalForeignApi::class)
private class IosSpeaker : TextSpeaker {
    private val synthesizer = AVSpeechSynthesizer()
    private val delegate = SpeechDelegate()

    init { synthesizer.delegate = delegate }

    override fun speak(text: String, onDone: () -> Unit) {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        delegate.done = onDone
        val utterance = AVSpeechUtterance(string = text)
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage("es-MX") ?: AVSpeechSynthesisVoice.voiceWithLanguage("es-ES")
        synthesizer.speakUtterance(utterance)
    }

    override fun stop() { synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
}
