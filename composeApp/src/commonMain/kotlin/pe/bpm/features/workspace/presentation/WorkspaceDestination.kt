package pe.bpm.features.workspace.presentation

/** Rutas públicas estables; los roles se leen desde la API y nunca desde la URL. */
enum class WorkspaceDestination(val route: String, val label: String) {
    Home("inicio", "Inicio"),
    Learn("aprender", "Aprender"),
    Operations("control", "Control"),
    Supervision("seguimiento", "Seguimiento"),
    Team("equipo", "Equipo"),
    Company("empresa", "Empresa"),
    Tasks("tareas", "Mis tareas"),
    Account("cuenta", "Mi cuenta");

    companion object {
        fun fromRoute(route: String?) = entries.firstOrNull { it.route == route }
    }
}

/** Política de presentación; el backend autoriza cada operación empresarial. */
fun workspaceDestinations(role: String?, platformAdmin: Boolean): List<WorkspaceDestination> {
    return when {
        platformAdmin -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Supervision, WorkspaceDestination.Team, WorkspaceDestination.Company, WorkspaceDestination.Account)
        role == "ADMIN" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Supervision, WorkspaceDestination.Team, WorkspaceDestination.Company, WorkspaceDestination.Account)
        role == "CHEF" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Team, WorkspaceDestination.Learn, WorkspaceDestination.Account)
        role == "WORKER" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Tasks, WorkspaceDestination.Learn, WorkspaceDestination.Account)
        else -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Account)
    }
}

/**
 * Barra principal de máximo cinco entradas: primero se omite Aprender y luego Equipo, que el ADMIN
 * sigue alcanzando desde Empresa («Cuentas y equipo»). Todas las rutas disponibles siguen permitidas.
 */
fun workspacePrimaryDestinations(available: List<WorkspaceDestination>): List<WorkspaceDestination> {
    var primary = available
    if (primary.size > 5) primary = primary.filter { it != WorkspaceDestination.Learn }
    if (primary.size > 5) primary = primary.filter { it != WorkspaceDestination.Team }
    return primary
}

/**
 * El ADMIN miembro opera como el CHEF; las sedes que se ofrecen vienen del servidor (todas las activas
 * de la empresa para el ADMIN, solo las asignadas para el CHEF). SUPER_ADMIN sin membresía no opera.
 */
object OperatingScope {
    fun canOperate(role: String?, platformAdmin: Boolean): Boolean =
        WorkspaceDestination.Operations in workspaceDestinations(role, platformAdmin)
}
