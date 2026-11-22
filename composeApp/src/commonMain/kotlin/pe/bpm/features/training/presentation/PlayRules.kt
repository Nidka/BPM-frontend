package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmPathNodeState
import pe.bpm.core.designsystem.BpmStepState
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.training.domain.AttemptType
import pe.bpm.features.training.domain.MyAssignment
import pe.bpm.features.training.domain.TrainingCourse
import pe.bpm.features.training.domain.TrainingLesson

/** Un paso de la lección del personal: video, una idea clave o el texto completo. */
internal sealed interface LessonStep {
    /** El video de la lección (privado o de YouTube). */
    data object Video : LessonStep
    /** Una idea de «Lo esencial»: [number] de [total] con su [text]. */
    data class Idea(val number: Int, val total: Int, val text: String) : LessonStep
    /** El texto completo, solo cuando la lección no trae ideas clave. */
    data object Reading : LessonStep
}

/**
 * Pasos de una lección: el video (si existe), un paso por cada idea clave y, solo si no hay ideas clave, el texto
 * completo. La práctica de las preguntas de la lección va después y la arma el flujo.
 */
internal fun lessonSteps(lesson: TrainingLesson): List<LessonStep> = buildList {
    if (lesson.video != null || lesson.externalVideo != null) add(LessonStep.Video)
    val points = lesson.keyPoints.map { it.trim() }.filter { it.isNotEmpty() }
    if (points.isEmpty()) add(LessonStep.Reading)
    else points.forEachIndexed { index, text -> add(LessonStep.Idea(index + 1, points.size, text)) }
}

/** Avance 0..1 de una secuencia: [done] pasos terminados de [total]. */
internal fun sequenceProgress(done: Int, total: Int): Float = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

private val IdeaFallback = listOf(BpmSymbol.Star, BpmSymbol.Bolt, BpmSymbol.Target, BpmSymbol.Eye)

/**
 * Icono grande de una idea clave según palabras de su texto (temperatura, manos, limpieza, plagas, agua, productos,
 * equipos); sin coincidencia rota entre estrella, rayo, diana y ojo según su [index].
 */
internal fun ideaSymbol(text: String, index: Int): BpmSymbol {
    val t = text.lowercase().replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u')
    fun has(vararg words: String) = words.any { it in t }
    return when {
        has("temperatura", "°c", "grados", "frio", "caliente", "refriger", "congel") -> BpmSymbol.Thermometer
        has("mano", "lavado", "lavar", "guante") -> BpmSymbol.Hand
        has("limpi", "desinf", "sanitiz", "detergente") -> BpmSymbol.Spray
        has("plaga", "insecto", "roedor", "mosca") -> BpmSymbol.Bug
        has("agua", "desague", "grifo") -> BpmSymbol.Drop
        has("producto", "insumo", "almacen", "etiqueta", "vencimiento", "peps") -> BpmSymbol.Box
        has("equipo", "malogr", "herramienta", "maquina") -> BpmSymbol.Wrench
        else -> IdeaFallback[index.coerceAtLeast(0) % IdeaFallback.size]
    }
}

/** Un círculo del camino: su estado, si responde al toque y qué abre. [lessonId] nulo = el trofeo (evaluación final). */
internal data class PathNodeModel(val state: BpmPathNodeState, val enabled: Boolean, val lessonId: String?, val label: String)

/**
 * Nodos del camino de un curso: una lección por círculo (hecha, la siguiente como actual, el resto bloqueado según
 * [lessonStepStates]) y el trofeo de la evaluación final, que solo se abre con todas las lecciones hechas, con
 * preguntas y con [canEvaluate] (membresía).
 */
internal fun pathNodes(course: TrainingCourse, canEvaluate: Boolean): List<PathNodeModel> {
    val steps = lessonStepStates(course)
    val nodes = course.lessons.mapIndexed { index, lesson ->
        when (steps[index]) {
            BpmStepState.Done -> PathNodeModel(BpmPathNodeState.Done, true, lesson.id, "Lección ${index + 1}: ${lesson.title}, completada")
            BpmStepState.Current -> PathNodeModel(BpmPathNodeState.Current, true, lesson.id, "Lección ${index + 1}: ${lesson.title}, la siguiente")
            else -> PathNodeModel(BpmPathNodeState.Locked, false, lesson.id, "Lección ${index + 1}: ${lesson.title}, bloqueada")
        }
    }
    val allDone = course.lessons.isNotEmpty() && steps.all { it == BpmStepState.Done }
    val open = allDone && canEvaluate && course.questions.isNotEmpty()
    return nodes + PathNodeModel(BpmPathNodeState.Trophy, open, null,
        if (open) "Evaluación final: abierta" else "Evaluación final: termina primero las lecciones")
}

/** Asignaciones en el orden del camino: en curso, vencidas, pendientes y, al final, completadas (cada grupo por fecha límite). */
internal fun pathOrder(assignments: List<MyAssignment>): List<MyAssignment> = assignmentsForToday(assignments, assignments.size)

/** Posición («Módulo n de N») de [courseId] en el camino; nulo si no está asignado. */
internal fun moduleOf(assignments: List<MyAssignment>, courseId: String): Pair<Int, Int>? {
    val ordered = pathOrder(assignments)
    val index = ordered.indexOfFirst { it.course.id.equals(courseId, ignoreCase = true) }
    return if (index < 0) null else index + 1 to ordered.size
}

/** Meta del día sin servidor: una lección. «1/1» si ya aprendió hoy, «0/1» si no. */
internal fun lessonGoalLabel(activeToday: Boolean): String = if (activeToday) "1/1" else "0/1"

/** Texto del chip de la evaluación: final con su mínimo, de lección o diagnóstica. */
internal fun evaluationChipLabel(type: AttemptType, passingScore: Int): String = when (type) {
    AttemptType.FINAL -> "Evaluación final · aprueba con $passingScore"
    AttemptType.MODULE -> "Evaluación de lección · suma XP"
    AttemptType.DIAGNOSTIC -> "Evaluación diagnóstica · no emite constancia"
}
