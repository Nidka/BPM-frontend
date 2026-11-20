package pe.bpm.features.training.presentation

import pe.bpm.features.training.domain.AssignmentProgress
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
