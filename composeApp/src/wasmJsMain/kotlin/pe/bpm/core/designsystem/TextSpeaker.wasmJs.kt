@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/** Web: `speechSynthesis` del navegador (voz en español si existe; si no, la predeterminada). Nulo si el navegador no la tiene. */
@Composable
actual fun rememberTextSpeaker(): TextSpeaker? {
    val speaker = remember { if (speechSupported()) BrowserSpeaker() else null }
    DisposableEffect(speaker) { onDispose { speaker?.stop() } }
    return speaker
}

private class BrowserSpeaker : TextSpeaker {
    override fun speak(text: String, onDone: () -> Unit) = speakText(text, onDone)
    override fun stop() = cancelSpeech()
}

@JsFun("() => typeof window !== 'undefined' && 'speechSynthesis' in window && typeof SpeechSynthesisUtterance !== 'undefined'")
private external fun speechSupported(): Boolean

@JsFun("""(text, done) => {
  const synth = window.speechSynthesis;
  synth.cancel();
  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = 'es-PE';
  const voice = synth.getVoices().find(v => v.lang && v.lang.toLowerCase().startsWith('es'));
  if (voice) utterance.voice = voice;
  utterance.onend = () => done();
  utterance.onerror = () => done();
  synth.speak(utterance);
}""")
private external fun speakText(text: String, done: () -> Unit)

@JsFun("() => { if ('speechSynthesis' in window) window.speechSynthesis.cancel(); }")
private external fun cancelSpeech()
