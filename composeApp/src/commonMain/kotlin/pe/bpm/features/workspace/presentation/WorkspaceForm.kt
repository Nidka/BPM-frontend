package pe.bpm.features.workspace.presentation

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
import pe.bpm.features.workspace.domain.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal enum class WorkspaceFormKind { Organization, Location, Member }

@OptIn(ExperimentalUuidApi::class)
@Composable internal fun WorkspaceForm(kind: WorkspaceFormKind, state: WorkspaceState, onClose: () -> Unit,
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
                Text(when (kind) { WorkspaceFormKind.Organization -> "Nueva organización"; WorkspaceFormKind.Location -> "Nueva sede"; WorkspaceFormKind.Member -> "Agregar al equipo" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (kind == WorkspaceFormKind.Member) {
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
                    FormField(name, { name = it }, if (kind == WorkspaceFormKind.Organization) "Razón social *" else "Nombre de la sede *", fields[if (kind == WorkspaceFormKind.Organization) "legalName" else "name"], !state.saving)
                    if (kind == WorkspaceFormKind.Organization) {
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
                    WorkspaceFormKind.Organization -> onOrganization(Organization(id, name, trade, business, tax, contactName, phone, email))
                    WorkspaceFormKind.Location -> onLocation(Location(id, name, address, district, phone))
                    WorkspaceFormKind.Member -> onMember(email, role, if (role == "ADMIN") emptyList() else locations)
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
