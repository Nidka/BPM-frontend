package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Opciones de presentación. La funcionalidad conserva la autorización y la selección. */
data class BpmChoiceOption(val id: String, val label: String)

/** Selector paginado común a programación, revisión de controles y operación diaria. */
@Composable
fun BpmPagedChoiceSelector(
    options: List<BpmChoiceOption>,
    selectedId: String?,
    selectedDescription: String,
    query: String,
    page: Int,
    loading: Boolean,
    failed: Boolean,
    previous: Boolean,
    next: Boolean,
    enabled: Boolean,
    searchLabel: String,
    emptyMessage: String,
    onSearch: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onSelect: (BpmChoiceOption) -> Unit,
) {
    BpmSearchField(query, onSearch, searchLabel, enabled = enabled)
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) BpmSecondaryButton(onClick = onRetry) { Text("Reintentar consulta") }
    else {
        if (options.isEmpty()) Text(emptyMessage)
        if (options.isNotEmpty())
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp).selectableGroup()) {
                items(options, key = { it.id }) { option ->
                    BpmChoiceRow(option.label, selectedId == option.id, enabled) {
                        onSelect(option)
                    }
                }
            }
        if (selectedDescription.isNotBlank())
            Text(selectedDescription, style = MaterialTheme.typography.bodySmall)
        PageNavigation(page, previous, next, loading || !enabled, onPrevious, onNext)
    }
}
