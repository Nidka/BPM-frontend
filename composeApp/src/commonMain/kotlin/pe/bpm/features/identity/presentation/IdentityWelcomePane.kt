package pe.bpm.features.identity.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.Brand
import pe.bpm.core.designsystem.Mascot

/** Presentación del acceso: adapta su composición al espacio recibido, sin consultar datos. */
@Composable
internal fun IdentityWelcomePane(modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.primaryContainer) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 560.dp
            val horizontalPadding = if (compact) 32.dp else 48.dp
            val illustrationSize = (maxHeight * 0.32f).coerceIn(200.dp, 340.dp)
                .coerceAtMost((maxWidth - horizontalPadding * 2).coerceAtLeast(0.dp))
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight).padding(horizontal = horizontalPadding, vertical = 40.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Brand()
                    Text("Buenas prácticas de manipulación",
                        style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold)
                    Text("Capacitación y gestión para los equipos de restaurantes y servicios afines.",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Mascot(Modifier.size(illustrationSize).align(Alignment.CenterHorizontally), learning = true)
                }
            }
        }
    }
}
