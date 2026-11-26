package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmSummaryItem
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAchievements
import pe.bpm.features.training.domain.MyAssignment
import pe.bpm.features.training.domain.ProgressStatus

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

/** Tope de la barra mientras el curso no está completado: el 100 % del servidor cuenta solo lecciones, falta la evaluación. */
private const val OPEN_COURSE_CAP = 0.95f

private fun isFinished(assignment: MyAssignment) =
    assignment.status == AssignmentProgress.COMPLETED || assignment.progressStatus == ProgressStatus.COMPLETED

/**
 * Avance 0..1 del curso de una asignación, o nulo si el servidor no lo informa (0 o servidor anterior). Solo un curso
 * completado llega a 1: con todas las lecciones vistas pero la evaluación pendiente la barra queda en 95 %, para que no
 * parezca terminado.
 */
fun assignmentProgress(assignment: MyAssignment): Float? {
    if (isFinished(assignment)) return 1f
    return assignment.percent.takeIf { it > 0 }?.let { (it.coerceAtMost(100) / 100f).coerceAtMost(OPEN_COURSE_CAP) }
}

/** Frase bajo la barra: «40 % del curso» o, con las lecciones vistas, lo que falta («Falta la evaluación final»). */
fun assignmentProgressNote(assignment: MyAssignment): String? = when {
    isFinished(assignment) -> null
    assignment.percent >= 100 -> "Viste todas las lecciones. Falta la evaluación final."
    assignment.percent > 0 -> "${assignment.percent} % del curso"
    else -> null
}

/** Datos de la franja de resumen del alumno (racha · puntos · insignias) para Hoy y Aprender sin panel de apoyo. */
fun learnerSummary(achievements: MyAchievements): List<BpmSummaryItem> = buildList {
    achievements.streak?.let { add(BpmSummaryItem(if (it.currentDays == 1) "1 día" else "${it.currentDays} días", "Racha", BpmSymbol.Flame)) }
    add(BpmSummaryItem("${achievements.totals.experiencePoints} puntos", "Experiencia"))
    add(BpmSummaryItem("${achievements.earned.size} de ${achievements.achievements.size}", "Insignias", BpmSymbol.Award))
}

/** Asignaciones de «Asignadas a ti» sin la que ya se muestra en «Continuar» (no se repite el mismo curso). */
fun assignedWithoutContinue(assignments: List<MyAssignment>): List<MyAssignment> {
    val continued = continueAssignment(assignments)?.assignmentId
    return assignments.filter { it.assignmentId != continued }
}
