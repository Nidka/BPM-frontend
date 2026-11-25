package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmPathNodeState
import pe.bpm.core.designsystem.BpmStepState
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.training.domain.AttemptType
import pe.bpm.features.training.domain.ProgressStatus
import pe.bpm.features.training.domain.QuestionResult
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

/** Qué abre un nodo del camino. */
internal enum class PathNodeKind { LESSON, REVIEW, FINAL }

/**
 * Un círculo del camino: su estado, si responde al toque y qué abre. [lessonId] nulo = el trofeo (evaluación final) o el
 * repaso ([kind]). [caption] es el texto visible bajo el nodo (solo en cursos con banco oficial; vacío en los demás).
 */
internal data class PathNodeModel(
    val state: BpmPathNodeState,
    val enabled: Boolean,
    val lessonId: String?,
    val label: String,
    val kind: PathNodeKind = if (lessonId != null) PathNodeKind.LESSON else PathNodeKind.FINAL,
    val caption: String = "",
)

/** Aciertos mínimos de una evaluación de [items] preguntas para aprobar con [passingScore] (80 % de 5 = 4). */
internal fun passingCorrect(items: Int, passingScore: Int): Int = (items * passingScore + 99) / 100

/** «4/5»: aciertos necesarios de las preguntas de la evaluación del módulo. */
internal fun passingFraction(course: TrainingCourse): String {
    val items = course.bank?.itemsPerAttempt ?: 5
    return "${passingCorrect(items, course.version.passingScore)}/$items"
}

/** «5 preguntas» / «1 pregunta». */
internal fun questionCountLabel(count: Int): String = if (count == 1) "1 pregunta" else "$count preguntas"

/** «2 por repasar». */
internal fun reviewCaption(due: Int): String = "Repaso · " + if (due == 1) "1 por repasar" else "$due por repasar"

/**
 * Nodos del camino de un curso: una lección (unidad) por círculo (hecha, la siguiente como actual, el resto bloqueado
 * según [lessonStepStates]), el repaso espaciado si hay preguntas por repasar (solo cursos con banco y con membresía) y
 * el trofeo de la evaluación final, que solo se abre con todas las lecciones hechas, con preguntas y con [canEvaluate]
 * (membresía). En cursos con banco oficial cada nodo lleva un [PathNodeModel.caption] visible: «1. Título · 5 preguntas».
 */
internal fun pathNodes(course: TrainingCourse, canEvaluate: Boolean): List<PathNodeModel> {
    val steps = lessonStepStates(course)
    val bank = course.bank != null
    val nodes = course.lessons.mapIndexed { index, lesson ->
        val count = course.questions.count { it.lessonId == lesson.id }
        val detail = if (bank && count > 0) " · ${questionCountLabel(count)}" else ""
        val caption = if (bank) "${index + 1}. ${lesson.title} · " + if (count > 0) questionCountLabel(count) else "Lección" else ""
        when (steps[index]) {
            BpmStepState.Done -> PathNodeModel(BpmPathNodeState.Done, true, lesson.id, "Lección ${index + 1}: ${lesson.title}$detail, completada", caption = caption)
            BpmStepState.Current -> PathNodeModel(BpmPathNodeState.Current, true, lesson.id, "Lección ${index + 1}: ${lesson.title}$detail, la siguiente", caption = caption)
            else -> PathNodeModel(BpmPathNodeState.Locked, false, lesson.id, "Lección ${index + 1}: ${lesson.title}$detail, bloqueada", caption = caption)
        }
    }
    val allDone = course.lessons.isNotEmpty() && steps.all { it == BpmStepState.Done }
    val open = allDone && canEvaluate && course.questions.isNotEmpty()
    val review = if (bank && canEvaluate && course.review.dueCount > 0)
        listOf(PathNodeModel(BpmPathNodeState.Current, true, null, "Repaso: ${course.review.dueCount} por repasar", PathNodeKind.REVIEW, reviewCaption(course.review.dueCount)))
    else emptyList()
    val trophyCaption = if (bank) "Evaluación del módulo · ${questionCountLabel(course.bank?.itemsPerAttempt ?: 5)} · aprueba con ${passingFraction(course)}" else ""
    return nodes + review + PathNodeModel(BpmPathNodeState.Trophy, open, null,
        if (open) "Evaluación final: abierta" else "Evaluación final: termina primero las lecciones", PathNodeKind.FINAL, trophyCaption)
}

/** Texto del botón del nodo actual del camino: «Continuar» si ya hay unidades hechas, «Empezar» en otro caso. */
internal fun startBubbleLabel(course: TrainingCourse): String =
    if (course.progress.completedLessonIds.isNotEmpty() || course.progress.status == ProgressStatus.IN_PROGRESS && course.progress.percent > 0) "Continuar" else "Empezar"

/** Estado de una pregunta en el resultado: texto, la respuesta elegida y la correcta (con datos del banco o buscando en el curso). */
internal data class ResultReview(val text: String, val chosen: String, val correct: String)

internal fun resultReview(course: TrainingCourse, result: QuestionResult): ResultReview {
    val question = course.questions.firstOrNull { it.id == result.questionId }
    val text = result.questionText ?: question?.questionText.orEmpty()
    val options = result.options.ifEmpty { question?.options.orEmpty() }
    return ResultReview(text, options.getOrNull(result.selectedOption).orEmpty(), options.getOrNull(result.correctOption).orEmpty())
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
internal fun evaluationChipLabel(type: AttemptType, passingScore: Int, bankFraction: String? = null): String = when (type) {
    AttemptType.FINAL -> if (bankFraction != null) "Evaluación del módulo · $bankFraction" else "Evaluación final · aprueba con $passingScore"
    AttemptType.MODULE -> "Evaluación de lección · suma XP"
    AttemptType.DIAGNOSTIC -> "Diagnóstica · sin constancia"
}

/**
 * Antetítulo de la cabecera de un curso en el camino: solo el código del curso y, si la asignación tiene fecha límite,
 * «Asignado · vence 25 nov» (sin numerar módulos: los títulos de los cursos ya traen su propio número).
 */
internal fun unitKicker(assignment: MyAssignment): String =
    assignment.dueAt?.takeIf { it.isNotBlank() }?.let { "${assignment.course.code} · Asignado · vence ${readableDate(it)}" } ?: assignment.course.code

/** Desplazamiento horizontal (dp) del nodo [index] de [count] en el camino: zigzag desde tres nodos; con uno o dos, todo centrado. */
internal fun pathNodeOffset(index: Int, count: Int): Int =
    if (count <= 2) 0 else intArrayOf(0, -60, 0, 60)[index % 4]

/**
 * La justificación del banco suele repetir la alternativa correcta. Se muestra solo si aporta algo distinto de la
 * respuesta correcta que ya aparece en la tarjeta (se comparan sin espacios sobrantes ni punto final).
 */
internal fun showExplanation(explanation: String, correctText: String): Boolean {
    fun norm(text: String) = text.trim().trimEnd('.').trim()
    return explanation.isNotBlank() && norm(explanation) != norm(correctText)
}
