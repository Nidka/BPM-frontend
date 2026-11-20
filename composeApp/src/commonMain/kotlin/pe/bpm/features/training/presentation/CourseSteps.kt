package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmStepState
import pe.bpm.features.training.domain.ProgressStatus
import pe.bpm.features.training.domain.TrainingCourse

/** Hay avance real por lección: el servidor informó el total de lecciones (no es una caché antigua ni un servidor anterior). */
internal fun hasLessonProgress(course: TrainingCourse): Boolean = course.progress.totalLessons > 0

/**
 * Estado de cada lección para dibujar el temario del curso, en el mismo orden que `course.lessons`.
 *
 * Con el avance real del servidor ([hasLessonProgress]) usa `completedLessonIds`: las terminadas figuran como hechas,
 * la actual es la última lección abierta si aún no está terminada (o, si no, la primera sin terminar) y el resto
 * está pendiente. Curso completado: todas hechas.
 *
 * **Si el servidor no envía el avance es una aproximación.** Solo se conoce el estado del curso y la última lección
 * abierta (`lastLessonId`), así que se asume que el alumno avanza en orden:
 * - sin iniciar, o sin una última lección que siga publicada: la primera es la actual y el resto está pendiente;
 * - en curso: las anteriores a la última abierta figuran como hechas, esa es la actual y las siguientes están pendientes.
 */
fun lessonStepStates(course: TrainingCourse): List<BpmStepState> {
    val lessons = course.lessons
    if (lessons.isEmpty()) return emptyList()
    if (course.progress.status == ProgressStatus.COMPLETED) return lessons.map { BpmStepState.Done }
    if (hasLessonProgress(course)) {
        val done = course.progress.completedLessonIds.toSet()
        val last = lessons.indexOfFirst { it.id == course.progress.lastLessonId && it.id !in done }
        val current = if (last >= 0) last else lessons.indexOfFirst { it.id !in done }
        return lessons.mapIndexed { index, lesson ->
            when {
                lesson.id in done -> BpmStepState.Done
                index == current -> BpmStepState.Current
                else -> BpmStepState.Pending
            }
        }
    }
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

/** Anillo de avance del curso: fracción 0..1, texto centrado y descripción para lectores de pantalla. */
internal data class CourseRing(val progress: Float, val label: String, val description: String)

/**
 * Anillo del curso. Con avance real usa el porcentaje del servidor («60 %»); sin él cuenta las lecciones hechas
 * según [lessonStepStates] («3/5»). Sin lecciones devuelve nulo.
 */
internal fun courseRing(course: TrainingCourse): CourseRing? {
    val total = course.lessons.size
    if (total == 0) return null
    if (hasLessonProgress(course)) {
        val percent = (if (course.progress.status == ProgressStatus.COMPLETED) 100 else course.progress.percent).coerceIn(0, 100)
        return CourseRing(percent / 100f, "$percent %", "Avance del curso: $percent %")
    }
    val done = lessonStepStates(course).count { it == BpmStepState.Done }
    return CourseRing(done.toFloat() / total, "$done/$total", "Avance del curso: $done de $total lecciones")
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
