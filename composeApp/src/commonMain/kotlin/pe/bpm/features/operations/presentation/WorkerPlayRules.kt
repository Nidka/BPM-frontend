package pe.bpm.features.operations.presentation

import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.operations.domain.CleaningFrequency
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.domain.CleaningResult
import kotlin.time.Instant

/*
 * Reglas puras de las pantallas de juego operativas del personal (Hoy, registro rápido y Tareas). Sin Compose ni red:
 * el estado de cada plan (hecho, vencida, turno) lo calcula el servidor; el cliente solo ordena, agrupa y cuenta.
 */

// ---- Meta de hoy -----------------------------------------------------------------------------------

/** Un trozo de la frase del tomate; [bold] resalta lo que falta («2 tareas»). */
internal data class PhrasePart(val text: String, val bold: Boolean = false)

/**
 * Meta del día sin servidor: **1 lección** ([lessonToday] = hubo actividad de aprendizaje hoy) más las tareas del turno.
 * [total] = 1 + [tasksTotal]; [done] = lección (0 o 1) + tareas hechas (sin pasar del total de tareas).
 */
internal data class DailyGoal(val lessonDone: Boolean, val tasksDone: Int, val tasksTotal: Int) {
    val total: Int get() = 1 + tasksTotal
    val done: Int get() = (if (lessonDone) 1 else 0) + tasksDone
    val missingTasks: Int get() = tasksTotal - tasksDone
    val missingLesson: Boolean get() = !lessonDone
    val complete: Boolean get() = missingTasks == 0 && !missingLesson
    val progress: Float get() = done.toFloat() / total
    /** «2/3»: el valor de la píldora de meta. */
    val label: String get() = "$done/$total"
    /** «2 de 3»: el texto de la ficha «Meta de hoy». */
    val chip: String get() = "$done de $total"
}

/** Meta del día: [lessonToday] y las tareas del turno hechas/total de `view=today`. Los valores se acotan a rangos válidos. */
internal fun dailyGoal(lessonToday: Boolean, tasksDone: Int, tasksTotal: Int): DailyGoal {
    val total = tasksTotal.coerceAtLeast(0)
    return DailyGoal(lessonToday, tasksDone.coerceIn(0, total), total)
}

/** «1 tarea» / «2 tareas». */
internal fun tasksText(count: Int): String = if (count == 1) "1 tarea" else "$count tareas"

/**
 * Frase del tomate en Hoy: «¡Hola, Lucía! Te faltan **2 tareas** y **1 lección** para cerrar el día.» o, con la meta
 * cumplida, «¡Día completo!». [name] vacío omite el saludo con nombre.
 */
internal fun goalPhrase(goal: DailyGoal, name: String): List<PhrasePart> {
    val greeting = if (name.isBlank()) "¡Hola! " else "¡Hola, $name! "
    if (goal.complete) return listOf(PhrasePart("¡Día completo! ", true), PhrasePart("Hiciste tus tareas y tu lección de hoy. Descansa un momento."))
    val missing = buildList {
        if (goal.missingTasks > 0) add(PhrasePart(tasksText(goal.missingTasks), true))
        if (goal.missingLesson) add(PhrasePart("1 lección", true))
    }
    // «Te falta» solo cuando falta exactamente una cosa; con dos, «Te faltan».
    val single = missing.size == 1 && (goal.missingTasks == 1 || goal.missingTasks == 0)
    return buildList {
        add(PhrasePart(greeting + if (single) "Te falta " else "Te faltan "))
        missing.forEachIndexed { index, part -> if (index > 0) add(PhrasePart(" y ")); add(part) }
        add(PhrasePart(" para cerrar el día."))
    }
}

/** La frase como texto plano (descripción para lectores de pantalla y pruebas). */
internal fun List<PhrasePart>.plain(): String = joinToString("") { it.text }

// ---- Misiones (filas de tareas) -------------------------------------------------------------------------

