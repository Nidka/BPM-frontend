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
                BpmCard(Modifier.fillMaxWidth()) {
                    Eyebrow("ORGANIZACIÓN")
                    Text(organization.tradeName.ifBlank { organization.legalName }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(organization.legalName)
                    Text("Tipo de establecimiento: ${organization.businessType.label}")
                    if (organization.taxId.isNotBlank()) Text("RUC / identificación fiscal: ${organization.taxId}")
                    if (organization.contactName.isNotBlank()) Text("Contacto: ${organization.contactName}")
                    if (organization.contactEmail.isNotBlank()) Text(organization.contactEmail)
                    if (organization.contactPhone.isNotBlank()) Text(organization.contactPhone)
                    BpmSecondaryButton(onClick = viewModel::editOrganization, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Editar organización") }
                }
            }
        }
        Text("Sedes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        StatusFilter(listOf(RecordStatus.ACTIVE to "Activas", RecordStatus.INACTIVE to "Inactivas"), state.locationStatus, !state.busy, viewModel::selectLocationStatus)
        BpmSearchField(state.locations.request.query, viewModel::searchLocations, "Buscar sedes", enabled = !state.busy, loading = state.locations.loading)
        PagedBlock(state.locations, empty = { EmptyState("Sin sedes para mostrar", "No hay sedes con este filtro. Puedes agregar una desde la sección Empresa.") },
            onRetry = viewModel::retryLocations, onPrevious = viewModel::previousLocations, onNext = viewModel::nextLocations) {
            BpmCardGrid(state.locations.items, minCardWidth = 320.dp, maxColumns = 3) { location, cardModifier ->
                BpmCard(cardModifier) {
                    Text(location.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(location.address)
                    if (location.district.isNotBlank()) Text(location.district)
                    if (location.contactPhone.isNotBlank()) Text(location.contactPhone)
                    Text("Zona horaria: ${location.timezone}", style = MaterialTheme.typography.bodySmall)
                    Eyebrow(if (location.status == RecordStatus.ACTIVE) "Activa" else "Inactiva")
                    BpmSecondaryButton(onClick = { viewModel.editLocation(location) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Editar sede") }
                }
            }
        }
    }
}

@Composable private fun OrganizationFormCard(form: OrganizationForm, busy: Boolean, viewModel: OrganizationViewModel) {
    val value = form.value
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Editar organización", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedButton(if (busy) "Guardando…" else "Guardar organización", viewModel::saveOrganization, Modifier.fillMaxWidth(), enabled = !busy)
        BpmSecondaryButton(onClick = viewModel::cancelOrganization, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}

@Composable private fun BusinessTypeChips(selected: BusinessType, enabled: Boolean, onSelect: (BusinessType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BusinessType.entries.forEach { type -> BpmFilterChip(selected = selected == type, onClick = { onSelect(type) }, enabled = enabled, label = { Text(type.label) }) }
    }
}

@Composable private fun LocationFormCard(form: LocationForm, busy: Boolean, viewModel: OrganizationViewModel) {
    val value = form.value
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Editar sede", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        BpmTextField(value.name, { v -> viewModel.changeLocation { it.copy(name = v) } }, "Nombre de la sede *", Modifier.fillMaxWidth(), error = form.fields["name"], enabled = !busy, rule = AccountsRules.locationName)
        BpmTextField(value.address, { v -> viewModel.changeLocation { it.copy(address = v) } }, "Dirección *", Modifier.fillMaxWidth(), error = form.fields["address"], enabled = !busy, rule = AccountsRules.address)
        BpmTextField(value.district, { v -> viewModel.changeLocation { it.copy(district = v) } }, "Distrito", Modifier.fillMaxWidth(), error = form.fields["district"], enabled = !busy, rule = AccountsRules.district)
        BpmTextField(value.contactPhone, { v -> viewModel.changeLocation { it.copy(contactPhone = v) } }, "Teléfono de contacto", Modifier.fillMaxWidth(), error = form.fields["contactPhone"], enabled = !busy,
            inputKind = BpmInputKind.Phone, rule = AccountsRules.contactPhone)
        BpmTextField(value.timezone, { v -> viewModel.changeLocation { it.copy(timezone = v) } }, "Zona horaria *", Modifier.fillMaxWidth(), error = form.fields["timezone"], enabled = !busy,
            placeholder = "America/Lima", supportingText = "Nombre de zona, por ejemplo America/Lima.")
        Text("Estado", style = MaterialTheme.typography.labelLarge)
        BpmChoiceRow("Activa", value.status == RecordStatus.ACTIVE, !busy, "Aparece para asignar a las personas del equipo.") { viewModel.changeLocation { it.copy(status = RecordStatus.ACTIVE) } }
        BpmChoiceRow("Inactiva", value.status == RecordStatus.INACTIVE, !busy, "No se borra ni se reasigna a nadie, pero ya no puede asignarse.") { viewModel.changeLocation { it.copy(status = RecordStatus.INACTIVE) } }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedButton(if (busy) "Guardando…" else "Guardar sede", viewModel::saveLocation, Modifier.fillMaxWidth(), enabled = !busy)
        BpmSecondaryButton(onClick = viewModel::cancelLocation, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}
