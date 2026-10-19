package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Navegación explícita compartida; no anuncia un total de páginas que el servidor no calcula. */
@Composable fun PageNavigation(number: Int, canPrevious: Boolean, canNext: Boolean, loading: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    if (canPrevious || canNext || number > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BpmFeedback(MaterialTheme.colorScheme.primary) {
        BpmSecondaryButton(onClick = onPrevious, enabled = canPrevious && !loading) { Text("Anterior") }
        }
        Text("Página $number", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        BpmFeedback(MaterialTheme.colorScheme.primary) {
        BpmSecondaryButton(onClick = onNext, enabled = canNext && !loading) { Text("Siguiente") }
        }
    }
}
