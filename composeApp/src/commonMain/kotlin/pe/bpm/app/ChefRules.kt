package pe.bpm.app

import pe.bpm.features.checklists.domain.ChecklistAssignment
import pe.bpm.features.checklists.domain.ChecklistFrequency
import pe.bpm.features.checklists.domain.ShiftState
import pe.bpm.features.workspace.domain.Location
import pe.bpm.features.workspace.domain.Member

/*
 * Reglas puras de las pantallas del jefe de cocina (Turno, Controles y Equipo). Sin Compose ni red: el servidor decide
 * qué está vencido o abierto; el cliente solo cuenta, ordena y redacta.
 */

/** Motivo de la franja «Requiere atención» de Turno. */
internal enum class ChefAttentionKind { OverdueChecks, OpenIncidents, OverdueCourses }

/** Un motivo con su cantidad (siempre > 0). */
internal data class ChefAttention(val kind: ChefAttentionKind, val count: Int) {
    /** «1 control vencido», «2 controles vencidos», «1 incidencia abierta», «2 cursos vencidos tuyos». */
    val text: String
        get() = when (kind) {
            ChefAttentionKind.OverdueChecks -> if (count == 1) "1 control vencido" else "$count controles vencidos"
            ChefAttentionKind.OpenIncidents -> if (count == 1) "1 incidencia abierta" else "$count incidencias abiertas"
            ChefAttentionKind.OverdueCourses -> if (count == 1) "1 curso vencido tuyo" else "$count cursos vencidos tuyos"
        }
}

/**
 * Motivos de atención con los datos reales: controles vencidos y incidencias abiertas (panel de indicadores del servidor) y
 * cursos vencidos propios. Los valores nulos (aún sin cargar) o en cero no generan motivo; el orden es el de la gravedad.
 */
internal fun chefAttention(overdueChecks: Int?, openIncidents: Int?, overdueCourses: Int?): List<ChefAttention> = listOfNotNull(
    overdueChecks?.takeIf { it > 0 }?.let { ChefAttention(ChefAttentionKind.OverdueChecks, it) },
    openIncidents?.takeIf { it > 0 }?.let { ChefAttention(ChefAttentionKind.OpenIncidents, it) },
    overdueCourses?.takeIf { it > 0 }?.let { ChefAttention(ChefAttentionKind.OverdueCourses, it) },
)

// ---- Controles programados -------------------------------------------------------------------------------

/** Estado de un control programado en el periodo vigente, desde el punto de vista de [userId]. */
internal enum class ChefControlState { Pending, Late, MineInProgress, OthersInProgress, Done }

internal fun controlState(item: ChecklistAssignment, userId: String): ChefControlState {
    val run = item.run
    return when {
        run?.status == "SUBMITTED" -> ChefControlState.Done
        run?.status == "DRAFT" -> if (run.performedBy == userId) ChefControlState.MineInProgress else ChefControlState.OthersInProgress
        item.shiftState == ShiftState.CLOSED -> ChefControlState.Late
        else -> ChefControlState.Pending
    }
}

/** Controles activos del periodo: pendientes primero (vencidos, en curso, por hacer) y los hechos al final. */
internal fun controlOrder(items: List<ChecklistAssignment>, userId: String): List<ChecklistAssignment> {
    val rank = mapOf(ChefControlState.Late to 0, ChefControlState.MineInProgress to 1, ChefControlState.OthersInProgress to 2,
        ChefControlState.Pending to 3, ChefControlState.Done to 4)
    return items.filter { it.active }.sortedBy { rank.getValue(controlState(it, userId)) }
}

/** Controles hechos y total del periodo: el indicador «Controles 1/2». */
internal fun controlsDone(items: List<ChecklistAssignment>): Pair<Int, Int> {
    val active = items.filter { it.active }
    return active.count { it.run?.status == "SUBMITTED" } to active.size
}

/** Etiqueta del botón de la fila según el estado. */
internal fun controlAction(state: ChefControlState): String = when (state) {
    ChefControlState.MineInProgress -> "Continuar"
    ChefControlState.Done, ChefControlState.OthersInProgress -> "Ver"
    ChefControlState.Pending, ChefControlState.Late -> "Empezar"
}

/**
 * Línea de apoyo: vencido → «Venció a las 10:00» (hora del turno o de referencia); hecho → «Hecho por Pedro»; en curso →
 * «En curso · Pedro»; por hacer → «Hasta las 12:00» o el turno. [clock] da la hora local («9:12») de una fecha ISO.
 */
internal fun controlDetail(item: ChecklistAssignment, userId: String, clock: (String) -> String): String {
    val site = item.locationName
    val main = when (controlState(item, userId)) {
        ChefControlState.Done -> listOfNotNull("Hecho", item.run?.submittedAt?.let(clock)?.takeIf { it.isNotBlank() }).joinToString(" ") +
            (item.run?.performedByName?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        ChefControlState.MineInProgress -> "En curso · tu revisión"
        ChefControlState.OthersInProgress -> "En curso · ${item.run?.performedByName.orEmpty()}".trimEnd(' ', '·')
        ChefControlState.Late -> deadline(item)?.let { "Venció a las $it" } ?: "Vencido"
        ChefControlState.Pending -> deadline(item)?.let { "Hasta las $it" } ?: item.shiftName.ifBlank { frequencyText(item.frequency) }
    }
    return if (site.isBlank()) main else "$main · $site"
}

private fun deadline(item: ChecklistAssignment): String? = (item.shiftEndTime.ifBlank { item.dueTime }).takeIf { it.isNotBlank() }?.let { pe.bpm.core.presentation.displayClock(it) }

private fun frequencyText(value: ChecklistFrequency) = when (value) {
    ChecklistFrequency.ONCE -> "Una sola vez"
    ChecklistFrequency.DAILY -> "Cada día"
    ChecklistFrequency.WEEKLY -> "Cada semana"
    ChecklistFrequency.MONTHLY -> "Cada mes"
}

// ---- Equipo -------------------------------------------------------------------------------------------------

/** Nombre visible de un rol de la organización. */
internal fun memberRoleLabel(role: String): String = when (role) {
    "ADMIN" -> "Administrador"
    "CHEF" -> "Jefe de cocina"
    else -> "Personal"
}

/**
 * Personas del equipo del jefe: las que comparten alguna de sus sedes ([siteIds]) —un administrador ve todas— y no son
 * la propia persona. Orden alfabético sin distinguir mayúsculas. El servidor solo entrega la lista cuando el rol la permite.
 */
internal fun teamOf(members: List<Member>, siteIds: Set<String>, selfId: String): List<Member> =
    members.filter { it.id != selfId && it.role != "ADMIN" && it.locationIds.any { id -> id in siteIds } }
        .sortedBy { it.name.lowercase() }

/** Sedes de una persona restringidas a las del jefe: «Sede principal, Sede norte» o «Sin sede en común». */
internal fun memberSites(member: Member, sites: List<Location>): String =
    sites.filter { it.id in member.locationIds }.joinToString(", ") { it.name }.ifBlank { "Sin sede en común" }

/** «Personal · Sede principal»: el detalle de una fila cuando no hay avance por persona. */
internal fun memberLine(member: Member, sites: List<Location>): String = "${memberRoleLabel(member.role)} · ${memberSites(member, sites)}"
