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
import androidx.compose.foundation.selection.selectableGroup
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
    onSubmit: () -> Unit, formState: WorkspaceFormState, onCheckAccount: () -> Unit,
    onEdit: ((WorkspaceFormDraft) -> WorkspaceFormDraft) -> Unit, onDiscardDraft: () -> Unit, onRetryDraft: () -> Unit, onUserActivity: () -> Unit = {},
    onPageSearch: (WorkspaceResource, String) -> Unit, onPageNext: (WorkspaceResource) -> Unit,
    onPagePrevious: (WorkspaceResource) -> Unit, onPageRetry: (WorkspaceResource) -> Unit) {
    if (formState.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val draft = formState.draft
    if (draft == null) { DraftSaveNotice(formState.localStatus, onRetryDraft); return }
    val navigation = rememberBpmFormNavigation(when (kind) {
        WorkspaceFormKind.Member -> listOf("email")
        WorkspaceFormKind.Organization -> listOf("name", "trade", "tax", "contactName", "email", "phone")
        WorkspaceFormKind.Location -> listOf("name", "address", "district", "phone")
    })
    val saving = state.saving || formState.busy || formState.savedRemote
    var discarding by remember { mutableStateOf(false) }
    val formScroll = rememberScrollState()
    val check = formState.memberCheck
    val fields = (state.errors + check.errors).flatMap { it.fields.entries }.associate { it.key to it.value }
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        val shortWindow = maxHeight < 500.dp
        LaunchedEffect(state.errors, check.errors, check.status, shortWindow) {
            if (shortWindow && (state.errors.isNotEmpty() || check.errors.isNotEmpty() || check.status != null)) formScroll.scrollTo(formScroll.maxValue)
        }
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
                    Text("Primero comprueba el correo de la persona. Después podrás asignarle su rol y sus sedes.", style = MaterialTheme.typography.bodySmall)
                    FormField(draft.email, { value -> onEdit { it.copy(email = value) } }, "Correo de la cuenta BPM", fields["email"], !saving, KeyboardType.Email, navigation = navigation["email"])
                    BpmSecondaryButton(onClick = onCheckAccount, enabled = !saving && !check.loading && draft.email.isNotBlank()) {
                        Text(if (check.loading) "Comprobando cuenta…" else if (check.status == MemberEligibility.READY) "Comprobar de nuevo" else "Comprobar cuenta")
                    }
                    if (check.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when (check.status) {
                        MemberEligibility.READY -> Text("Cuenta disponible. Ahora elige su rol y dónde trabajará.", color = MaterialTheme.colorScheme.primary)
                        MemberEligibility.ALREADY_MEMBER -> Text("Esta cuenta ya pertenece al equipo. Puedes consultarla en la lista de personas.")
                        MemberEligibility.UNAVAILABLE -> if (state.errors.isEmpty()) Text("Esta persona todavía no tiene una cuenta BPM disponible. Pídele que entre en Crear cuenta, confirme su correo e inicie sesión. Después comprueba este correo de nuevo.")
                        null -> Unit
                    }
                    if (check.status == MemberEligibility.READY) {
                    Text("¿Qué podrá hacer?", fontWeight = FontWeight.Bold)
                    Column(Modifier.selectableGroup()) {
                        listOf("ADMIN", "CHEF", "WORKER").forEach { choice ->
                            BpmChoiceRow(roleLabel(choice), draft.role == choice, !saving,
                                supportingText = when (choice) {
                                    "ADMIN" -> "Gestiona sedes, equipo y plantillas; supervisa las revisiones realizadas por el chef."
                                    "CHEF" -> "Realiza controles en las sedes asignadas y accede a capacitaciones."
                                    else -> "Accede a capacitaciones y a su avance de aprendizaje."
                                }) { onEdit { it.copy(role = choice) } }
                        }
                    }
                    fields["role"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (draft.role == "ADMIN") Text("Acceso administrativo a todas las sedes de esta organización.")
                    else {
                        Text("¿En qué sedes trabajará?", fontWeight = FontWeight.Bold)
                        Text("Sedes de ${state.workspace?.organization?.name.orEmpty()}. Marca las que le corresponden.", style = MaterialTheme.typography.bodySmall)
                        Text(if (draft.locations.isEmpty()) "Selecciona al menos una sede." else if (draft.locations.size == 1) "1 sede seleccionada" else "${draft.locations.size} sedes seleccionadas", style = MaterialTheme.typography.bodySmall)
                        WorkspacePagedList(WorkspaceResource.Locations, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                        state.workspace?.locations.orEmpty().forEach { location ->
                            BpmCheckboxRow(location.name, location.id in draft.locations, !saving) { checked ->
                                onEdit { it.copy(locations = if (checked) it.locations + location.id else it.locations - location.id) }
                            }
                        }
                        if (state.workspace?.locations.isNullOrEmpty()) Text(if (state.pages[WorkspaceResource.Locations]?.request?.query.isNullOrBlank()) "Esta organización aún no tiene sedes. Agrega una desde Empresa antes de asignar a esta persona." else "No encontramos sedes con ese nombre. Prueba otra búsqueda.")
                        }
                        fields["locationIds"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    }
                } else {
                    FormField(draft.name, { value -> onEdit { it.copy(name = value) } }, if (kind == WorkspaceFormKind.Organization) "Razón social *" else "Nombre de la sede *", fields[if (kind == WorkspaceFormKind.Organization) "legalName" else "name"], !saving, navigation = navigation["name"])
                    if (kind == WorkspaceFormKind.Organization) {
                        FormField(draft.trade, { value -> onEdit { it.copy(trade = value) } }, "Nombre comercial", fields["tradeName"], !saving, navigation = navigation["trade"])
                        FormField(draft.tax, { value -> onEdit { it.copy(tax = value) } }, "RUC / identificación fiscal", fields["taxId"], !saving, navigation = navigation["tax"])
                        FormField(draft.contactName, { value -> onEdit { it.copy(contactName = value) } }, "Persona de contacto", fields["contactName"], !saving, navigation = navigation["contactName"])
                        listOf("RESTAURANT" to "Restaurante", "CAFETERIA" to "Cafetería", "CATERING" to "Catering", "RELATED_SERVICE" to "Servicio afín").forEach { (value, label) ->
                            FilterChip(selected = draft.business == value, onClick = { onEdit { it.copy(business = value) } }, enabled = !saving, label = { Text(label) })
                        }
                        FormField(draft.email, { value -> onEdit { it.copy(email = value) } }, "Correo de contacto", fields["contactEmail"], !saving, KeyboardType.Email, navigation = navigation["email"])
                    } else {
                        FormField(draft.address, { value -> onEdit { it.copy(address = value) } }, "Dirección *", fields["address"], !saving, navigation = navigation["address"])
                        FormField(draft.district, { value -> onEdit { it.copy(district = value) } }, "Distrito", fields["district"], !saving, navigation = navigation["district"])
                    }
                    FormField(draft.phone, { value -> onEdit { it.copy(phone = value) } }, "Teléfono de contacto", fields["contactPhone"], !saving, KeyboardType.Phone, navigation = navigation["phone"])
                }
            }
            // La respuesta permanece junto a Guardar, fuera del área desplazable de campos.
            Errors(state.errors)
            Errors(check.errors)
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (formState.savedRemote) Text("El registro ya se guardó en el servidor. Estamos cerrando el borrador local.")
            if (kind != WorkspaceFormKind.Member || check.status == MemberEligibility.READY)
                RaisedButton(if (saving) "Guardando…" else if (kind == WorkspaceFormKind.Member) "Agregar al equipo" else "Guardar",
                    enabled = !saving && !state.loading && !check.loading &&
                        (kind != WorkspaceFormKind.Member || draft.role == "ADMIN" || draft.locations.isNotEmpty()), onClick = onSubmit)
            if (!formState.savedRemote) BpmTextButton(onClick = { discarding = true }, enabled = !saving) { Text("Descartar borrador") }
            BpmTextButton(onClick = onClose, enabled = !state.saving && !formState.busy, modifier = Modifier.fillMaxWidth()) { Text("Volver y conservar borrador") }
        }
    }
}
@Composable private fun FormField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, navigation: BpmFieldNavigation) {
    BpmTextField(value, onValue, label, enabled = enabled, error = error,
        navigation = navigation, keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = keyboard == KeyboardType.Text))
}
