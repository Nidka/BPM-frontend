package pe.bpm.features.accounts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.accounts.domain.*

/** Mensajes de éxito y error que los lectores de pantalla anuncian sin quitar el foco. */
@Composable internal fun AccountMessages(failure: AccountFailure?, notice: String?) {
    failure?.let { Text(accountFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable internal fun <T> StatusFilter(options: List<Pair<T, String>>, selected: T, enabled: Boolean, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) -> FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled, label = { Text(label) }) }
    }
}

/** Lista de una página con carga, error reintentable y navegación sin total inventado. */
@Composable internal fun <T> PagedBlock(page: AccountPage<T>, empty: @Composable () -> Unit, onRetry: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            page.loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Cargando…") }
            page.failure != null -> {
                Text(accountFailureMessage(page.failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                BpmSecondaryButton(onClick = onRetry) { Text("Reintentar") }
            }
            page.items.isEmpty() -> empty()
            else -> content()
        }
        PageNavigation(page.number, page.previous.isNotEmpty(), page.nextToken.isNotEmpty(), page.loading || page.failure != null, onPrevious, onNext)
    }
}

@Composable internal fun RolePicker(selected: MemberRole, enabled: Boolean, onSelect: (MemberRole) -> Unit, error: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rol", style = MaterialTheme.typography.labelLarge)
        MemberRole.entries.forEach { role ->
            BpmChoiceRow(role.label, selected == role, enabled,
                supportingText = if (role == MemberRole.ADMIN) "Ve y administra todas las sedes." else "Trabaja solo en las sedes que elijas.") { onSelect(role) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable internal fun LocationPicker(title: String, locations: List<AccountLocation>, failure: AccountFailure?, selected: Set<String>,
    enabled: Boolean, onToggle: (String) -> Unit, error: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        when {
            failure != null -> Text(accountFailureMessage(failure), color = MaterialTheme.colorScheme.error)
            locations.isEmpty() -> Text("No hay sedes activas. Activa o agrega una sede antes de asignarla.")
            else -> locations.forEach { location -> BpmCheckboxRow(location.name, location.id in selected, enabled) { onToggle(location.id) } }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

/** Confirmación en línea (sin ventana emergente) para acciones con efectos que conviene entender. */
@Composable internal fun ConfirmCard(title: String, busy: Boolean, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit,
    confirmEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedButton(if (busy) "Guardando…" else confirmLabel, onConfirm, Modifier.fillMaxWidth(), enabled = !busy && confirmEnabled)
        BpmSecondaryButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}

internal fun roleText(role: MemberRole) = role.label
internal fun locationNames(ids: List<String>, known: List<AccountLocation>): String {
    if (ids.isEmpty()) return "Sin sedes asignadas"
    val byId = known.associateBy { it.id }
    val names = ids.map { byId[it]?.name ?: "Sede inactiva" }
    return names.joinToString(", ")
}
