package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.*

@Composable internal fun Home(workspace: Workspace, wide: Boolean, learningAllowed: Boolean, onLearn: () -> Unit, onCompany: () -> Unit) {
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("PEQUEÑOS HÁBITOS. GRANDES CUIDADOS.")
                Text("Hagamos de cada día\nun día más seguro.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Aprende, acompaña a tu equipo y cuida lo que más importa.")
                if (learningAllowed) RaisedButton("Explorar capacitaciones", onLearn, Modifier.widthIn(max = 300.dp))
            }
            if (wide) Mascot(Modifier.size(240.dp))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Sedes" to workspace.locations.size, "Cursos" to workspace.courses.size, "Controles" to workspace.checklists.size).forEach { (label, count) ->
            BpmCard(Modifier.weight(1f)) {
                Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    BpmCard(Modifier.fillMaxWidth()) {
        Eyebrow("PRIMEROS PASOS")
        Text("Un buen comienzo, juntos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("✓  Cuenta verificada")
        Text("✓  Organización disponible")
        Text(if (workspace.locations.isEmpty()) "○  Configura la primera sede" else "✓  Sedes disponibles")
        if (workspace.canManage) OutlinedButton(onClick = onCompany) { Text("Ver mi organización") }
    }
}

@Composable internal fun SectionHeading(title: String, action: String?, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (action != null) OutlinedButton(onClick = onAction) { Text("+ $action") }
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
