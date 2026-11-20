package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmStepState
import pe.bpm.features.training.domain.ProgressStatus
import pe.bpm.features.training.domain.TrainingCourse

/**
 * Estado de cada lección para dibujar el temario del curso, en el mismo orden que `course.lessons`.
 *
 * **Es una aproximación.** El modelo no registra qué lecciones se completaron: solo guarda el estado del curso
 * y la última lección abierta (`lastLessonId`). Por eso se asume que el alumno avanza en orden:
 * - curso completado: todas las lecciones figuran como hechas;
 * - sin iniciar, o sin una última lección que siga publicada: la primera es la actual y el resto está pendiente;
 * - en curso: las anteriores a la última abierta figuran como hechas, esa es la actual y las siguientes están pendientes.
 *
 * Quien salte lecciones o vuelva atrás verá un avance distinto del real; cuando el backend exponga el avance por
 * lección esta función debe reemplazarse por ese dato.
 */
fun lessonStepStates(course: TrainingCourse): List<BpmStepState> {
    val lessons = course.lessons
    if (lessons.isEmpty()) return emptyList()
    if (course.progress.status == ProgressStatus.COMPLETED) return lessons.map { BpmStepState.Done }
    val last = lessons.indexOfFirst { it.id == course.progress.lastLessonId }
    if (course.progress.status == ProgressStatus.NOT_STARTED || last < 0) {
        return lessons.indices.map { if (it == 0) BpmStepState.Current else BpmStepState.Pending }
    }
    return lessons.indices.map { index ->
        when {
            index < last -> BpmStepState.Done
            index == last -> BpmStepState.Current
            else -> BpmStepState.Pending
        }
    }
}

/**
 * Texto del botón principal del curso: «Repasar el curso» si ya está completado, «Continuar lección n» si hay una
 * lección actual con avance (según [lessonStepStates]) y «Empezar el curso» en los demás casos.
 */
fun courseMainActionLabel(course: TrainingCourse): String {
    if (course.progress.status == ProgressStatus.COMPLETED) return "Repasar el curso"
    val started = course.progress.status != ProgressStatus.NOT_STARTED && course.lessons.any { it.id == course.progress.lastLessonId }
    if (!started) return "Empezar el curso"
    val current = lessonStepStates(course).indexOf(BpmStepState.Current)
    return if (current >= 0) "Continuar lección ${current + 1}" else "Empezar el curso"
}
