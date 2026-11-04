package pe.bpm.features.incidents.presentation

import pe.bpm.features.incidents.domain.*

data class IncidentPage<T>(
    val rows: List<T> = emptyList(),
    val query: String = "",
    val token: String = "",
    val next: String = "",
    val previous: List<String> = emptyList(),
    val loading: Boolean = false,
    val failure: IncidentsFailure? = null,
) {
    val number
        get() = previous.size + 1
}

enum class IncidentPicker {
    CATALOG,
    ASSIGNEE,
}

data class IncidentsState(
    val userId: String? = null,
    val organizationId: String? = null,
    val siteId: String? = null,
    val role: String = "",
    val timeZone: String = "",
    val authorized: Boolean = false,
    val loading: Boolean = false,
    val failure: IncidentsFailure? = null,
    val catalogMode: Boolean = false,
    val status: IncidentStatus? = null,
    val incidents: IncidentPage<IncidentSummary> = IncidentPage(),
    val catalog: IncidentPage<IncidentCatalog> = IncidentPage(),
    val detail: IncidentDetail? = null,
    val picker: IncidentPicker? = null,
    val choices: IncidentPage<IncidentAssignee> = IncidentPage(),
    val catalogChoices: IncidentPage<IncidentCatalog> = IncidentPage(),
    val history: IncidentPage<IncidentEvent> = IncidentPage(),
    val showHistory: Boolean = false,
)
