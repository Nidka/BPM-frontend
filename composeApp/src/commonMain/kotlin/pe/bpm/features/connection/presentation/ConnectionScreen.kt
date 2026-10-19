package pe.bpm.features.connection.presentation

import pe.bpm.core.designsystem.RaisedButton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.Brand

@Composable
fun ConnectionScreen(state: ConnectionUiState, onRetry: () -> Unit) {
    val connected = state is ConnectionUiState.Connected
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Brand()
            Spacer(Modifier.height(18.dp))
            Text("Estado del servicio", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("Comprueba la disponibilidad del servicio si tienes problemas para acceder.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(if (connected) "Conexión exitosa" else "Estado de conexión",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    when (state) {
                        ConnectionUiState.Checking -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("Comprobando la API y la base de datos…")
                        }
                        is ConnectionUiState.Connected -> {
                            Text("El frontend y el backend están conectados. PostgreSQL está disponible.")
                            Text("Backend · ${state.version.take(7)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        is ConnectionUiState.Unavailable -> Text(state.message)
                    }
                    RaisedButton(if (connected) "Volver a comprobar" else "Reintentar conexión", onRetry,
                        enabled = state !is ConnectionUiState.Checking)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
