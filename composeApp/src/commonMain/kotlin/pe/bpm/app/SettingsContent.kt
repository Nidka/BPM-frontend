package pe.bpm.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*

/** Tamaños del texto que se ofrecen: normal, grande y muy grande (se suman al tamaño de letra del sistema). */
internal val textScaleOptions = listOf(1f to "Normal", 1.15f to "Grande", 1.3f to "Muy grande")

/**
 * «Ajustes»: la misma pantalla para Personal, Jefe de cocina, Administrador y Superadministrador (el Perfil solo dice
 * quién eres). Cuatro grupos en lenguaje simple: **Accesibilidad** (texto, contraste, «Escuchar», subtítulos, transcripción,
 * movimiento), **Sonido y vibración**, **Avisos** y **Cuenta** (dispositivos, invitación, cerrar sesión). No hay selector de
 * idioma: la app solo está en español. Cada cambio se aplica al instante y se guarda en este dispositivo.
 */
@Composable
internal fun SettingsContent(
    settings: BpmFeedbackSettings,
    onChange: (BpmFeedbackSettings) -> Unit,
    onAlerts: (() -> Unit)?,
    onDevices: () -> Unit,
    onAcceptInvitation: (() -> Unit)?,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    /** Solo SUPER_ADMIN: edición del contacto de soporte. */
    platformSupport: (@Composable () -> Unit)? = null,
    /** Interruptor de notificaciones push (solo donde existen, p. ej. Android). */
    pushSwitch: (@Composable () -> Unit)? = null,
    /** WhatsApp propio (opcional) para que el equipo pueda escribir. */
    myWhatsapp: (@Composable () -> Unit)? = null,
) {
    BpmSettingsScaffold("Ajustes", modifier, subtitle = "Haz la app más fácil de ver y de escuchar. Los cambios se guardan solo en este dispositivo.") {
        BpmSettingsSection("Accesibilidad", description = "Para leer mejor, escuchar y ver menos movimiento.") {
            BpmSettingChoice("Tamaño del texto", "Se suma al tamaño de letra de tu teléfono.", textScaleOptions, settings.textScale) { onChange(settings.copy(textScale = it)) }
            BpmSettingToggle("Más contraste", "Letras y bordes más oscuros para leer mejor.", settings.highContrast, { onChange(settings.copy(highContrast = it)) })
            BpmSettingToggle("Escuchar", "Muestra un botón para que el teléfono lea en voz alta lecciones, preguntas y avisos.", settings.readAloud, { onChange(settings.copy(readAloud = it)) })
            BpmSettingToggle("Subtítulos en los videos", "Los videos empiezan con subtítulos en español.", settings.captions, { onChange(settings.copy(captions = it)) })
            BpmSettingToggle("Mostrar siempre la transcripción", "El texto del video aparece abierto debajo de él, para leerlo o escucharlo.", settings.transcript, { onChange(settings.copy(transcript = it)) })
            BpmSettingToggle("Reducir movimiento", "Sin animaciones, destellos ni confeti.", settings.reduceMotion, { onChange(settings.copy(reduceMotion = it)) })
        }
        BpmSettingsSection("Sonido y vibración", description = "Cada sonido tiene también un mensaje en pantalla.") {
            BpmSettingToggle("Sonido", "Un sonido corto al acertar y al terminar.", settings.sound, { onChange(settings.copy(sound = it)) })
            BpmSettingToggle("Vibración", "Una vibración suave al responder.", settings.vibration, { onChange(settings.copy(vibration = it)) })
        }
        pushSwitch?.invoke()
        onAlerts?.let { open ->
            BpmSettingsSection("Avisos") {
                BpmSettingLink("Mis avisos", "Mira tus avisos y elige si el teléfono te los notifica.", open, symbol = BpmSymbol.Bell)
            }
        }
        platformSupport?.invoke()
        myWhatsapp?.invoke()
        BpmSettingsSection("Cuenta") {
            BpmSettingLink("Mis dispositivos", "Dónde tienes abierta tu sesión.", onDevices, symbol = BpmSymbol.Account)
            onAcceptInvitation?.let { BpmSettingLink("Aceptar una invitación", "Únete a otra organización con el código del correo.", it, symbol = BpmSymbol.Team) }
            BpmSettingLink("Cerrar sesión", "Sales de la app en este dispositivo.", onLogout, symbol = BpmSymbol.Logout)
        }
    }
}
