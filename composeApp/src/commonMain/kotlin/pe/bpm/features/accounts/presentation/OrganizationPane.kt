package pe.bpm.features.accounts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.accounts.domain.*

/** Datos de la organización (con tipo de establecimiento) y de cada sede, con filtro de estado. */
@Composable
internal fun OrganizationPane(viewModel: OrganizationViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        AccountMessages(state.failure, state.notice)
        if (state.loading && state.organization == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.organizationForm != null -> OrganizationFormCard(state.organizationForm!!, state.busy, viewModel)
            state.locationForm != null -> LocationFormCard(state.locationForm!!, state.busy, viewModel)
            else -> state.organization?.let { organization ->
                BpmDetailPane(organization.tradeName.ifBlank { organization.legalName }, subtitle = organization.legalName,
                    actions = { BpmSecondaryButton(onClick = viewModel::editOrganization, enabled = !state.busy) { Text("Editar organización") } }) {
                    BpmSection("Datos de la organización") {
                        BpmDetailFields(listOf(
                            BpmDetailField("Tipo de establecimiento", organization.businessType.label),
                            BpmDetailField("RUC / identificación fiscal", organization.taxId),
                            BpmDetailField("Persona de contacto", organization.contactName),
                            BpmDetailField("Correo de contacto", organization.contactEmail),
                            BpmDetailField("Teléfono de contacto", organization.contactPhone),
                        ))
                    }
                }
            }
        }
        Text("Sedes", style = MaterialTheme.typography.titleLarge)
        BpmListToolbar(
            filters = { BpmFilterChips(listOf(RecordStatus.ACTIVE to "Activas", RecordStatus.INACTIVE to "Inactivas"), state.locationStatus, !state.busy, viewModel::selectLocationStatus) },
            search = { m -> BpmSearchField(state.locations.request.query, viewModel::searchLocations, "Buscar sedes", m, enabled = !state.busy, loading = state.locations.loading) },
        )
        PagedBlock(state.locations, empty = { EmptyState("Sin sedes para mostrar", "No hay sedes con este filtro. Puedes agregar una desde la sección Empresa.") },
            onRetry = viewModel::retryLocations, onPrevious = viewModel::previousLocations, onNext = viewModel::nextLocations) {
            BpmGridLayout(state.locations.items) { location, cardModifier ->
                BpmRecordCard(location.name, cardModifier,
                    status = if (location.status == RecordStatus.ACTIVE) BpmRecordStatus("Activa", BpmTone.Success) else BpmRecordStatus("Inactiva", BpmTone.Neutral),
                    subtitle = listOf(location.address, location.district).filter { it.isNotBlank() }.joinToString(" · "),
                    details = listOfNotNull(location.contactPhone.takeIf { it.isNotBlank() }, "Zona horaria: ${location.timezone}"),
                    actionLabel = "Editar sede", onAction = { viewModel.editLocation(location) }, enabled = !state.busy)
            }
        }
    }
}

