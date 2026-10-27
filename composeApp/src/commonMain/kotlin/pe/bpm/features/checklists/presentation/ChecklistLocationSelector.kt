package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*

/**
 * Selección paginada compartida entre programación y revisión; recibe exclusivamente sedes
 * autorizadas.
 */
@Composable
internal fun ChecklistLocationSelector(
    locations: List<ChecklistRunLocation>,
    selected: String?,
    selectedName: String,
    query: String,
    page: Int,
    loading: Boolean,
    failed: Boolean,
    previous: Boolean,
    next: Boolean,
    enabled: Boolean,
    onSearch: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (ChecklistRunLocation) -> Unit,
) {
    BpmSearchField(query, onSearch, "Buscar sede", enabled = enabled)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) BpmSecondaryButton(onClick = onRetry) { Text("Reintentar sedes") }
    else {
        if (locations.isEmpty()) Text("No hay sedes disponibles para esta búsqueda.")
        if (locations.isNotEmpty())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).selectableGroup()) {
                items(locations, key = { it.id }) { site ->
                    BpmChoiceRow(site.name, selected == site.id, enabled) { onSelect(site) }
                }
            }
        if (selected != null && selected.isNotEmpty())
            Text(
                if (selectedName.isNotEmpty()) "Sede elegida: $selectedName"
                else "Hay una sede seleccionada en el borrador.",
                style = MaterialTheme.typography.bodySmall,
            )
        PageNavigation(page, previous, next, loading || !enabled, onPrevious, onNext)
    }
}
