package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import pe.bpm.core.designsystem.*
import pe.bpm.features.identity.domain.Profile
import pe.bpm.features.workspace.domain.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private enum class Section(val label: String, val mark: String) {
    Home("Inicio", "⌂"), Learn("Aprender", "◇"), Operations("Control", "✓"),
    Team("Equipo", "◎"), Company("Empresa", "▦")
}
private enum class Form { Organization, Location, Member }

@Composable
fun WorkspaceScreen(
    profile: Profile, state: WorkspaceState, onSelect: (String) -> Unit, onRefresh: () -> Unit,
    onOrganization: (Organization) -> Unit, onLocation: (Location) -> Unit,
    onMember: (String, String, List<String>) -> Unit, onClear: () -> Unit, onLogout: () -> Unit,
) {
    var section by remember { mutableStateOf(Section.Home) }
    var form by remember { mutableStateOf<Form?>(null) }
    var picker by remember { mutableStateOf(false) }
    val workspace = state.workspace
    val superAdmin = profile.platformRole == "SUPER_ADMIN"
    val open: (Form) -> Unit = { onClear(); form = it }
    LaunchedEffect(state.revision) { if (state.revision > 0) form = null }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val wide = maxWidth >= 950.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(232.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Brand()
                    Spacer(Modifier.height(32.dp))
                    Section.entries.forEach { item ->
                        NavigationDrawerItem(label = { Text(item.label, fontWeight = FontWeight.Bold) },
                            selected = section == item, onClick = { section = item },
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
                        if (!wide) Brand(compact = true)
                        Box(Modifier.weight(1f)) {
                            TextButton(onClick = { picker = true }, enabled = !state.saving) {
                                Text((workspace?.organization?.name ?: "Mi espacio") + " ▾", maxLines = 1)
                            }
                            DropdownMenu(picker, { picker = false }) {
                                state.organizations.forEach { org -> DropdownMenuItem(text = { Text(org.name) },
                                    onClick = { picker = false; onSelect(org.id) }) }
                                if (superAdmin) DropdownMenuItem(text = { Text("+ Crear organización") }, onClick = { picker = false; open(Form.Organization) })
                                if (state.organizations.isEmpty() && !superAdmin) DropdownMenuItem(text = { Text("Sin organizaciones asignadas") }, onClick = { picker = false })
                            }
                        }
                        if (wide) Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = onLogout) { Text("Salir") }
                    }
                }
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (wide) 32.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("TU ESPACIO BPM")
                            Text(if (section == Section.Home) "¡Hola, ${profile.displayName.substringBefore(' ')}!" else section.label,
                                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
                        }
                        TextButton(onClick = onRefresh, enabled = !state.loading && !state.saving) { Text("Actualizar") }
                    }
                    if (form == null) Errors(state.errors)
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (workspace == null && !state.loading) {
                        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                            Mascot(Modifier.size(160.dp).align(Alignment.CenterHorizontally))
                            Text("Tu próximo gran hábito empieza aquí.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(when {
                                state.errors.isNotEmpty() -> "No pudimos cargar este espacio. Comprueba tu conexión y vuelve a intentarlo."
                                state.organizations.isEmpty() -> "Tu cuenta está lista. Para comenzar, necesitas un espacio de trabajo."
                                else -> "No pudimos abrir este espacio. Actualiza para volver a intentarlo."
                            })
                            if (superAdmin) RaisedButton("Crear organización", { open(Form.Organization) })
                            else if (state.organizations.isEmpty() && state.errors.isEmpty())
                                Text("Pide al administrador de tu organización que agregue ${profile.email} al equipo. No necesitas crear otra cuenta.")
                        }
                    }
                    workspace?.let { current -> when (section) {
                        Section.Home -> Home(current, wide, onLearn = { section = Section.Learn }, onCompany = { section = Section.Company })
                        Section.Company -> {
                            BpmCard(Modifier.fillMaxWidth()) {
                                Eyebrow("ORGANIZACIÓN")
                                Text(current.organization.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(current.organization.legalName)
                                if (current.organization.contactEmail.isNotBlank()) Text(current.organization.contactEmail)
                                if (current.organization.contactPhone.isNotBlank()) Text(current.organization.contactPhone)
                            }
                            SectionHeading("Sedes", if (current.canManage) "Agregar sede" else null) { open(Form.Location) }
                            if (current.locations.isEmpty()) EmptyState("Aún no hay sedes disponibles", "Agrega el primer local para organizar al equipo y sus actividades.")
                            current.locations.forEach { location -> BpmCard(Modifier.fillMaxWidth()) {
                                Text(location.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(location.address)
                                if (location.district.isNotBlank()) Text(location.district, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (location.contactPhone.isNotBlank()) Text(location.contactPhone)
                            } }
                        }
                        Section.Team -> {
                            SectionHeading("Personas que hacen la diferencia", if (current.canManage) "Agregar persona" else null) { open(Form.Member) }
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
                        Section.Learn -> {
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
                        Section.Operations -> {
                            Eyebrow("CONTROLES DISPONIBLES")
                            Entries(current.checklists, "Todo listo para organizar tus controles", "Aquí aparecerán las plantillas activas. La ejecución y sincronización de controles se implementarán en la siguiente etapa.")
                            Eyebrow("INCIDENCIAS DE TUS SEDES")
                            Entries(current.incidents, "Sin incidencias para mostrar", "No hay incidencias disponibles para tus permisos actuales.")
                        }
                    } }
                    Text("Los datos se consultan en línea. No se guardan cambios pendientes sin conexión.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!wide) NavigationBar {
                    Section.entries.forEach { item -> NavigationBarItem(selected = section == item,
                        onClick = { section = item }, icon = { Text(item.mark, style = MaterialTheme.typography.titleLarge) }, label = { Text(item.label) }) }
                }
            }
        }
    }
    form?.let { kind -> WorkspaceForm(kind, state, onClose = { if (!state.saving) { form = null; onClear() } },
        onOrganization, onLocation, onMember) }
}

@Composable private fun Home(workspace: Workspace, wide: Boolean, onLearn: () -> Unit, onCompany: () -> Unit) {
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("PEQUEÑOS HÁBITOS. GRANDES CUIDADOS.")
                Text("Hagamos de cada día\nun día más seguro.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Aprende, acompaña a tu equipo y cuida lo que más importa.")
                RaisedButton("Explorar capacitaciones", onLearn, Modifier.widthIn(max = 300.dp))
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

@Composable private fun SectionHeading(title: String, action: String?, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (action != null) OutlinedButton(onClick = onAction) { Text("+ $action") }
    }
}
@Composable private fun Entries(entries: List<Entry>, title: String, empty: String) {
    if (entries.isEmpty()) EmptyState(title, empty)
    entries.forEach { entry -> BpmCard(Modifier.fillMaxWidth()) {
        Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (entry.detail.isNotBlank()) Text(entry.detail)
        Eyebrow(when (entry.status) { "PUBLISHED" -> "Publicado"; "ACTIVE" -> "Disponible"; "NOT_STARTED" -> "Sin iniciar"; "IN_PROGRESS" -> "En progreso"; "COMPLETED" -> "Completado"; "OPEN" -> "Abierta"; "RESOLVED" -> "Resuelta"; else -> "Registrado" })
    } }
}
@Composable private fun Errors(errors: List<WorkspaceError>) {
    errors.forEach { error -> Text(error.message, color = MaterialTheme.colorScheme.error) }
}
private fun roleLabel(role: String) = when (role) { "ADMIN" -> "Administrador"; "CHEF" -> "Responsable / chef"; else -> "Colaborador" }

@OptIn(ExperimentalUuidApi::class)
@Composable private fun WorkspaceForm(kind: Form, state: WorkspaceState, onClose: () -> Unit,
    onOrganization: (Organization) -> Unit, onLocation: (Location) -> Unit,
    onMember: (String, String, List<String>) -> Unit) {
    val id = remember(kind) { Uuid.random().toString() }
    var name by remember { mutableStateOf("") }
    var trade by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("WORKER") }
    var business by remember { mutableStateOf("RESTAURANT") }
    var locations by remember { mutableStateOf(emptyList<String>()) }
    val fields = state.errors.flatMap { it.fields.entries }.associate { it.key to it.value }
    Dialog(onDismissRequest = onClose) {
        BpmCard(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 720.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("CRECER EN EQUIPO")
                Text(when (kind) { Form.Organization -> "Nueva organización"; Form.Location -> "Nueva sede"; Form.Member -> "Agregar al equipo" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (kind == Form.Member) {
                    Text("La persona debe registrarse y verificar su correo en BPM primero. No se envían invitaciones por correo.", style = MaterialTheme.typography.bodySmall)
                    FormField(email, { email = it }, "Correo de la cuenta BPM", fields["email"], !state.saving)
                    listOf("ADMIN", "CHEF", "WORKER").forEach { choice -> FilterChip(selected = role == choice, onClick = { role = choice }, enabled = !state.saving, label = { Text(roleLabel(choice)) }) }
                    fields["role"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (role == "ADMIN") Text("Acceso administrativo a todas las sedes de esta organización.")
                    else {
                        Text("Sedes permitidas", fontWeight = FontWeight.Bold)
                        state.workspace?.locations.orEmpty().forEach { location -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(location.id in locations, enabled = !state.saving, onCheckedChange = { checked -> locations = if (checked) locations + location.id else locations - location.id })
                            Text(location.name)
                        } }
                        if (state.workspace?.locations.isNullOrEmpty()) Text("Crea una sede antes de asignar colaboradores.")
                        fields["locationIds"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    FormField(name, { name = it }, if (kind == Form.Organization) "Razón social *" else "Nombre de la sede *", fields[if (kind == Form.Organization) "legalName" else "name"], !state.saving)
                    if (kind == Form.Organization) {
                        FormField(trade, { trade = it }, "Nombre comercial", fields["tradeName"], !state.saving)
                        FormField(tax, { tax = it }, "RUC / identificación fiscal", fields["taxId"], !state.saving)
                        FormField(contactName, { contactName = it }, "Persona de contacto", fields["contactName"], !state.saving)
                        listOf("RESTAURANT" to "Restaurante", "CAFETERIA" to "Cafetería", "CATERING" to "Catering", "RELATED_SERVICE" to "Servicio afín").forEach { (value, label) ->
                            FilterChip(selected = business == value, onClick = { business = value }, enabled = !state.saving, label = { Text(label) })
                        }
                        FormField(email, { email = it }, "Correo de contacto", fields["contactEmail"], !state.saving)
                    } else {
                        FormField(address, { address = it }, "Dirección *", fields["address"], !state.saving)
                        FormField(district, { district = it }, "Distrito", fields["district"], !state.saving)
                    }
                    FormField(phone, { phone = it }, "Teléfono de contacto", fields["contactPhone"], !state.saving)
                }
                Errors(state.errors)
            }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            RaisedButton(if (state.saving) "Guardando…" else "Guardar", enabled = !state.saving && !state.loading, onClick = {
                when (kind) {
                    Form.Organization -> onOrganization(Organization(id, name, trade, business, tax, contactName, phone, email))
                    Form.Location -> onLocation(Location(id, name, address, district, phone))
                    Form.Member -> onMember(email, role, if (role == "ADMIN") emptyList() else locations)
                }
            })
            TextButton(onClick = onClose, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
        }
    }
}
@Composable private fun FormField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean) {
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        enabled = enabled, shape = RoundedCornerShape(14.dp), isError = error != null, supportingText = error?.let { { Text(it) } })
}
