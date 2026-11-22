package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/** iOS: generadores de respuesta háptica de UIKit y un sonido corto del sistema. */
@Composable
internal actual fun rememberPlatformFeedback(): BpmPlatformFeedback = remember { IosFeedback() }

@OptIn(ExperimentalForeignApi::class)
private class IosFeedback : BpmPlatformFeedback {
    override fun play(kind: BpmFeedbackKind, sound: Boolean, vibration: Boolean) {
        if (vibration) when (kind) {
            BpmFeedbackKind.Success -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
            BpmFeedbackKind.Error -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            BpmFeedbackKind.Celebrate -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
        }
        if (sound) AudioServicesPlaySystemSound(when (kind) {
            BpmFeedbackKind.Success -> 1057u // «Tink»
            BpmFeedbackKind.Error -> 1053u // «Tock» grave
            BpmFeedbackKind.Celebrate -> 1025u // «New mail»: tres notas ascendentes
        })
    }
}
