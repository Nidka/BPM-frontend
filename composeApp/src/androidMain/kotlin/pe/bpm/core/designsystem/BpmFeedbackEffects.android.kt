package pe.bpm.core.designsystem

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Android: la vibración usa las vibraciones de la vista (`performHapticFeedback`), que no piden permisos y respetan
 * los ajustes de toque del sistema. El sonido es un tono breve de `ToneGenerator` por el canal multimedia.
 */
@Composable
internal actual fun rememberPlatformFeedback(): BpmPlatformFeedback {
    val view = LocalView.current
    return remember(view) { AndroidFeedback(view) }
}

private class AndroidFeedback(private val view: View) : BpmPlatformFeedback {
    private val main = Handler(Looper.getMainLooper())

    override fun play(kind: BpmFeedbackKind, sound: Boolean, vibration: Boolean) {
        if (vibration) view.performHapticFeedback(haptic(kind))
        if (sound) tone(kind)
    }

    private fun haptic(kind: BpmFeedbackKind): Int = when {
        Build.VERSION.SDK_INT >= 30 -> if (kind == BpmFeedbackKind.Error) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM
        kind == BpmFeedbackKind.Celebrate -> HapticFeedbackConstants.LONG_PRESS
        else -> HapticFeedbackConstants.VIRTUAL_KEY
    }

    private fun tone(kind: BpmFeedbackKind) {
        val (type, millis) = when (kind) {
            BpmFeedbackKind.Success -> ToneGenerator.TONE_PROP_ACK to 140
            BpmFeedbackKind.Error -> ToneGenerator.TONE_PROP_NACK to 160
            BpmFeedbackKind.Celebrate -> ToneGenerator.TONE_PROP_BEEP2 to 260
        }
        val generator = ToneGenerator(AudioManager.STREAM_MUSIC, 45)
        generator.startTone(type, millis)
        main.postDelayed({ generator.release() }, millis + 150L)
    }
}