/** Icono y color de la fila de una tarea. */
internal data class MissionIcon(val symbol: BpmSymbol, val tone: BpmPlayTone)

private fun normalized(text: String): String =
    text.lowercase().replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n')

/**
 * Icono de una tarea por palabras de su nombre o su área: temperatura/cámara → termómetro; manos y agua → gota;
 * recepción, insumos y vencimientos → caja; equipos → llave; plagas → insecto; el resto (limpieza y desinfección) → rociador.
 */
internal fun missionIcon(plan: CleaningPlan): MissionIcon = missionIconFor(plan.task + " " + plan.area)

/** Igual que [missionIcon] pero por un texto cualquiera (el nombre de un control programado, por ejemplo). */
internal fun missionIconFor(text: String): MissionIcon {
    val t = normalized(text)
    fun has(vararg words: String) = words.any { it in t }
    return when {
        has("temperatura", "°c", "camara", "refriger", "congel") -> MissionIcon(BpmSymbol.Thermometer, BpmPlayTone.Action)
        has("lavado de manos", "manos", "agua", "desague", "grifo") -> MissionIcon(BpmSymbol.Drop, BpmPlayTone.Correct)
        has("recepcion", "insumo", "vencim", "almacen", "stock", "producto") -> MissionIcon(BpmSymbol.Box, BpmPlayTone.Gold)
        has("equipo", "maquina", "campana", "extractor") -> MissionIcon(BpmSymbol.Wrench, BpmPlayTone.Action)
        has("plaga", "insecto", "roedor") -> MissionIcon(BpmSymbol.Bug, BpmPlayTone.Gold)
        else -> MissionIcon(BpmSymbol.Spray, BpmPlayTone.Streak)
    }
}

/**
 * Línea de detalle de una misión: hecha → «Hecho por ti»; vencida → «Venció a las 10:00»; con hora → «Hasta las 12:00»;
 * sin hora → el turno o la frecuencia. Siempre termina con el área («· Cocina») si existe.
 */
internal fun missionDetail(plan: CleaningPlan): String {
    val today = plan.today
    val main = when {
        today?.done == true -> todayDoneBy(plan) ?: "Hecho"
        today?.overdue == true -> if (plan.dueTime.isBlank()) "Vencida" else "Venció a las ${plan.dueTime}"
        plan.dueTime.isNotBlank() -> "Hasta las ${plan.dueTime}"
        else -> today?.shift?.name?.takeIf { it.isNotBlank() } ?: frequencyLabel(plan.frequency)
    }
    return if (plan.area.isBlank()) main else "$main · ${plan.area}"
}

/** La misión está vencida (pendiente y el servidor la marca vencida). */
internal fun isLate(plan: CleaningPlan): Boolean = plan.today?.done != true && plan.today?.overdue == true

/** Misiones de la pantalla Hoy: vencidas primero, luego por hora y las hechas al final (en verde). */
internal fun missionOrder(plans: List<CleaningPlan>): List<CleaningPlan> = sortTodayPlans(plans)

// ---- Tareas: grupos Ahora / Más tarde / Hechas --------------------------------------------------------------

/** Las tareas de la sede separadas en las tres secciones de la pantalla Tareas. */
internal data class TaskGroups(val now: List<CleaningPlan>, val later: List<CleaningPlan>, val done: List<CleaningPlan>)

/** Ventana de «Ahora»: una tarea con hora dentro de las próximas 2 horas ya se puede hacer. */
private const val NOW_WINDOW_MINUTES = 120

