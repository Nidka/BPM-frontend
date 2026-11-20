package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmSummaryItem
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAchievements
import pe.bpm.features.training.domain.MyAssignment

/**
 * Asignación para la tarjeta «Continuar» de Aprender: solo las que están en curso (la que vence antes primero,
 * sin fecha al final). No inventa un avance: si no hay ninguna en curso devuelve nulo.
 */
fun continueAssignment(assignments: List<MyAssignment>): MyAssignment? =
    nextAssignment(assignments.filter { it.status == AssignmentProgress.IN_PROGRESS })

/** Datos reales de una asignación para la tarjeta de curso: duración estimada y mejor puntaje si lo hay. */
fun assignmentMeta(assignment: MyAssignment): String {
    val parts = mutableListOf("${assignment.course.estimatedDurationMinutes} min")
    assignment.bestScore?.let { parts += "mejor puntaje $it" }
    return parts.joinToString(" · ")
}

/** Avance 0..1 del curso de una asignación, o nulo si el servidor no lo informa (0 o servidor anterior). */
fun assignmentProgress(assignment: MyAssignment): Float? =
    assignment.percent.takeIf { it > 0 }?.let { it.coerceAtMost(100) / 100f }

/** Datos de la franja de resumen del alumno (racha · XP · insignias) para Hoy y Aprender sin panel de apoyo. */
fun learnerSummary(achievements: MyAchievements): List<BpmSummaryItem> = buildList {
    achievements.streak?.let { add(BpmSummaryItem(if (it.currentDays == 1) "1 día" else "${it.currentDays} días", "Racha", BpmSymbol.Flame)) }
    add(BpmSummaryItem("${achievements.totals.experiencePoints} XP", "Experiencia"))
    add(BpmSummaryItem("${achievements.earned.size} de ${achievements.achievements.size}", "Insignias", BpmSymbol.Award))
}

/** Asignaciones de «Asignadas a ti» sin la que ya se muestra en «Continuar» (no se repite el mismo curso). */
fun assignedWithoutContinue(assignments: List<MyAssignment>): List<MyAssignment> {
    val continued = continueAssignment(assignments)?.assignmentId
    return assignments.filter { it.assignmentId != continued }
}
