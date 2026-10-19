package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal enum class WorkspaceFormKind(val route: String, val destination: WorkspaceDestination) {
    Organization("empresa/nueva-organizacion", WorkspaceDestination.Company),
    Location("empresa/nueva-sede", WorkspaceDestination.Company),
    Member("equipo/agregar-persona", WorkspaceDestination.Team);

    fun isAllowed(platformAdmin: Boolean, canManage: Boolean) =
        if (this == Organization) platformAdmin else canManage
}

@OptIn(ExperimentalUuidApi::class)
@Composable internal fun WorkspaceForm(kind: WorkspaceFormKind, state: WorkspaceState, onClose: () -> Unit,
    onOrganization: (Organization) -> Unit, onLocation: (Location) -> Unit,
    onMember: (String, String, List<String>) -> Unit, onUserActivity: () -> Unit = {},
    sessionNotice: @Composable () -> Unit = {}, noticeVisible: Boolean = false,
    onPageSearch: (WorkspaceResource, String) -> Unit, onPageNext: (WorkspaceResource) -> Unit,
    onPagePrevious: (WorkspaceResource) -> Unit, onPageRetry: (WorkspaceResource) -> Unit) {
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
    var locations by remember { mutableStateOf(emptySet<String>()) }
    val formScroll = rememberScrollState()
    LaunchedEffect(noticeVisible) { if (noticeVisible) formScroll.scrollTo(0) }
    val fields = state.errors.flatMap { it.fields.entries }.associate { it.key to it.value }
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        val shortWindow = maxHeight < 500.dp
        // En horizontal o con teclado, las acciones no deben comprimir el contenido del formulario.
        BpmCard(Modifier.fillMaxWidth().trackUserActivity(onUserActivity)
            .then(if (shortWindow) Modifier.verticalScroll(formScroll) else Modifier)) {
            Column(if (shortWindow) Modifier else Modifier.weight(1f, fill = false).verticalScroll(formScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("CRECER EN EQUIPO")
                Text(when (kind) { WorkspaceFormKind.Organization -> "Nueva organización"; WorkspaceFormKind.Location -> "Nueva sede"; WorkspaceFormKind.Member -> "Agregar al equipo" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                sessionNotice()
                if (kind == WorkspaceFormKind.Member) {
                    Text("La persona debe registrarse y verificar su correo en BPM primero. No se envían invitaciones por correo.", style = MaterialTheme.typography.bodySmall)
                    FormField(email, { email = it }, "Correo de la cuenta BPM", fields["email"], !state.saving, onUserActivity, KeyboardType.Email)
                    listOf("ADMIN", "CHEF", "WORKER").forEach { choice -> FilterChip(selected = role == choice, onClick = { role = choice }, enabled = !state.saving, label = { Text(roleLabel(choice)) }) }
                    fields["role"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (role == "ADMIN") Text("Acceso administrativo a todas las sedes de esta organización.")
                    else {
                        Text("Sedes permitidas · ${locations.size} seleccionadas", fontWeight = FontWeight.Bold)
                        WorkspacePagedList(WorkspaceResource.Locations, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                        state.workspace?.locations.orEmpty().forEach { location ->
                            BpmCheckboxRow(location.name, location.id in locations, !state.saving) { checked ->
                                locations = if (checked) locations + location.id else locations - location.id
                            }
                        }
                        if (state.workspace?.locations.isNullOrEmpty()) Text("No hay sedes para esta búsqueda. Busca otro nombre o crea una sede si aún no existe.")
                        }
                        fields["locationIds"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    FormField(name, { name = it }, if (kind == WorkspaceFormKind.Organization) "Razón social *" else "Nombre de la sede *", fields[if (kind == WorkspaceFormKind.Organization) "legalName" else "name"], !state.saving, onUserActivity)
                    if (kind == WorkspaceFormKind.Organization) {
                        FormField(trade, { trade = it }, "Nombre comercial", fields["tradeName"], !state.saving, onUserActivity)
                        FormField(tax, { tax = it }, "RUC / identificación fiscal", fields["taxId"], !state.saving, onUserActivity)
                        FormField(contactName, { contactName = it }, "Persona de contacto", fields["contactName"], !state.saving, onUserActivity)
                        listOf("RESTAURANT" to "Restaurante", "CAFETERIA" to "Cafetería", "CATERING" to "Catering", "RELATED_SERVICE" to "Servicio afín").forEach { (value, label) ->
                            FilterChip(selected = business == value, onClick = { business = value }, enabled = !state.saving, label = { Text(label) })
                        }
                        FormField(email, { email = it }, "Correo de contacto", fields["contactEmail"], !state.saving, onUserActivity, KeyboardType.Email)
                    } else {
                        FormField(address, { address = it }, "Dirección *", fields["address"], !state.saving, onUserActivity)
                        FormField(district, { district = it }, "Distrito", fields["district"], !state.saving, onUserActivity)
                    }
                    FormField(phone, { phone = it }, "Teléfono de contacto", fields["contactPhone"], !state.saving, onUserActivity, KeyboardType.Phone)
                }
                Errors(state.errors)
            }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            RaisedButton(if (state.saving) "Guardando…" else "Guardar", enabled = !state.saving && !state.loading, onClick = {
                when (kind) {
                    WorkspaceFormKind.Organization -> onOrganization(Organization(id, name, trade, business, tax, contactName, phone, email))
                    WorkspaceFormKind.Location -> onLocation(Location(id, name, address, district, phone))
                    WorkspaceFormKind.Member -> onMember(email, role, if (role == "ADMIN") emptyList() else locations.toList())
                }
            })
            BpmTextButton(onClick = onClose, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
        }
    }
}
@Composable private fun FormField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean,
    onUserActivity: () -> Unit, keyboard: KeyboardType = KeyboardType.Text) {
    BpmTextField(value, { onUserActivity(); onValue(it) }, label, enabled = enabled, error = error,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = keyboard == KeyboardType.Text))
}
