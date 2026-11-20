package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*

/**
 * Cabecera de «Mis tareas» del personal (WORKER): título de la sección y acceso a incidencias y correcciones,
 * que reemplaza al botón suelto de los demás roles.
 */
@Composable
internal fun WorkerTasksHeader(onIncidents: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier.padding(bottom = BpmSpacing.md), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
        BpmPageHeader("Mis tareas")
        BpmTaskRow(
            title = "Incidencias y correcciones",
            symbol = BpmSymbol.Alert,
            detail = "Reporta un problema o sube la foto de una corrección",
            tone = BpmTone.Warning,
            onClick = if (enabled) onIncidents else null,
        )
    }
}
