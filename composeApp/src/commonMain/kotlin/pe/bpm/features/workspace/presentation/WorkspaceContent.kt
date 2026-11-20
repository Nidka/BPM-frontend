package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.*

@Composable internal fun Home(workspace: Workspace, learningAllowed: Boolean, onLearn: () -> Unit, onCompany: () -> Unit) {
    val locations = workspace.totals["locations"] ?: workspace.locations.size
    val indicators = listOf("Sedes" to locations, "Cursos" to (workspace.totals["courses"] ?: workspace.courses.size),
        "Controles" to (workspace.totals["checklists"] ?: workspace.checklists.size))
        .map { (label, count) -> @Composable { m: Modifier -> BpmStatTile(count.toString(), label, m) } }
    BpmPanelLayout(indicators = indicators, support = {
        BpmCard(Modifier.fillMaxWidth()) {
            Text("Estado de tu espacio", style = MaterialTheme.typography.titleMedium)
            SetupStatus("Cuenta verificada", complete = true)
            SetupStatus("Organización disponible", complete = true)
            SetupStatus(if (locations == 0) "Configura la primera sede" else "Sedes disponibles", locations > 0)
            if (workspace.canManage) BpmSecondaryButton(onClick = onCompany) { Text("Ver mi organización") }
        }
    }) {
        BpmIntroCard(
            title = if (learningAllowed) "Capacitación y buenas prácticas" else "Gestión de organizaciones",
            description = if (learningAllowed) "Accede a las capacitaciones y consulta la información de tu organización."
                else "Consulta las empresas, sus sedes y los permisos del equipo.",
        ) {
            if (learningAllowed) RaisedButton("Explorar capacitaciones", onLearn, Modifier.bpmPrimaryButtonWidth())
        }
    }
}

@Composable private fun SetupStatus(label: String, complete: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmIcon(if (complete) BpmSymbol.Check else BpmSymbol.Clock, tint = MaterialTheme.colorScheme.primary)
        Text(label)
    }
}

@Composable internal fun SectionHeading(title: String, action: String?, onAction: () -> Unit) {
    // Título y acción en una misma fila, alineados al centro; sin el «+» pegado como texto.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (action != null) BpmSecondaryButton(onClick = onAction) { Text(action) }
    }
}
/** [onOpen] conecta cada entrada con su detalle (por ejemplo, el curso de Academia); sin él solo se listan. */
@Composable internal fun Entries(entries: List<Entry>, title: String, empty: String, onOpen: ((String) -> Unit)? = null) {
    if (entries.isEmpty()) EmptyState(title, empty)
    BpmGridLayout(entries) { entry, cardModifier -> BpmCard(cardModifier) {
        Text(entry.title, style = MaterialTheme.typography.titleMedium)
        if (entry.detail.isNotBlank()) Text(entry.detail)
        Eyebrow(when (entry.status) { "PUBLISHED" -> "Publicado"; "ACTIVE" -> "Disponible"; "NOT_STARTED" -> "Sin iniciar"; "IN_PROGRESS" -> "En progreso"; "COMPLETED" -> "Completado"; "OPEN" -> "Abierta"; "RESOLVED" -> "Resuelta"; else -> "Registrado" })
        if (onOpen != null) BpmSecondaryButton(onClick = { onOpen(entry.id) }, modifier = Modifier.fillMaxWidth()) { Text("Abrir curso") }
    } }
}
@Composable internal fun Errors(errors: List<WorkspaceError>) {
    errors.forEach { error -> Text(error.message, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}
internal fun roleLabel(role: String) = when (role) { "ADMIN" -> "Administrador"; "CHEF" -> "Responsable / chef"; else -> "Colaborador" }
