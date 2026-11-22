package pe.bpm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*

/**
 * Cabecera de «Tareas» del personal (WORKER), estilo de juego: el título (Baloo 2) y, a la derecha, el chip de la sede
 * («Sede Miraflores ▾», con área táctil de 48 dp) que abre el cambio de sede. Sin [showActions] (formulario o detalle)
 * solo se muestra el título. [siteName] vacío ofrece «Elegir sede». «Reportar» es el botón flotante de la lista y
 * registrar una tarea es el círculo de cada fila: aquí ya no hay botones de acción.
 */
@Composable
internal fun WorkerTasksHeader(
    siteName: String?,
    onChangeSite: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showActions: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Tareas", style = BpmPlayType.h1, color = BpmPlayColors.ink, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1)
        if (showActions) {
            val label = if (siteName.isNullOrBlank()) "Elegir sede" else siteName
            Row(
                Modifier.heightIn(min = BpmSpacing.touch).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onChangeSite)
                    .semantics { contentDescription = "Sede: $label. Cambiar" }
                    .background(BpmPlayColors.action.soft, CircleShape).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = BpmPlayType.key, color = BpmPlayColors.action.face, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                BpmIcon(BpmSymbol.ChevronDown, tint = BpmPlayColors.action.face, modifier = Modifier.size(16.dp))
            }
        }
    }
}
