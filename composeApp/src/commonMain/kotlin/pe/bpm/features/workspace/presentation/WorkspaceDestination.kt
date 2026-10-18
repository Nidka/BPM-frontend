package pe.bpm.features.workspace.presentation

/** Stable, non-sensitive URL fragments. Roles are read from the API, never from routes. */
enum class WorkspaceDestination(val route: String, val label: String, val mark: String) {
    Home("inicio", "Inicio", "⌂"),
    Learn("aprender", "Aprender", "◇"),
    Operations("control", "Control", "✓"),
    Team("equipo", "Equipo", "◎"),
    Company("empresa", "Empresa", "▦"),
    Account("cuenta", "Mi cuenta", "○");

    companion object {
        fun fromRoute(route: String?) = entries.firstOrNull { it.route == route }
    }
}

/** Presentation policy only. The backend still authorizes every business operation. */
fun workspaceDestinations(role: String?, platformAdmin: Boolean): List<WorkspaceDestination> {
    return when {
        platformAdmin -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Team, WorkspaceDestination.Company, WorkspaceDestination.Account)
        role == "ADMIN" -> WorkspaceDestination.entries.toList()
        role == "CHEF" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Team, WorkspaceDestination.Learn, WorkspaceDestination.Account)
        role == "WORKER" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Learn, WorkspaceDestination.Account)
        else -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Account)
    }
}

fun workspacePrimaryDestinations(available: List<WorkspaceDestination>): List<WorkspaceDestination> =
    if (available.size > 5) available.filter { it != WorkspaceDestination.Learn } else available
