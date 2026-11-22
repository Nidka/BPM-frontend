@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Web: los tonos se generan con WebAudio (osciladores de onda seno con envolvente corta; sin archivos de audio) y la
 * vibración usa `navigator.vibrate` cuando el navegador la ofrece. Los navegadores solo dejan sonar tras un gesto, y
 * estos efectos siempre siguen a un toque o a la tecla Enter.
 */
@Composable
internal actual fun rememberPlatformFeedback(): BpmPlatformFeedback = remember { WebFeedback }

private object WebFeedback : BpmPlatformFeedback {
    override fun play(kind: BpmFeedbackKind, sound: Boolean, vibration: Boolean) {
        if (vibration) browserVibrate(when (kind) { BpmFeedbackKind.Success -> 20; BpmFeedbackKind.Error -> 60; BpmFeedbackKind.Celebrate -> 40 })
        if (sound) browserTones(kind.ordinal)
    }
}

@JsFun("(ms) => { try { if (navigator.vibrate) navigator.vibrate(ms); } catch (e) {} }")
private external fun browserVibrate(ms: Int)

// kind: 0 acierto (dos notas ascendentes), 1 fallo (una nota grave), 2 logro (arpegio de cuatro notas).
@JsFun("""(kind) => {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    const ctx = window.__bpmAudio || (window.__bpmAudio = new Ctx());
    if (ctx.state === 'suspended') ctx.resume();
    const notes = kind === 0 ? [[660, 0], [880, 0.09]] : kind === 1 ? [[220, 0]] : [[523, 0], [659, 0.09], [784, 0.18], [1047, 0.27]];
    const start = ctx.currentTime;
    for (const [freq, at] of notes) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = kind === 1 ? 'triangle' : 'sine';
      osc.frequency.value = freq;
      gain.gain.setValueAtTime(0.0001, start + at);
      gain.gain.exponentialRampToValueAtTime(0.12, start + at + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + at + (kind === 1 ? 0.22 : 0.16));
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(start + at);
      osc.stop(start + at + 0.25);
    }
  } catch (e) {}
}""")
private external fun browserTones(kind: Int)
