package pe.bpm.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import pe.bpm.core.presentation.DraftSaveStatus

/** Distingue la copia local del guardado confirmado por el servidor. */
@Composable fun DraftSaveNotice(status: DraftSaveStatus, onRetry: () -> Unit) {
    when (status) {
        DraftSaveStatus.IDLE -> Unit
        DraftSaveStatus.SAVING -> Text("Conservando borrador en este dispositivo…", style = MaterialTheme.typography.bodySmall)
        DraftSaveStatus.SAVED -> Text("Borrador guardado en este dispositivo.", style = MaterialTheme.typography.bodySmall)
        DraftSaveStatus.FAILED -> {
            Text("No pudimos conservar el borrador en este dispositivo. Evita cerrar la aplicación hasta guardarlo.",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmTextButton(onClick = onRetry) { Text("Reintentar protección del borrador") }
        }
    }
}
