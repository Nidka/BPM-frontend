package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAssignment

private val MONTHS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

/**
 * Fecha legible y corta a partir de un instante ISO-8601 («2026-11-25T10:00:00Z» → «25 nov»).
 * Usa solo la parte de fecha del texto (sin zona horaria) para no desplazar el día. Si no se puede interpretar,
 * devuelve la parte de fecha tal cual llegó; nulo o vacío → cadena vacía.
 */
fun readableDate(instant: String?): String {
    val date = instant?.substringBefore('T').orEmpty()
    val parts = date.split('-')
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    if (parts.size != 3 || month == null || day == null || month !in 1..12) return date
    return "$day ${MONTHS[month - 1]}"
}

/**
 * Asignación que se propone como «siguiente paso»: primero las que están en curso, luego las vencidas y por
 * último las pendientes; dentro de cada grupo la que vence antes (sin fecha al final). Las completadas no cuentan.
 */
fun nextAssignment(assignments: List<MyAssignment>): MyAssignment? {
    val order = listOf(AssignmentProgress.IN_PROGRESS, AssignmentProgress.OVERDUE, AssignmentProgress.PENDING)
    for (status in order) {
        val match = assignments.filter { it.status == status }.minWithOrNull(compareBy<MyAssignment> { it.dueAt == null }.thenBy { it.dueAt.orEmpty() })
        if (match != null) return match
    }
    return null
}

/**
 * Asignaciones para la lista de «Hoy»: las no completadas primero (con el mismo criterio de [nextAssignment]:
 * en curso, vencidas, pendientes; por fecha límite) y después las completadas.
 */
fun assignmentsForToday(assignments: List<MyAssignment>, limit: Int = 3): List<MyAssignment> {
    val rank = mapOf(AssignmentProgress.IN_PROGRESS to 0, AssignmentProgress.OVERDUE to 1, AssignmentProgress.PENDING to 2, AssignmentProgress.COMPLETED to 3)
    return assignments
        .sortedWith(compareBy<MyAssignment> { rank.getValue(it.status) }.thenBy { it.dueAt == null }.thenBy { it.dueAt.orEmpty() })
        .take(limit)
}

/** Texto y tono del chip de estado/vencimiento de una asignación. */
fun assignmentChip(assignment: MyAssignment): Pair<String, BpmTone> = when {
    assignment.status == AssignmentProgress.OVERDUE -> "Vencida" to BpmTone.Danger
    assignment.status == AssignmentProgress.COMPLETED -> "Completada" to BpmTone.Success
    assignment.dueAt != null -> "Vence ${readableDate(assignment.dueAt)}" to BpmTone.Warning
    else -> assignmentStatusLabel(assignment.status) to BpmTone.Neutral
}