@Composable private fun OrganizationFormCard(form: OrganizationForm, busy: Boolean, viewModel: OrganizationViewModel) {
    val value = form.value
    BpmFormLayout(actions = { BpmFormActions("Guardar organización", viewModel::saveOrganization, busy = busy, onCancel = viewModel::cancelOrganization) }) {
    BpmSection("Editar organización") {
        BpmTextField(value.legalName, { v -> viewModel.changeOrganization { it.copy(legalName = v) } }, "Razón social *", Modifier.fillMaxWidth(), error = form.fields["legalName"], enabled = !busy, rule = AccountsRules.legalName)
        BpmTextField(value.tradeName, { v -> viewModel.changeOrganization { it.copy(tradeName = v) } }, "Nombre comercial", Modifier.fillMaxWidth(), error = form.fields["tradeName"], enabled = !busy, rule = AccountsRules.tradeName)
        BpmTextField(value.taxId, { v -> viewModel.changeOrganization { it.copy(taxId = v) } }, "RUC / identificación fiscal", Modifier.fillMaxWidth(), error = form.fields["taxId"], enabled = !busy, rule = AccountsRules.taxId)
        Text("Tipo de establecimiento *", style = MaterialTheme.typography.labelLarge)
        BusinessTypeChips(value.businessType, !busy) { type -> viewModel.changeOrganization { it.copy(businessType = type) } }
        form.fields["businessType"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BpmTextField(value.contactName, { v -> viewModel.changeOrganization { it.copy(contactName = v) } }, "Persona de contacto", Modifier.fillMaxWidth(), error = form.fields["contactName"], enabled = !busy,
            inputKind = BpmInputKind.PersonName, rule = AccountsRules.contactName)
        BpmTextField(value.contactEmail, { v -> viewModel.changeOrganization { it.copy(contactEmail = v) } }, "Correo de contacto", Modifier.fillMaxWidth(), error = form.fields["contactEmail"], enabled = !busy,
            inputKind = BpmInputKind.Email, rule = AccountsRules.contactEmail)
        BpmTextField(value.contactPhone, { v -> viewModel.changeOrganization { it.copy(contactPhone = v) } }, "Teléfono de contacto", Modifier.fillMaxWidth(), error = form.fields["contactPhone"], enabled = !busy,
            inputKind = BpmInputKind.Phone, rule = AccountsRules.contactPhone)
    }
    }
}

@Composable private fun BusinessTypeChips(selected: BusinessType, enabled: Boolean, onSelect: (BusinessType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BusinessType.entries.forEach { type -> BpmFilterChip(selected = selected == type, onClick = { onSelect(type) }, enabled = enabled, label = { Text(type.label) }) }
    }
}

@Composable private fun LocationFormCard(form: LocationForm, busy: Boolean, viewModel: OrganizationViewModel) {
    val value = form.value
    BpmFormLayout(actions = { BpmFormActions("Guardar sede", viewModel::saveLocation, busy = busy, onCancel = viewModel::cancelLocation) }) {
    BpmSection("Editar sede") {
        BpmTextField(value.name, { v -> viewModel.changeLocation { it.copy(name = v) } }, "Nombre de la sede *", Modifier.fillMaxWidth(), error = form.fields["name"], enabled = !busy, rule = AccountsRules.locationName)
        BpmShortFields(
            { m -> BpmTextField(value.address, { v -> viewModel.changeLocation { it.copy(address = v) } }, "Dirección *", m, error = form.fields["address"], enabled = !busy, rule = AccountsRules.address) },
            { m -> BpmTextField(value.district, { v -> viewModel.changeLocation { it.copy(district = v) } }, "Distrito", m, error = form.fields["district"], enabled = !busy, rule = AccountsRules.district) })
        BpmTextField(value.contactPhone, { v -> viewModel.changeLocation { it.copy(contactPhone = v) } }, "Teléfono de contacto", Modifier.fillMaxWidth(), error = form.fields["contactPhone"], enabled = !busy,
            inputKind = BpmInputKind.Phone, rule = AccountsRules.contactPhone)
        BpmTextField(value.timezone, { v -> viewModel.changeLocation { it.copy(timezone = v) } }, "Zona horaria *", Modifier.fillMaxWidth(), error = form.fields["timezone"], enabled = !busy,
            placeholder = "America/Lima", supportingText = "Nombre de zona, por ejemplo America/Lima.")
        Text("Estado", style = MaterialTheme.typography.labelLarge)
        BpmChoiceRow("Activa", value.status == RecordStatus.ACTIVE, !busy, "Aparece para asignar a las personas del equipo.") { viewModel.changeLocation { it.copy(status = RecordStatus.ACTIVE) } }
        BpmChoiceRow("Inactiva", value.status == RecordStatus.INACTIVE, !busy, "No se borra ni se reasigna a nadie, pero ya no puede asignarse.") { viewModel.changeLocation { it.copy(status = RecordStatus.INACTIVE) } }
    }
    }
}
