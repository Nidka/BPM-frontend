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
        role == "ADMIN" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Learn, WorkspaceDestination.Supervision, WorkspaceDestination.Team, WorkspaceDestination.Company, WorkspaceDestination.Account)
        role == "CHEF" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Learn, WorkspaceDestination.Account)
        role == "WORKER" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Tasks, WorkspaceDestination.Learn, WorkspaceDestination.Account)
        else -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Account)
    }
}

/**
 * Navegación principal de máximo cinco entradas. «Mi cuenta» sale de ella y va en el avatar de la cabecera;
 * si aún sobran, Equipo se omite (el ADMIN lo alcanza desde Empresa, «Cuentas y equipo») y luego Aprender.
 * Todas las rutas disponibles siguen permitidas.
 */
fun workspacePrimaryDestinations(available: List<WorkspaceDestination>): List<WorkspaceDestination> {
    var primary = available.filter { it != WorkspaceDestination.Account }
    if (primary.size > 5) primary = primary.filter { it != WorkspaceDestination.Team }
    if (primary.size > 5) primary = primary.filter { it != WorkspaceDestination.Learn }
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

/**
 * Accesos de la navegación principal que no son secciones propias sino atajos a pantallas existentes
 * (incidencias, investigación, empresas, acceso de investigación). Se colocan [after] la sección indicada.
 */
enum class WorkspaceShortcut(val label: String, val after: WorkspaceDestination) {
    Incidents("Incidencias", WorkspaceDestination.Operations),
    Research("Investigación", WorkspaceDestination.Home),
    Companies("Empresas", WorkspaceDestination.Home),
    Access("Acceso", WorkspaceDestination.Supervision),
}

/**
 * Atajos por rol: el jefe de cocina ve Incidencias como destino propio; la persona investigadora ve Investigación;
 * el equipo BPM ve Empresas y, si concede accesos, Acceso. El servidor sigue autorizando cada operación.
 */
fun workspaceShortcuts(role: String?, platformAdmin: Boolean, canResearch: Boolean, canManageGrants: Boolean): List<WorkspaceShortcut> = buildList {
    // Las incidencias son operación diaria: quien administra la plataforma no opera, aunque tenga una membresía de jefe de cocina.
    if (role == "CHEF" && !platformAdmin) add(WorkspaceShortcut.Incidents)
    if (platformAdmin) add(WorkspaceShortcut.Companies)
    if (platformAdmin && canManageGrants) add(WorkspaceShortcut.Access)
    else if (canResearch || canManageGrants) add(WorkspaceShortcut.Research)
}

/** Un elemento de la navegación principal: sección o atajo. */
sealed interface WorkspaceNavItem {
    data class Section(val destination: WorkspaceDestination) : WorkspaceNavItem
    data class Shortcut(val shortcut: WorkspaceShortcut) : WorkspaceNavItem
}

/**
 * Mezcla secciones y atajos en un máximo de cinco entradas. Si sobran, se omite primero Equipo y luego Aprender.
 */
fun workspaceNavigation(primary: List<WorkspaceDestination>, shortcuts: List<WorkspaceShortcut>): List<WorkspaceNavItem> {
    fun merge(sections: List<WorkspaceDestination>): List<WorkspaceNavItem> = sections.flatMap { section ->
        listOf<WorkspaceNavItem>(WorkspaceNavItem.Section(section)) + shortcuts.filter { it.after == section }.map { WorkspaceNavItem.Shortcut(it) }
    } + shortcuts.filter { it.after !in sections }.map { WorkspaceNavItem.Shortcut(it) }
    var sections = primary
    var items = merge(sections)
    for (drop in listOf(WorkspaceDestination.Team, WorkspaceDestination.Learn)) if (items.size > 5) {
        sections = sections.filter { it != drop }
        items = merge(sections)
    }
    return items.take(5)
}
