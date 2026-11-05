package pe.bpm.features.checklists.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    BpmPagedChoiceSelector(
        options = locations.map { BpmChoiceOption(it.id, it.name) },
        selectedId = selected,
        selectedDescription =
            if (selected.isNullOrBlank()) ""
            else if (selectedName.isNotBlank()) "Sede elegida: $selectedName"
            else "Hay una sede seleccionada en el borrador.",
        query = query,
        page = page,
        loading = loading,
        failed = failed,
        previous = previous,
        next = next,
        enabled = enabled,
        searchLabel = "Buscar sede",
        emptyMessage = "No hay sedes disponibles para esta búsqueda.",
        onSearch = onSearch,
        onPrevious = onPrevious,
        onNext = onNext,
        onRetry = onRetry,
        onSelect = { option -> locations.firstOrNull { it.id == option.id }?.let(onSelect) },
    )
}
