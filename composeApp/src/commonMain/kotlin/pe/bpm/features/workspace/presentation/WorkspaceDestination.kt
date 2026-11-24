package pe.bpm.features.workspace.presentation

/** Rutas públicas estables; los roles se leen desde la API y nunca desde la URL. */
enum class WorkspaceDestination(val route: String, val label: String) {
    Home("inicio", "Inicio"),
    /** Indicadores de la organización elegida (solo super administración). */
    Indicators("indicadores", "Indicadores"),
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

/**
 * Etiqueta visible de un destino según el rol. El personal (WORKER) ve «Hoy», «Tareas» y «Perfil»; el jefe de cocina
 * (CHEF) ve «Turno», «Controles» y «Perfil»; el administrador de la organización (ADMIN) ve «Controles» y «Academia» y la antigua sección de
 * seguimiento se titula también «Controles» (sus revisiones del equipo son una sección de Controles); la super administración ve «Organizaciones»,
 * «Indicadores» y «Perfil» (con [platformAdmin]); el resto de roles conserva [WorkspaceDestination.label]. Solo cambia el
 * texto: la ruta y el control de acceso no dependen de él.
 */
fun destinationLabel(destination: WorkspaceDestination, role: String?, platformAdmin: Boolean = false): String = when {
    platformAdmin && destination == WorkspaceDestination.Home -> "Organizaciones"
    platformAdmin && destination == WorkspaceDestination.Account -> "Perfil"
    role == "CHEF" && destination == WorkspaceDestination.Home -> "Turno"
    role == "CHEF" && destination == WorkspaceDestination.Operations -> "Controles"
    role == "CHEF" && destination == WorkspaceDestination.Account -> "Perfil"
    role == "ADMIN" && destination == WorkspaceDestination.Operations -> "Controles"
    role == "ADMIN" && destination == WorkspaceDestination.Learn -> "Academia"
    role == "ADMIN" && destination == WorkspaceDestination.Supervision -> "Controles"
    role == "WORKER" && destination == WorkspaceDestination.Home -> "Hoy"
    role == "WORKER" && destination == WorkspaceDestination.Tasks -> "Tareas"
    role == "WORKER" && destination == WorkspaceDestination.Account -> "Perfil"
    else -> destination.label
}

/** Política de presentación; el backend autoriza cada operación empresarial. */
fun workspaceDestinations(role: String?, platformAdmin: Boolean): List<WorkspaceDestination> {
    return when {
        // Super administración: Organizaciones · Indicadores · Acceso (atajo) · Perfil; Empresa, Equipo y Seguimiento son las rutas de la organización abierta.
        platformAdmin -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Indicators, WorkspaceDestination.Supervision, WorkspaceDestination.Team, WorkspaceDestination.Company, WorkspaceDestination.Account)
        role == "ADMIN" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Team, WorkspaceDestination.Learn, WorkspaceDestination.Supervision, WorkspaceDestination.Company, WorkspaceDestination.Account)
        role == "CHEF" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Operations, WorkspaceDestination.Team, WorkspaceDestination.Learn, WorkspaceDestination.Account)
        role == "WORKER" -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Learn, WorkspaceDestination.Tasks, WorkspaceDestination.Account)
        else -> listOf(WorkspaceDestination.Home, WorkspaceDestination.Account)
    }
}

/**
 * Navegación principal de máximo cinco entradas. «Mi cuenta» sale de ella y va en el avatar de la cabecera;
 * (salvo en el personal, donde «Perfil» es un destino propio); si aún sobran, Equipo se omite (el ADMIN lo alcanza desde Empresa, «Equipo») y luego Aprender.
 * Todas las rutas disponibles siguen permitidas.
 */
fun workspacePrimaryDestinations(available: List<WorkspaceDestination>, role: String? = null): List<WorkspaceDestination> {
    // Super administración (la única con «Indicadores»): Organizaciones · Indicadores · (Acceso) · Perfil.
    if (WorkspaceDestination.Indicators in available) return listOf(WorkspaceDestination.Home, WorkspaceDestination.Indicators, WorkspaceDestination.Account)
    // El personal (WORKER) tiene «Perfil» como cuarto destino de la barra: Hoy · Aprender · Tareas · Perfil; el jefe de cocina
    // (CHEF), como quinto: Turno · Controles · Equipo · Aprender · Perfil.
    // El administrador de la organización (con Controles) navega Inicio · Controles · Equipo · Academia · Empresa: «Seguimiento»
    // deja de ser destino y sus revisiones viven dentro de Controles (la ruta sigue permitida).
    val adminMember = role == "ADMIN" && WorkspaceDestination.Operations in available
    var primary = available.filter { (it != WorkspaceDestination.Account || profileInNavigation(role, available)) && !(adminMember && it == WorkspaceDestination.Supervision) }
    if (primary.size > 5) primary = primary.filter { it != WorkspaceDestination.Team }
    if (primary.size > 5) primary = primary.filter { it != WorkspaceDestination.Learn }
    return primary
}

/** «Perfil» es un destino propio de la navegación (y no el avatar de la cabecera) para el personal y el jefe de cocina. */
fun profileInNavigation(role: String?, available: List<WorkspaceDestination>): Boolean =
    WorkspaceDestination.Account in available &&
        (role == "WORKER" && WorkspaceDestination.Tasks in available || role == "CHEF" && WorkspaceDestination.Operations in available)

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
 * (incidencias, investigación, acceso de investigación). Se colocan [after] la sección indicada.
 */
enum class WorkspaceShortcut(val label: String, val after: WorkspaceDestination) {
    Incidents("Incidencias", WorkspaceDestination.Operations),
    Research("Investigación", WorkspaceDestination.Home),
    Access("Acceso", WorkspaceDestination.Indicators),
}

/**
 * Atajos por rol: la persona investigadora ve Investigación; la super administración siempre ve Acceso (concesiones e investigación; el servidor autoriza).
 * Las incidencias del jefe de cocina ya no son un destino: viven como sección dentro de Turno y Controles.
 * El servidor sigue autorizando cada operación.
 */
fun workspaceShortcuts(role: String?, platformAdmin: Boolean, canResearch: Boolean, canManageGrants: Boolean): List<WorkspaceShortcut> = buildList {
    if (platformAdmin) add(WorkspaceShortcut.Access)
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
