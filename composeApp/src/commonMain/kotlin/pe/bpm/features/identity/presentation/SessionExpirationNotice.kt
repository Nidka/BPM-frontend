package pe.bpm.features.identity.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*

/** Aviso integrado en el árbol accesible; también puede componerse dentro de formularios abiertos. */
@Composable
fun SessionExpirationNotice(status: SessionStatus, onContinue: () -> Unit, onLogout: () -> Unit) {
    val warning = status.warning ?: return
    BpmCard(Modifier.fillMaxWidth()
        .semantics { paneTitle = "Aviso de sesión" }, tint = MaterialTheme.colorScheme.secondaryContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BpmIcon(BpmSymbol.Clock)
            Text("Tu sesión está por vencer", fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        Text(if (warning.reason == SessionExpiryReason.INACTIVITY)
            "¿Sigues ahí? Por seguridad, cerraremos tu sesión en ${warning.secondsRemaining} segundos si no continúas."
        else "Por seguridad, tu sesión alcanzará su duración máxima en ${warning.secondsRemaining} segundos. Después necesitarás iniciar sesión de nuevo.")
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (status.continuing) LinearProgressIndicator(Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (warning.reason == SessionExpiryReason.INACTIVITY)
                Button(onClick = onContinue, enabled = !status.continuing, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (status.continuing) "Comprobando sesión…" else "Seguir conectado")
                }
            TextButton(onClick = onLogout, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (warning.reason == SessionExpiryReason.INACTIVITY) "Cerrar sesión" else "Volver a iniciar sesión")
            }
        }
    }
}
