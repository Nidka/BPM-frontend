package pe.bpm.features.notifications.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*
import pe.bpm.features.notifications.domain.*

/** Estado visible del interruptor: encendido solo si el servidor confirmó el dispositivo (y el permiso del sistema está dado). */
fun pushStatusText(s: NotificationDevicesState): String = when {
  s.busy -> "Activando…"
  s.device != null -> "Activadas. Te llegan los avisos a este celular."
  s.failure == NotificationFailure.PERMISSION -> "Bloqueadas en Android: ábrelo en Ajustes del sistema."
  s.failure == NotificationFailure.CONNECTION -> "Sin conexión. Vuelve a intentarlo cuando tengas internet."
  s.failure == NotificationFailure.SESSION -> "Vuelve a entrar a tu cuenta para activarlas."
  s.failure == NotificationFailure.CONFLICT -> "Este celular no pudo vincularse. Apágalo y vuelve a encenderlo."
  s.failure != null -> "No pudimos activarlas. Inténtalo otra vez."
  else -> "Desactivadas. Los avisos siguen en la bandeja de la app."
}

/** «Notificaciones en este celular»: un solo interruptor con su estado real y, si Android las bloquea, acceso a sus ajustes. */
@Composable
fun PushOptInPanel(viewModel: NotificationDevicesViewModel) {
  val s by viewModel.state.collectAsState()
  if (!s.available) return
  BpmSettingsSection("Notificaciones") {
    BpmSettingToggle("Notificaciones en este celular", pushStatusText(s), s.device != null || s.busy, { on -> if (!s.busy) viewModel.setEnabled(on) })
    if (s.failure == NotificationFailure.PERMISSION)
      BpmChunkyButton("Abrir ajustes de Android", { viewModel.openSystemSettings() }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
  }
}
