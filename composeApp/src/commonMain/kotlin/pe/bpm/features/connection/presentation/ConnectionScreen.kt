package pe.bpm.features.connection.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*

/** Estado del servicio: patrón de acceso (tarjeta única centrada de 440 dp; ancho completo en compacta). */
@Composable
fun ConnectionScreen(state: ConnectionUiState, onRetry: () -> Unit) {
    val connected = state is ConnectionUiState.Connected
    BpmAccessScreen(Modifier.background(MaterialTheme.colorScheme.background)) {
        Brand()
        BpmPageHeader("Estado del servicio", description = "Comprueba la disponibilidad del servicio si tienes problemas para acceder.")
        BpmCenteredCard {
            Text(if (connected) "Conexión exitosa" else "Estado de conexión", style = MaterialTheme.typography.titleLarge)
            when (state) {
                ConnectionUiState.Checking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Comprobando la API y la base de datos…")
                }
                is ConnectionUiState.Connected -> {
                    Text("El frontend y el backend están conectados. PostgreSQL está disponible.")
                    Text("Backend · ${state.version.take(7)}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is ConnectionUiState.Unavailable -> Text(state.message)
            }
            RaisedButton(if (connected) "Volver a comprobar" else "Reintentar conexión", onRetry,
                modifier = Modifier.fillMaxWidth(), enabled = state !is ConnectionUiState.Checking)
        }
    }
}
