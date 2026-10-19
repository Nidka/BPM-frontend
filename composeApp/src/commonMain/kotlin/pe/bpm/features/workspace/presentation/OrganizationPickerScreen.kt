package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.Organization
import pe.bpm.features.workspace.domain.WorkspaceError

/** Selección de contexto con ruta propia; no mezcla una lista de botones con el contenido empresarial. */
@Composable internal fun OrganizationPickerScreen(
    organizations: List<Organization>, selectedId: String?, loading: Boolean, errors: List<WorkspaceError>,
    canCreate: Boolean, onSelect: (String) -> Unit, onCreate: () -> Unit, onUserActivity: () -> Unit,
    notice: @Composable () -> Unit, noticeVisible: Boolean,
    page: WorkspacePageState, onSearch: (String) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onRetry: () -> Unit,
) {
    val query = page.request.query
    val scroll = rememberLazyListState()
    LaunchedEffect(noticeVisible) { if (noticeVisible) scroll.scrollToItem(0) }
    val matches = organizations
    BoxWithConstraints(Modifier.widthIn(max = 640.dp).heightIn(max = 680.dp).fillMaxWidth()) {
    val shortWindow = maxHeight < 480.dp
    BpmCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cambiar organización", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (canCreate && shortWindow) TextButton(onClick = onCreate, enabled = !loading,
                modifier = Modifier.semantics { contentDescription = "Crear organización" }) { Text("Crear") }
        }
        if (!shortWindow) Text("Elige el espacio de trabajo al que quieres entrar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        BpmTextField(query, { onUserActivity(); onSearch(it.take(160)) }, "Buscar organización", enabled = !loading)
        Errors(errors + page.errors)
        if (page.errors.isNotEmpty()) TextButton(onClick = onRetry) { Text("Reintentar") }
        if (loading || page.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "notice") { notice() }
            if (matches.isEmpty() && !loading && !page.loading && errors.isEmpty() && page.errors.isEmpty()) item {
                Text(if (query.isEmpty()) "No tienes organizaciones asignadas." else "No encontramos coincidencias. Prueba con otro nombre.",
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            items(matches, key = { it.id }) { organization ->
                OrganizationRow(organization, organization.id == selectedId, !loading && !page.loading) { onSelect(organization.id) }
            }
        }
        PageNavigation(page.number, page.previous.isNotEmpty(), page.nextToken.isNotEmpty(), loading || page.loading || page.errors.isNotEmpty(), onPrevious, onNext)
        if (canCreate && !shortWindow) {
            HorizontalDivider()
            OutlinedButton(onClick = onCreate, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Crear organización") }
        }
    }
    }
}

@Composable private fun OrganizationRow(organization: Organization, current: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        Surface(onClick = onSelect, enabled = enabled, interactionSource = interactions,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent),
            color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics {
                selected = current
                if (current) stateDescription = "Organización actual"
            }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BpmIcon(BpmSymbol.Company, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(organization.name, fontWeight = FontWeight.SemiBold)
                    if (organization.name != organization.legalName) Text(organization.legalName,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (current) BpmIcon(BpmSymbol.Check, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
