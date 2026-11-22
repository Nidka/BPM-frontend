package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.*
import pe.bpm.core.domain.InputRule

internal enum class WorkspaceFormKind(val route: String, val destination: WorkspaceDestination) {
    Organization("empresa/nueva-organizacion", WorkspaceDestination.Company),
    Location("empresa/nueva-sede", WorkspaceDestination.Company),
    Member("equipo/agregar-persona", WorkspaceDestination.Team);

    fun isAllowed(platformAdmin: Boolean, canManage: Boolean) =
        if (this == Organization) platformAdmin else canManage
}

@Composable internal fun WorkspaceForm(kind: WorkspaceFormKind, state: WorkspaceState, onClose: () -> Unit,
    onSubmit: () -> Unit, formState: WorkspaceFormState, onRetryAccount: () -> Unit, onSelectAccount: (String) -> Unit,
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
    val check = formState.memberSearch
    val fields = (state.errors + check.errors).flatMap { it.fields.entries }.associate { it.key to it.value }
    LaunchedEffect(state.errors) {
        val aliases = mapOf("legalName" to "name", "tradeName" to "trade", "taxId" to "tax", "contactEmail" to "email", "contactPhone" to "phone")
        navigation.focusFirstError(fields.keys.map { aliases[it] ?: it })
    }
    // Persona y organización piden varias decisiones: formulario por pasos (specs/LAYOUT.md §4.7). La sede sigue en una página.
    if (kind != WorkspaceFormKind.Location) {
        WorkspaceStepForm(kind, state, formState, draft, fields, saving, navigation, onClose, onSubmit, onRetryAccount, onSelectAccount,
            onEdit, onDiscardDraft, onRetryDraft, onUserActivity, onPageSearch, onPageNext, onPagePrevious, onPageRetry)
        return
    }
    Box(Modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth()) {
        val shortWindow = currentWindowHeight < 560.dp
        LaunchedEffect(state.errors, check.errors, shortWindow) {
            if (shortWindow && (state.errors.isNotEmpty() || check.errors.isNotEmpty())) formScroll.scrollTo(formScroll.maxValue)
        }
        // En horizontal o con teclado, las acciones no deben comprimir el contenido del formulario.
        BpmCard(Modifier.fillMaxWidth().trackUserActivity(onUserActivity)
            .then(if (shortWindow) Modifier.verticalScroll(formScroll) else Modifier)) {
            Column(if (shortWindow) Modifier else Modifier.weight(1f, fill = false).verticalScroll(formScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("CRECER EN EQUIPO")
                Text(when (kind) { WorkspaceFormKind.Organization -> "Nueva organización"; WorkspaceFormKind.Location -> "Nueva sede"; WorkspaceFormKind.Member -> "Agregar al equipo" }, style = MaterialTheme.typography.headlineSmall)
                if (formState.recovered) Text("Recuperamos tu borrador. Revisa los datos antes de guardar.")
                DraftSaveNotice(formState.localStatus, onRetryDraft)
                if (discarding) DiscardDraftConfirmation({ discarding = false }, onDiscardDraft, enabled = !saving)
                if (kind == WorkspaceFormKind.Member) {
                    Text("Busca a la persona por su correo y asígnale acceso al equipo.", style = MaterialTheme.typography.bodySmall)
                    MemberAccountField(draft.email, { value -> onEdit { it.copy(email = value) } }, check,
                        !saving, fields["email"], navigation["email"], onSelectAccount, onRetryAccount)
                    if (check.status == MemberEligibility.READY) {
                    Text("¿Qué podrá hacer?", style = BpmType.bodyStrong)
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
                        Text("¿En qué sedes trabajará?", style = BpmType.bodyStrong)
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
                    FormField(draft.name, { value -> onEdit { it.copy(name = value) } }, if (kind == WorkspaceFormKind.Organization) "Razón social *" else "Nombre de la sede *", fields[if (kind == WorkspaceFormKind.Organization) "legalName" else "name"], !saving, rule = if (kind == WorkspaceFormKind.Organization) WorkspaceInputRules.legalName else WorkspaceInputRules.locationName, navigation = navigation["name"])
                    if (kind == WorkspaceFormKind.Organization) {
                        BpmShortFields(
                            { m -> FormField(draft.trade, { value -> onEdit { it.copy(trade = value) } }, "Nombre comercial", fields["tradeName"], !saving, rule = WorkspaceInputRules.tradeName, navigation = navigation["trade"], modifier = m) },
                            { m -> FormField(draft.tax, { value -> onEdit { it.copy(tax = value) } }, "RUC / identificación fiscal", fields["taxId"], !saving, rule = WorkspaceInputRules.taxId, navigation = navigation["tax"], modifier = m) })
                        FormField(draft.contactName, { value -> onEdit { it.copy(contactName = value) } }, "Persona de contacto", fields["contactName"], !saving, BpmInputKind.PersonName, rule = WorkspaceInputRules.contactName, navigation = navigation["contactName"])
                        Text("Tipo de establecimiento *", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("RESTAURANT" to "Restaurante", "CAFETERIA" to "Cafetín", "CATERING" to "Catering", "OTHER_FOOD_SERVICE" to "Otro servicio de alimentos").forEach { (value, label) ->
                                BpmFilterChip(selected = draft.business == value, onClick = { onEdit { it.copy(business = value) } }, enabled = !saving, label = { Text(label) })
                            }
                        }
                        fields["businessType"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } else {
                        FormField(draft.address, { value -> onEdit { it.copy(address = value) } }, "Dirección *", fields["address"], !saving, rule = WorkspaceInputRules.address, navigation = navigation["address"])
                    }
                    // Campos cortos: dos columnas desde la clase media (distrito/teléfono o correo/teléfono).
                    BpmShortFields(
                        { m -> if (kind == WorkspaceFormKind.Location)
                            FormField(draft.district, { value -> onEdit { it.copy(district = value) } }, "Distrito", fields["district"], !saving, rule = WorkspaceInputRules.district, navigation = navigation["district"], modifier = m)
                        else FormField(draft.email, { value -> onEdit { it.copy(email = value) } }, "Correo de contacto", fields["contactEmail"], !saving, BpmInputKind.Email, rule = WorkspaceInputRules.contactEmail, navigation = navigation["email"], modifier = m) },
                        { m -> FormField(draft.phone, { value -> onEdit { it.copy(phone = value) } }, "Teléfono de contacto", fields["contactPhone"], !saving, BpmInputKind.Phone, rule = WorkspaceInputRules.contactPhone, navigation = navigation["phone"], modifier = m) })
                }
            }
            // La respuesta permanece junto a Guardar, fuera del área desplazable de campos.
            Errors(state.errors)
            if (formState.savedRemote) Text("El registro ya se guardó en el servidor. Estamos cerrando el borrador local.")
            // Acciones del formulario (specs/LAYOUT.md §4.4): «Volver» conserva el borrador; la principal dice qué crea.
            if (kind != WorkspaceFormKind.Member || check.status == MemberEligibility.READY)
                BpmFormActions(when (kind) { WorkspaceFormKind.Organization -> "Crear organización"; WorkspaceFormKind.Location -> "Crear sede"; WorkspaceFormKind.Member -> "Agregar al equipo" },
                    onSubmit, busy = saving, cancelLabel = "Volver",
                    enabled = !state.loading && !check.loading && (kind != WorkspaceFormKind.Member || draft.role == "ADMIN" || draft.locations.isNotEmpty()),
                    onCancel = if (state.saving || formState.busy) null else onClose)
            else BpmTextButton(onClick = onClose, enabled = !state.saving && !formState.busy, modifier = Modifier.heightIn(min = BpmSpacing.touch)) { Text("Volver y conservar borrador") }
            if (!formState.savedRemote) BpmTextButton(onClick = { discarding = true }, enabled = !saving, modifier = Modifier.heightIn(min = BpmSpacing.touch)) { Text("Descartar borrador") }
        }
    }
}
@Composable private fun FormField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean,
    inputKind: BpmInputKind = BpmInputKind.Text, rule: InputRule, navigation: BpmFieldNavigation, modifier: Modifier = Modifier) {
    BpmTextField(value, onValue, label, modifier, enabled = enabled, error = error,
        navigation = navigation, inputKind = inputKind, rule = rule)
}

/** Persona (cuenta → rol → sedes) y organización (empresa → contacto) como formulario por pasos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun WorkspaceStepForm(kind: WorkspaceFormKind, state: WorkspaceState, formState: WorkspaceFormState, draft: WorkspaceFormDraft,
    fields: Map<String, String>, saving: Boolean, navigation: BpmFormNavigation, onClose: () -> Unit, onSubmit: () -> Unit,
    onRetryAccount: () -> Unit, onSelectAccount: (String) -> Unit, onEdit: ((WorkspaceFormDraft) -> WorkspaceFormDraft) -> Unit,
    onDiscardDraft: () -> Unit, onRetryDraft: () -> Unit, onUserActivity: () -> Unit,
    onPageSearch: (WorkspaceResource, String) -> Unit, onPageNext: (WorkspaceResource) -> Unit,
    onPagePrevious: (WorkspaceResource) -> Unit, onPageRetry: (WorkspaceResource) -> Unit) {
    val check = formState.memberSearch
    var discarding by remember { mutableStateOf(false) }
    var step by androidx.compose.runtime.saveable.rememberSaveable(kind) { mutableIntStateOf(0) }
    // Un error del servidor vuelve al primer paso que lo tiene.
    LaunchedEffect(fields.keys) {
        val byStep = if (kind == WorkspaceFormKind.Member) listOf("email" in fields, "role" in fields, "locationIds" in fields)
        else listOf(fields.keys.any { it in setOf("legalName", "tradeName", "taxId", "businessType") },
            fields.keys.any { it in setOf("contactName", "contactEmail", "contactPhone") })
        BpmStepFormRules.firstStepWithError(byStep)?.let { step = it }
    }
    val steps = if (kind == WorkspaceFormKind.Member) buildList {
        add(BpmFormStep("¿A quién agregas?", description = "Busca a la persona por el correo con el que ingresa a BPM.",
            canContinue = check.status == MemberEligibility.READY, blockedHint = "Busca y elige una cuenta disponible para continuar.") {
            MemberAccountField(draft.email, { value -> onEdit { it.copy(email = value) } }, check,
                !saving, fields["email"], navigation["email"], onSelectAccount, onRetryAccount)
        })
        add(BpmFormStep("¿Qué podrá hacer?", description = "El rol define qué secciones verá en la app.") {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
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
            if (draft.role == "ADMIN") Text("Acceso administrativo a todas las sedes de esta organización.", style = MaterialTheme.typography.bodySmall)
        })
        if (draft.role != "ADMIN") add(BpmFormStep("¿En qué sedes trabajará?",
            description = "Sedes de ${state.workspace?.organization?.name.orEmpty()}. Marca las que le corresponden.",
            canContinue = draft.locations.isNotEmpty(), blockedHint = "Selecciona al menos una sede para continuar.") {
            Text(if (draft.locations.isEmpty()) "Ninguna sede seleccionada" else if (draft.locations.size == 1) "1 sede seleccionada" else "${draft.locations.size} sedes seleccionadas",
                style = MaterialTheme.typography.bodySmall)
            WorkspacePagedList(WorkspaceResource.Locations, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                state.workspace?.locations.orEmpty().forEach { location ->
                    BpmCheckboxRow(location.name, location.id in draft.locations, !saving) { checked ->
                        onEdit { it.copy(locations = if (checked) it.locations + location.id else it.locations - location.id) }
                    }
                }
                if (state.workspace?.locations.isNullOrEmpty()) Text(if (state.pages[WorkspaceResource.Locations]?.request?.query.isNullOrBlank()) "Esta organización aún no tiene sedes. Agrega una desde Empresa antes de asignar a esta persona." else "No encontramos sedes con ese nombre. Prueba otra búsqueda.")
            }
            fields["locationIds"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        })
    } else listOf(
        BpmFormStep("Datos de la empresa", description = "Como figuran en su registro tributario.",
            canContinue = draft.name.isNotBlank() && draft.business.isNotBlank(), blockedHint = "Escribe la razón social y elige el tipo de establecimiento.") {
            FormField(draft.name, { value -> onEdit { it.copy(name = value) } }, "Razón social *", fields["legalName"], !saving, rule = WorkspaceInputRules.legalName, navigation = navigation["name"])
            BpmShortFields(
                { m -> FormField(draft.trade, { value -> onEdit { it.copy(trade = value) } }, "Nombre comercial", fields["tradeName"], !saving, rule = WorkspaceInputRules.tradeName, navigation = navigation["trade"], modifier = m) },
                { m -> FormField(draft.tax, { value -> onEdit { it.copy(tax = value) } }, "RUC / identificación fiscal", fields["taxId"], !saving, rule = WorkspaceInputRules.taxId, navigation = navigation["tax"], modifier = m) })
            Text("Tipo de establecimiento *", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                listOf("RESTAURANT" to "Restaurante", "CAFETERIA" to "Cafetín", "CATERING" to "Catering", "OTHER_FOOD_SERVICE" to "Otro servicio de alimentos").forEach { (value, label) ->
                    BpmFilterChip(selected = draft.business == value, onClick = { onEdit { it.copy(business = value) } }, enabled = !saving, label = { Text(label) })
                }
            }
            fields["businessType"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        },
        BpmFormStep("¿Con quién hablamos?", description = "Persona de contacto de la empresa (opcional).") {
            FormField(draft.contactName, { value -> onEdit { it.copy(contactName = value) } }, "Persona de contacto", fields["contactName"], !saving, BpmInputKind.PersonName, rule = WorkspaceInputRules.contactName, navigation = navigation["contactName"])
            BpmShortFields(
                { m -> FormField(draft.email, { value -> onEdit { it.copy(email = value) } }, "Correo de contacto", fields["contactEmail"], !saving, BpmInputKind.Email, rule = WorkspaceInputRules.contactEmail, navigation = navigation["email"], modifier = m) },
                { m -> FormField(draft.phone, { value -> onEdit { it.copy(phone = value) } }, "Teléfono de contacto", fields["contactPhone"], !saving, BpmInputKind.Phone, rule = WorkspaceInputRules.contactPhone, navigation = navigation["phone"], modifier = m) })
        },
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).trackUserActivity(onUserActivity), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
        Text(if (kind == WorkspaceFormKind.Member) "Agregar al equipo" else "Nueva organización", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        if (formState.recovered) BpmInlineNotice("Recuperamos tu borrador. Revisa los datos antes de guardar.")
        DraftSaveNotice(formState.localStatus, onRetryDraft)
        if (discarding) DiscardDraftConfirmation({ discarding = false }, onDiscardDraft, enabled = !saving)
        BpmStepForm(steps, step, { step = it },
            submitLabel = if (kind == WorkspaceFormKind.Member) "Agregar al equipo" else "Crear organización",
            onSubmit = onSubmit, busy = saving,
            submitEnabled = !state.loading && !check.loading,
            onCancel = if (state.saving || formState.busy) null else onClose,
            header = { Errors(state.errors) })
        if (formState.savedRemote) Text("El registro ya se guardó en el servidor. Estamos cerrando el borrador local.")
        if (!formState.savedRemote) BpmTextButton(onClick = { discarding = true }, enabled = !saving, modifier = Modifier.heightIn(min = BpmSpacing.touch)) { Text("Descartar borrador") }
    }
}
