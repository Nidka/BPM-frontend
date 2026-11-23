package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Preferencias de sonido y vibración de la persona (interruptores de Perfil). Ambas vienen activas hasta que la
 * persona las apague; las guarda `FeedbackPreferences` en el dispositivo y la raíz de la app las publica en
 * [LocalBpmFeedbackSettings].
 */
@Immutable
data class BpmFeedbackSettings(
    val sound: Boolean = true,
    val vibration: Boolean = true,
    /** Tamaño del texto: 1 = normal, 1,15 = grande, 1,3 = muy grande (se multiplica por el del sistema). */
    val textScale: Float = 1f,
    /** Texto y bordes más oscuros para quien ve poco. */
    val highContrast: Boolean = false,
    /** Muestra «Escuchar» (lectura en voz alta) donde la plataforma tiene síntesis de voz. */
    val readAloud: Boolean = true,
)

/** Preferencias vigentes para [rememberBpmFeedbackEffects]; sin proveedor valen los valores por defecto (todo activo). */
val LocalBpmFeedbackSettings = staticCompositionLocalOf { BpmFeedbackSettings() }

/** Tipo de efecto de retroalimentación: acierto, fallo o logro (terminar algo). */
enum class BpmFeedbackKind { Success, Error, Celebrate }

/**
 * Efectos cortos de sonido y vibración para el estilo de juego. Cada llamada respeta los interruptores de
 * [BpmFeedbackSettings] y nunca lanza ni bloquea: si la plataforma no puede, no pasa nada.
 *
 * - Android: vibración corta con las vibraciones de la vista (sin permisos) y un tono breve de `ToneGenerator`.
 * - iOS: `UIImpactFeedbackGenerator`/`UINotificationFeedbackGenerator` y un sonido del sistema.
 * - Web: tonos cortos generados con WebAudio (sin archivos de audio) y `navigator.vibrate` donde exista.
 * - JVM (pruebas y capturas): no hace nada.
 */
interface BpmFeedbackEffects {
    /** Respuesta correcta o paso logrado. */
    fun success()
    /** Respuesta incorrecta: suave, sin castigo. */
    fun error()
    /** Terminar una lección, una evaluación o una tarea. */
    fun celebrate()
}

/** Implementación de cada plataforma: reproduce [kind] solo con lo que permiten [sound] y [vibration]. */
internal interface BpmPlatformFeedback {
    fun play(kind: BpmFeedbackKind, sound: Boolean, vibration: Boolean)
}

/** Efectos de la plataforma actual (un `actual` por source set). */
@Composable
internal expect fun rememberPlatformFeedback(): BpmPlatformFeedback

/**
 * Efectos de retroalimentación que respetan [LocalBpmFeedbackSettings]. Las llamadas leen las preferencias al
 * momento de reproducir, así que apagar un interruptor surte efecto de inmediato.
 */
@Composable
fun rememberBpmFeedbackEffects(): BpmFeedbackEffects {
    val platform = rememberPlatformFeedback()
    val settings by rememberUpdatedState(LocalBpmFeedbackSettings.current)
    return remember(platform) {
        object : BpmFeedbackEffects {
            private fun play(kind: BpmFeedbackKind) {
                val current = settings
                if (current.sound || current.vibration) runCatching { platform.play(kind, current.sound, current.vibration) }
            }
            override fun success() = play(BpmFeedbackKind.Success)
            override fun error() = play(BpmFeedbackKind.Error)
            override fun celebrate() = play(BpmFeedbackKind.Celebrate)
        }
    }
}
