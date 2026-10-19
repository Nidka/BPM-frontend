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

internal enum class WorkspaceFormKind(val route: String, val destination: WorkspaceDestination) {
    Organization("empresa/nueva-organizacion", WorkspaceDestination.Company),
    Location("empresa/nueva-sede", WorkspaceDestination.Company),
    Member("equipo/agregar-persona", WorkspaceDestination.Team);

    fun isAllowed(platformAdmin: Boolean, canManage: Boolean) =
        if (this == Organization) platformAdmin else canManage
}

@Composable internal fun WorkspaceForm(kind: WorkspaceFormKind, state: WorkspaceState, onClose: () -> Unit,
    onSubmit: () -> Unit, formState: WorkspaceFormState,
    onEdit: ((WorkspaceFormDraft) -> WorkspaceFormDraft) -> Unit, onDiscardDraft: () -> Unit, onRetryDraft: () -> Unit, onUserActivity: () -> Unit = {},
    onPageSearch: (WorkspaceResource, String) -> Unit, onPageNext: (WorkspaceResource) -> Unit,
    onPagePrevious: (WorkspaceResource) -> Unit, onPageRetry: (WorkspaceResource) -> Unit) {
    if (formState.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val draft = formState.draft
    if (draft == null) { DraftSaveNotice(formState.localStatus, onRetryDraft); return }
    val saving = state.saving || formState.busy || formState.savedRemote
    var discarding by remember { mutableStateOf(false) }
    val formScroll = rememberScrollState()
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
                if (formState.recovered) Text("Recuperamos tu borrador. Revisa los datos antes de guardar.")
                DraftSaveNotice(formState.localStatus, onRetryDraft)
                if (discarding) DiscardDraftConfirmation({ discarding = false }, onDiscardDraft, enabled = !saving)
                if (kind == WorkspaceFormKind.Member) {
                    Text("La persona debe registrarse y verificar su correo en BPM primero. No se envían invitaciones por correo.", style = MaterialTheme.typography.bodySmall)
                    FormField(draft.email, { value -> onEdit { it.copy(email = value) } }, "Correo de la cuenta BPM", fields["email"], !saving, onUserActivity, KeyboardType.Email)
                    listOf("ADMIN", "CHEF", "WORKER").forEach { choice -> FilterChip(selected = draft.role == choice, onClick = { onEdit { it.copy(role = choice) } }, enabled = !saving, label = { Text(roleLabel(choice)) }) }
                    fields["role"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (draft.role == "ADMIN") Text("Acceso administrativo a todas las sedes de esta organización.")
                    else {
                        Text("Sedes permitidas · ${draft.locations.size} seleccionadas", fontWeight = FontWeight.Bold)
                        WorkspacePagedList(WorkspaceResource.Locations, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                        state.workspace?.locations.orEmpty().forEach { location ->
                            BpmCheckboxRow(location.name, location.id in draft.locations, !saving) { checked ->
                                onEdit { it.copy(locations = if (checked) it.locations + location.id else it.locations - location.id) }
                            }
                        }
                        if (state.workspace?.locations.isNullOrEmpty()) Text("No hay sedes para esta búsqueda. Busca otro nombre o crea una sede si aún no existe.")
                        }
                        fields["locationIds"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    FormField(draft.name, { value -> onEdit { it.copy(name = value) } }, if (kind == WorkspaceFormKind.Organization) "Razón social *" else "Nombre de la sede *", fields[if (kind == WorkspaceFormKind.Organization) "legalName" else "name"], !saving, onUserActivity)
                    if (kind == WorkspaceFormKind.Organization) {
                        FormField(draft.trade, { value -> onEdit { it.copy(trade = value) } }, "Nombre comercial", fields["tradeName"], !saving, onUserActivity)
                        FormField(draft.tax, { value -> onEdit { it.copy(tax = value) } }, "RUC / identificación fiscal", fields["taxId"], !saving, onUserActivity)
                        FormField(draft.contactName, { value -> onEdit { it.copy(contactName = value) } }, "Persona de contacto", fields["contactName"], !saving, onUserActivity)
                        listOf("RESTAURANT" to "Restaurante", "CAFETERIA" to "Cafetería", "CATERING" to "Catering", "RELATED_SERVICE" to "Servicio afín").forEach { (value, label) ->
                            FilterChip(selected = draft.business == value, onClick = { onEdit { it.copy(business = value) } }, enabled = !saving, label = { Text(label) })
                        }
                        FormField(draft.email, { value -> onEdit { it.copy(email = value) } }, "Correo de contacto", fields["contactEmail"], !saving, onUserActivity, KeyboardType.Email)
                    } else {
                        FormField(draft.address, { value -> onEdit { it.copy(address = value) } }, "Dirección *", fields["address"], !saving, onUserActivity)
                        FormField(draft.district, { value -> onEdit { it.copy(district = value) } }, "Distrito", fields["district"], !saving, onUserActivity)
                    }
                    FormField(draft.phone, { value -> onEdit { it.copy(phone = value) } }, "Teléfono de contacto", fields["contactPhone"], !saving, onUserActivity, KeyboardType.Phone)
                }
                Errors(state.errors)
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (formState.savedRemote) Text("El registro ya se guardó en el servidor. Estamos cerrando el borrador local.")
            RaisedButton(if (saving) "Guardando…" else "Guardar", enabled = !saving && !state.loading, onClick = onSubmit)
            if (!formState.savedRemote) BpmTextButton(onClick = { discarding = true }, enabled = !saving) { Text("Descartar borrador") }
            BpmTextButton(onClick = onClose, enabled = !state.saving && !formState.busy, modifier = Modifier.fillMaxWidth()) { Text("Volver y conservar borrador") }
        }
    }
}
@Composable private fun FormField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean,
    onUserActivity: () -> Unit, keyboard: KeyboardType = KeyboardType.Text) {
    BpmTextField(value, { onUserActivity(); onValue(it) }, label, enabled = enabled, error = error,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = keyboard == KeyboardType.Text))
}
