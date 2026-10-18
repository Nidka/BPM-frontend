package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.background
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

@Composable
fun WorkspaceScreen(
    viewer: WorkspaceViewer, state: WorkspaceState, section: WorkspaceDestination,
    onNavigate: (WorkspaceDestination) -> Unit, onBack: () -> Unit, canGoBack: Boolean,
    onVerifySession: () -> Unit, sessionBusy: Boolean, sessionMessage: String?, onSelect: (String) -> Unit, onRefresh: () -> Unit,
    onOrganization: (Organization) -> Unit, onLocation: (Location) -> Unit,
    onMember: (String, String, List<String>) -> Unit, onClear: () -> Unit, onLogout: () -> Unit,
) {
    val available = workspaceDestinations(state.workspace?.role, viewer.canCreateOrganization)
    val destinations = workspacePrimaryDestinations(available)
    val navigate: (WorkspaceDestination) -> Unit = { if (it in available) onNavigate(it) }
    var form by remember(viewer.id, state.selected) { mutableStateOf<WorkspaceFormKind?>(null) }
    var picker by remember(viewer.id, state.selected) { mutableStateOf(false) }
    val workspace = state.workspace
    val superAdmin = viewer.canCreateOrganization
    val open: (WorkspaceFormKind) -> Unit = { onClear(); form = it }
    LaunchedEffect(state.revision) { if (state.revision > 0) form = null }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val wide = maxWidth >= 950.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(232.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Brand()
                    Spacer(Modifier.height(32.dp))
                    destinations.forEach { item ->
                        NavigationDrawerItem(label = { Text(item.label, fontWeight = FontWeight.Bold) },
                            selected = section == item, onClick = { navigate(item) },
                            icon = { Text(item.mark, style = MaterialTheme.typography.titleLarge) })
                    }
                    Spacer(Modifier.weight(1f))
                    BpmCard(tint = MaterialTheme.colorScheme.primaryContainer) {
                        Text("Cada hábito cuenta.", fontWeight = FontWeight.Bold)
                        Text("Aprender y cuidar también se hace en equipo.", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("By: Kadin, Evelyn y Piero", style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 32.dp else 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (canGoBack) TextButton(onClick = onBack, enabled = !state.saving) { Text("Volver") }
                        if (!wide) Brand(compact = true)
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { picker = true }, enabled = !state.saving) {
                                Text((workspace?.organization?.name ?: "Mi espacio") + " ▾", maxLines = 1)
                            }
                            DropdownMenu(picker, { picker = false }) {
                                state.organizations.forEach { org -> DropdownMenuItem(text = { Text(org.name) },
                                    onClick = { picker = false; onSelect(org.id) }) }
                                if (superAdmin) DropdownMenuItem(text = { Text("+ Crear organización") }, onClick = { picker = false; open(WorkspaceFormKind.Organization) })
                                if (state.organizations.isEmpty() && !superAdmin) DropdownMenuItem(text = { Text("Sin organizaciones asignadas") }, onClick = { picker = false })
                            }
                        }
                        if (wide) Text(viewer.displayName, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onLogout) { Text("Salir") }
                    }
                }
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (wide) 32.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("TU ESPACIO BPM")
                            Text(if (section == WorkspaceDestination.Home) "¡Hola, ${viewer.displayName.substringBefore(' ')}!" else section.label,
                                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                        }
                        TextButton(onClick = onRefresh, enabled = !state.loading && !state.saving) { Text("Actualizar") }
                    }
                    if (form == null) Errors(state.errors)
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (section == WorkspaceDestination.Account) {
                        BpmCard(Modifier.fillMaxWidth()) {
                            Text(viewer.displayName, style = MaterialTheme.typography.titleLarge)
                            Text(viewer.email)
                            Text(if (viewer.canCreateOrganization) "Administrador general" else workspace?.role?.let(::roleLabel) ?: "Sin organización activa")
                            OutlinedButton(onClick = onVerifySession, enabled = !sessionBusy) { Text(if (sessionBusy) "Verificando…" else "Verificar sesión y permisos") }
                            sessionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (WorkspaceDestination.Learn in available) OutlinedButton(onClick = { navigate(WorkspaceDestination.Learn) }) { Text("Mi aprendizaje") }
                        }
                    }
                    if (section != WorkspaceDestination.Account && workspace == null && !state.loading) {
                        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                            Mascot(Modifier.size(160.dp).align(Alignment.CenterHorizontally))
                            Text("Tu próximo gran hábito empieza aquí.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(when {
                                state.errors.isNotEmpty() -> "No pudimos cargar este espacio. Comprueba tu conexión y vuelve a intentarlo."
                                state.organizations.isEmpty() -> "Tu cuenta está lista. Para comenzar, necesitas un espacio de trabajo."
                                else -> "No pudimos abrir este espacio. Actualiza para volver a intentarlo."
                            })
                            if (superAdmin) RaisedButton("Crear organización", { open(WorkspaceFormKind.Organization) })
                            else if (state.organizations.isEmpty() && state.errors.isEmpty())
                                Text("Pide al administrador de tu organización que agregue ${viewer.email} al equipo. No necesitas crear otra cuenta.")
                        }
                    }
                    workspace?.let { current -> when (section) {
                        WorkspaceDestination.Account -> Unit
                        WorkspaceDestination.Home -> Home(current, wide, learningAllowed = WorkspaceDestination.Learn in available, onLearn = { navigate(WorkspaceDestination.Learn) }, onCompany = { navigate(WorkspaceDestination.Company) })
                        WorkspaceDestination.Company -> {
                            BpmCard(Modifier.fillMaxWidth()) {
                                Eyebrow("ORGANIZACIÓN")
                                Text(current.organization.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(current.organization.legalName)
                                if (current.organization.contactEmail.isNotBlank()) Text(current.organization.contactEmail)
                                if (current.organization.contactPhone.isNotBlank()) Text(current.organization.contactPhone)
                            }
                            SectionHeading("Sedes", if (current.canManage) "Agregar sede" else null) { open(WorkspaceFormKind.Location) }
                            if (current.locations.isEmpty()) EmptyState("Aún no hay sedes disponibles", "Agrega el primer local para organizar al equipo y sus actividades.")
                            current.locations.forEach { location -> BpmCard(Modifier.fillMaxWidth()) {
                                Text(location.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(location.address)
                                if (location.district.isNotBlank()) Text(location.district, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (location.contactPhone.isNotBlank()) Text(location.contactPhone)
                            } }
                        }
                        WorkspaceDestination.Team -> {
                            SectionHeading("Personas que hacen la diferencia", if (current.canManage) "Agregar persona" else null) { open(WorkspaceFormKind.Member) }
                            if (!current.canManage) EmptyState("Un espacio que se cuida en equipo", "La administración de personas está disponible para el administrador de tu organización.")
                            else {
                                if (current.members.isEmpty()) EmptyState("Forma tu equipo", "Agrega personas que ya tengan una cuenta BPM verificada y asigna sus permisos.")
                                current.members.forEach { member -> BpmCard(Modifier.fillMaxWidth()) {
                                    Text(member.name, fontWeight = FontWeight.Bold)
                                    Text(member.email)
                                    Eyebrow(roleLabel(member.role))
                                    Text(if (member.role == "ADMIN") "Todas las sedes de esta organización" else "${member.locationIds.size} sede(s) asignada(s)", style = MaterialTheme.typography.bodySmall)
                                } }
                            }
                        }
                        WorkspaceDestination.Learn -> {
                            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.secondaryContainer) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Eyebrow("APRENDER PARA CUIDAR")
                                        Text("Un hábito a la vez.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                                        Text("Consulta las capacitaciones publicadas por tu organización.")
                                    }
                                    Mascot(Modifier.size(if (wide) 180.dp else 100.dp), learning = true)
                                }
                            }
                            Entries(current.courses, "Tu próxima lección llegará aquí", "Todavía no hay capacitaciones publicadas. El recorrido de lecciones y evaluaciones se habilitará en la siguiente etapa.")
                        }
                        WorkspaceDestination.Operations -> {
                            Eyebrow("CONTROLES DISPONIBLES")
                            Entries(current.checklists, "Todo listo para organizar tus controles", "Aquí aparecerán las plantillas activas. La ejecución y sincronización de controles se implementarán en la siguiente etapa.")
                            Eyebrow("INCIDENCIAS DE TUS SEDES")
                            Entries(current.incidents, "Sin incidencias para mostrar", "No hay incidencias disponibles para tus permisos actuales.")
                        }
                    } }
                    Text("Los datos se consultan en línea. No se guardan cambios pendientes sin conexión.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!wide) NavigationBar {
                    destinations.forEach { item -> NavigationBarItem(selected = section == item,
                        onClick = { navigate(item) }, icon = { Text(item.mark, style = MaterialTheme.typography.titleLarge) }, label = { Text(item.label) }) }
                }
            }
        }
    }
    form?.let { kind -> WorkspaceForm(kind, state, onClose = { if (!state.saving) { form = null; onClear() } },
        onOrganization, onLocation, onMember) }
}
