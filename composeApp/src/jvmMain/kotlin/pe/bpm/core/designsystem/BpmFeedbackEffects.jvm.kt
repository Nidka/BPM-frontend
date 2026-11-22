package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable

/** JVM (escritorio de desarrollo, pruebas y capturas): sin sonido ni vibración. */
@Composable
internal actual fun rememberPlatformFeedback(): BpmPlatformFeedback = NoFeedback

private object NoFeedback : BpmPlatformFeedback {
    override fun play(kind: BpmFeedbackKind, sound: Boolean, vibration: Boolean) = Unit
}
