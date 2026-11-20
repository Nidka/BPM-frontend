package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.operations.presentation.WorkerTasksMaxWidth

/**
 * Cabecera de «Mis tareas» del personal (WORKER). Una fila con el título y el chip de sede («Sede Miraflores · Cambiar»);
 * «Registrar limpieza» es la acción principal: fija abajo en clase compacta ([BpmActionBar]) y, desde la media, un botón
 * de ancho automático a la derecha del título. «Incidencias y correcciones» queda como fila bajo la cabecera.
 *
 * Sin [onRegister] (formulario, detalle o sin sede) solo se muestra el título. [siteName] vacío ofrece «Elegir sede».
 */
@Composable
internal fun WorkerTasksHeader(
    siteName: String?,
    onChangeSite: () -> Unit,
    onRegister: (() -> Unit)?,
    onIncidents: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showActions: Boolean = true,
) {
    val wide = currentWindowClass != BpmWindowClass.Compact
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text("Mis tareas", style = MaterialTheme.typography.headlineMedium, maxLines = 1, modifier = Modifier.semantics { heading() })
                if (showActions) {
                    // Compacta: solo la sede con una flecha (no cabe «· Cambiar» junto al título); desde la media, «Sede · Cambiar».
                    val label = buildAnnotatedString {
                        if (!siteName.isNullOrBlank()) append(siteName)
                        if (siteName.isNullOrBlank() || wide) {
                            if (!siteName.isNullOrBlank()) append(" · ")
                            withStyle(SpanStyle(color = primary)) { append(if (siteName.isNullOrBlank()) "Elegir sede" else "Cambiar") }
                        }
                    }
                    BpmFilterChip(selected = false, onClick = onChangeSite, enabled = enabled, modifier = Modifier.weight(1f, fill = false),
                        label = {
                            Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                BpmIcon(BpmSymbol.ChevronDown, tint = primary, modifier = Modifier.size(16.dp))
                            }
                        })
                }
            }
            if (showActions && wide && onRegister != null)
                Row(Modifier.widthIn(min = 160.dp).width(IntrinsicSize.Max)) { RegisterButton(onRegister, enabled) }
        }
        if (showActions && !wide && onRegister != null) BpmActionBar { RegisterButton(onRegister, enabled) }
        // La fila de incidencias va con la lista (máx. 840 dp); la cabecera de arriba usa todo el ancho.
        if (showActions && onIncidents != null) BpmTaskRow(
            modifier = Modifier.widthIn(max = WorkerTasksMaxWidth),
            title = "Incidencias y correcciones",
            symbol = BpmSymbol.Alert,
            detail = "Reporta un problema o sube la foto de una corrección",
            tone = BpmTone.Warning,
            onClick = if (enabled) onIncidents else null,
        )
    }
}

@Composable
private fun RegisterButton(onRegister: () -> Unit, enabled: Boolean) {
    RaisedButton("Registrar limpieza", onRegister, Modifier.fillMaxWidth(), enabled = enabled)
}
