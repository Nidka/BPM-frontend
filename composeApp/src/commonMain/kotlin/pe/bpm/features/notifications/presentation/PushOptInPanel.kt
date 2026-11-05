package pe.bpm.features.notifications.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.notifications.domain.*

@Composable
fun PushOptInPanel(viewModel: NotificationDevicesViewModel) {
  val s by viewModel.state.collectAsState()
  if (!s.available) return
  BpmCard(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Avisos en este dispositivo", style = MaterialTheme.typography.titleMedium)
      if (!s.available) Text(s.unavailableReason)
      else {
        Text(
          "Activa notificaciones para recibir un aviso genérico. El contenido privado se consulta dentro de la aplicación."
        )
        if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.failure?.let {
          Text(
            when (it) {
              NotificationFailure.TOKEN_CHANGED ->
                "El dispositivo renovó su vinculación. Activa de nuevo para confirmar las notificaciones con tu sesión actual."
              NotificationFailure.PERMISSION ->
                "Permiso de notificaciones no concedido. Puedes habilitarlo en los ajustes del dispositivo y reintentar."
              NotificationFailure.CONFLICT ->
                "Este dispositivo no pudo vincularse a esta cuenta. Desactiva y vuelve a intentar."
              NotificationFailure.SESSION -> "Vuelve a ingresar para activar las notificaciones."
              NotificationFailure.DENIED -> "Tu cuenta no tiene acceso a este registro."
              NotificationFailure.UNAVAILABLE -> "Las notificaciones externas no están disponibles."
              NotificationFailure.INVALID_RESPONSE ->
                "No pudimos verificar la activación. Reintenta."
              NotificationFailure.CONNECTION ->
                "No pudimos confirmar el cambio en el servidor. Puedes reintentar."
            },
            color = MaterialTheme.colorScheme.error,
          )
        }
        if (s.device != null) {
          Text(
            "Dispositivo registrado por el servidor. La entrega de cada aviso depende del proveedor."
          )
          BpmSecondaryButton(viewModel::disable, enabled = !s.busy) {
            Text("Desactivar notificaciones")
          }
        } else {
          RaisedButton(
            "Activar notificaciones",
            viewModel::enable,
            Modifier.fillMaxWidth(),
            enabled = !s.busy,
          )
          if (s.failure == NotificationFailure.CONFLICT)
            BpmSecondaryButton(viewModel::disable, enabled = !s.busy) {
              Text("Limpiar vinculación de este dispositivo")
            }
        }
      }
    }
  }
}
