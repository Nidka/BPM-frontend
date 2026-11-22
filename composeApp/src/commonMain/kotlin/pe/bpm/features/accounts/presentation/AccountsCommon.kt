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

/** Adapta una [AccountPage] al listado paginado del sistema (specs/LAYOUT.md §3.3). */
@Composable internal fun <T> PagedBlock(page: AccountPage<T>, empty: @Composable () -> Unit, onRetry: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit, content: @Composable ColumnScope.() -> Unit) =
    BpmPagedList(loading = page.loading, failure = page.failure?.let { accountFailureMessage(it) }, isEmpty = page.items.isEmpty(),
        pageNumber = page.number, canPrevious = page.previous.isNotEmpty(), canNext = page.nextToken.isNotEmpty(),
        onRetry = onRetry, onPrevious = onPrevious, onNext = onNext, empty = empty, content = content)

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

internal fun roleText(role: MemberRole) = role.label
internal fun locationNames(ids: List<String>, known: List<AccountLocation>): String {
    if (ids.isEmpty()) return "Sin sedes asignadas"
    val byId = known.associateBy { it.id }
    val names = ids.map { byId[it]?.name ?: "Sede inactiva" }
    return names.joinToString(", ")
}
