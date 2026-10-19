package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.*

@Composable internal fun Home(workspace: Workspace, learningAllowed: Boolean, onLearn: () -> Unit, onCompany: () -> Unit) {
    BpmIntroCard(
        title = if (learningAllowed) "Capacitación y buenas prácticas" else "Gestión de organizaciones",
        description = if (learningAllowed) "Accede a las capacitaciones y consulta la información de tu organización."
            else "Consulta las empresas, sus sedes y los permisos del equipo.",
    ) {
        if (learningAllowed) RaisedButton("Explorar capacitaciones", onLearn, Modifier.widthIn(max = 300.dp).fillMaxWidth())
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val fontScale = LocalDensity.current.fontScale
    val columns = (maxWidth.value / (112 * fontScale)).toInt().coerceIn(1, 3)
    FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = columns,
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Sedes" to (workspace.totals["locations"] ?: workspace.locations.size), "Cursos" to (workspace.totals["courses"] ?: workspace.courses.size), "Controles" to (workspace.totals["checklists"] ?: workspace.checklists.size)).forEach { (label, count) ->
            BpmCard(Modifier.weight(1f)) {
                Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    }
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Estado de tu espacio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SetupStatus("Cuenta verificada", complete = true)
        SetupStatus("Organización disponible", complete = true)
        SetupStatus(if ((workspace.totals["locations"] ?: workspace.locations.size) == 0) "Configura la primera sede" else "Sedes disponibles", (workspace.totals["locations"] ?: workspace.locations.size) > 0)
        if (workspace.canManage) BpmSecondaryButton(onClick = onCompany) { Text("Ver mi organización") }
    }
}

@Composable private fun SetupStatus(label: String, complete: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BpmIcon(if (complete) BpmSymbol.Check else BpmSymbol.Clock, tint = MaterialTheme.colorScheme.primary)
        Text(label)
    }
}

@Composable internal fun SectionHeading(title: String, action: String?, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (action != null) BpmSecondaryButton(onClick = onAction) { Text("+ $action") }
    }
}
@Composable internal fun Entries(entries: List<Entry>, title: String, empty: String) {
    if (entries.isEmpty()) EmptyState(title, empty)
    entries.forEach { entry -> BpmCard(Modifier.fillMaxWidth()) {
        Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (entry.detail.isNotBlank()) Text(entry.detail)
        Eyebrow(when (entry.status) { "PUBLISHED" -> "Publicado"; "ACTIVE" -> "Disponible"; "NOT_STARTED" -> "Sin iniciar"; "IN_PROGRESS" -> "En progreso"; "COMPLETED" -> "Completado"; "OPEN" -> "Abierta"; "RESOLVED" -> "Resuelta"; else -> "Registrado" })
    } }
}
@Composable internal fun Errors(errors: List<WorkspaceError>) {
    errors.forEach { error -> Text(error.message, color = MaterialTheme.colorScheme.error) }
}
internal fun roleLabel(role: String) = when (role) { "ADMIN" -> "Administrador"; "CHEF" -> "Responsable / chef"; else -> "Colaborador" }