private fun minutesOf(time: String): Int? {
    val parts = time.split(':')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

/**
 * Minutos transcurridos del día en la zona de la sede. El periodo de cada plan empieza a medianoche local
 * (`periodStartsAt`), así que `(ahora − inicio) mod 1440` da la hora local sin conocer la zona. Nulo sin datos.
 */
internal fun siteMinutesOfDay(plan: CleaningPlan, nowEpochMillis: Long): Int? {
    val start = plan.today?.periodStartsAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: return null
    return (((nowEpochMillis - start) / 60_000L) % 1440L + 1440L).toInt() % 1440
}

/**
 * Agrupa las tareas: **Hechas** (las que el servidor marca hechas); **Ahora** (pendientes vencidas, con turno abierto,
 * con hora dentro de las próximas 2 h o diarias sin hora); **Más tarde** (el resto). Cada grupo conserva el orden de
 * [sortTodayPlans]. Con [nowEpochMillis] nulo no se mira la hora: solo cuentan vencidas, turno abierto y diarias sin hora.
 */
internal fun taskGroups(plans: List<CleaningPlan>, nowEpochMillis: Long?): TaskGroups {
    val sorted = sortTodayPlans(plans)
    val (done, pending) = sorted.partition { it.today?.done == true }
    val (now, later) = pending.partition { plan ->
        val due = minutesOf(plan.dueTime)
        val minutes = if (nowEpochMillis != null) siteMinutesOfDay(plan, nowEpochMillis) else null
        plan.today?.overdue == true || plan.today?.shift?.state == "OPEN" ||
            (due == null && plan.frequency == CleaningFrequency.DAILY) ||
            (due != null && minutes != null && due - minutes <= NOW_WINDOW_MINUTES)
    }
    return TaskGroups(now, later, done)
}

/** Título de la tarjeta del turno: «Turno tarde» si hay un turno abierto; si no, «Tu turno». */
internal fun shiftTitle(plans: List<CleaningPlan>): String {
    val open = plans.firstNotNullOfOrNull { it.today?.shift?.takeIf { s -> s.state == "OPEN" && s.name.isNotBlank() } } ?: return "Tu turno"
    return if (open.name.startsWith("turno", ignoreCase = true)) open.name else "Turno ${open.name}"
}

// ---- Avance del turno ---------------------------------------------------------------------------------

/** Avance del turno: [done] de [total] tareas hechas y la [next] pendiente (la que sigue en el orden del turno). */
internal data class ShiftProgress(val done: Int, val total: Int, val next: CleaningPlan?) {
    val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
    /** «2 de 3». */
    val label: String get() = "$done de $total"
    val complete: Boolean get() = total > 0 && done >= total
}

/**
 * Avance del turno con los planes de `view=today`. [justDoneId] cuenta como hecho aunque el servidor aún no lo
 * confirme (se acaba de registrar, o quedó en el dispositivo sin conexión) y nunca se ofrece como siguiente.
 */
internal fun shiftProgress(plans: List<CleaningPlan>, justDoneId: String? = null): ShiftProgress {
    val done = plans.count { it.today?.done == true || it.id == justDoneId }
    val next = pendingPlans(plans.filter { it.id != justDoneId }).firstOrNull()
    return ShiftProgress(done, plans.size, next)
}

/** «Temperatura de la cámara (hasta 12:00)»: lo que falta, con su hora si la tiene. */
internal fun nextTaskText(plan: CleaningPlan): String =
    if (plan.dueTime.isBlank()) plan.task else "${plan.task} (hasta ${plan.dueTime})"

// ---- Registro rápido de una tarea -----------------------------------------------------------------------

/** Pasos del registro rápido: cómo quedó, nota (solo si no quedó bien) y foto. */
internal enum class QuickStep { Result, Note, Photo }

/** Los pasos de un registro: con «Bien» son dos (resultado → foto); con observación o «No se pudo», tres (con nota). */
internal fun quickSteps(result: CleaningResult): List<QuickStep> =
    if (result == CleaningResult.COMPLETED) listOf(QuickStep.Result, QuickStep.Photo)
    else listOf(QuickStep.Result, QuickStep.Note, QuickStep.Photo)

/** Hora «10:42» dentro de una fecha legible del dispositivo («20/11/2026 · 10:42 PET»); vacío si no hay hora. */
internal fun clockOf(display: String): String = Regex("""\d{1,2}:\d{2}""").find(display)?.value.orEmpty()
