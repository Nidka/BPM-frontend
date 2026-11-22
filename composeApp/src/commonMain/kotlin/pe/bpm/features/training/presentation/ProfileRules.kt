package pe.bpm.features.training.presentation

import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.training.domain.Achievement
import pe.bpm.features.training.domain.AchievementCriterion
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.domain.MyAssignment

/**
 * Un día de la semana de la racha (L–D). [active] marca un día con aprendizaje dentro de la racha vigente; [today] es el
 * día de hoy (se resalta aunque todavía no esté activo). [description] es lo que lee el lector de pantalla.
 */
data class StreakDay(val letter: String, val active: Boolean, val today: Boolean, val description: String)

private val WEEK_LETTERS = listOf("L", "M", "M", "J", "V", "S", "D")
private val WEEK_NAMES = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

/**
 * Días desde 1970-01-01 de una fecha civil (algoritmo de Howard Hinnant). Sirve para sumar y restar días y obtener el
 * día de la semana sin una biblioteca de fechas; solo trabaja con fechas, nunca con horas ni zonas.
 */
internal fun epochDay(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097 + dayOfEra - 719468
}

/** `yyyy-MM-dd` (con o sin hora detrás) → días desde 1970-01-01; nulo si no es una fecha válida. */
internal fun parseIsoDay(text: String?): Long? {
    val parts = text?.substringBefore('T')?.split('-') ?: return null
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    return epochDay(year, month, day)
}

/** Índice del día de la semana con lunes = 0 … domingo = 6 (el 1970-01-01 fue jueves). */
internal fun mondayIndex(epochDay: Long): Int = (((epochDay + 3) % 7 + 7) % 7).toInt()

/**
 * Semana (lunes a domingo) que contiene [today], marcando los días activos de la racha. Función pura: [today] y el
 * resto llegan como fechas en días desde 1970-01-01.
 *
 * La racha ocupa [currentDays] días consecutivos que terminan en el último día con aprendizaje: hoy si [activeToday], o
 * [lastActivityDate] (`yyyy-MM-dd`) si todavía está vigente (terminó ayer). Una racha que terminó antes de ayer ya está
 * rota, y sin días o sin fecha no hay nada marcado. Los días futuros nunca se marcan.
 */
fun streakWeek(currentDays: Int, activeToday: Boolean, lastActivityDate: String?, today: Long): List<StreakDay> {
    val end = if (activeToday) today else parseIsoDay(lastActivityDate)?.takeIf { it >= today - 1 }
    val first = if (end != null && currentDays > 0) end - (currentDays - 1) else null
    val monday = today - mondayIndex(today)
    return (0..6).map { index ->
        val day = monday + index
        val active = first != null && end != null && day in first..end && day <= today
        val isToday = day == today
        val state = when { active && isToday -> "hoy, con actividad"; active -> "con actividad"; isToday -> "hoy, sin actividad todavía"; day > today -> "por venir"; else -> "sin actividad" }
        StreakDay(WEEK_LETTERS[index], active, isToday, "${WEEK_NAMES[index]}, $state")
    }
}

/** Título de la tarjeta de racha: «Racha de 6 días», «Racha de 1 día» o la invitación a empezar. */
fun streakTitle(currentDays: Int): String = when {
    currentDays <= 0 -> "Empieza tu racha hoy"
    currentDays == 1 -> "Racha de 1 día"
    else -> "Racha de $currentDays días"
}

/** Cómo ganar una insignia pendiente, en una frase («Completa 3 cursos», «Aprende 7 días seguidos»). */
fun badgeCriterion(achievement: Achievement): String {
    val n = achievement.criterionThreshold
    return when (achievement.criterion) {
        AchievementCriterion.COURSES_COMPLETED -> if (n == 1) "Completa 1 curso" else "Completa $n cursos"
        AchievementCriterion.PERFECT_SCORE -> if (n == 1) "Saca el puntaje perfecto en 1 evaluación" else "Saca el puntaje perfecto en $n evaluaciones"
        AchievementCriterion.STREAK_DAYS -> if (n == 1) "Aprende 1 día" else "Aprende $n días seguidos"
    }
}

/** Icono de una insignia según lo que premia. */
fun badgeSymbol(achievement: Achievement): BpmSymbol = when (achievement.criterion) {
    AchievementCriterion.COURSES_COMPLETED -> BpmSymbol.Award
    AchievementCriterion.PERFECT_SCORE -> BpmSymbol.Star
    AchievementCriterion.STREAK_DAYS -> BpmSymbol.Flame
}

/**
 * Insignias del perfil: primero las ganadas (la más reciente primero) y después las pendientes, de la más cercana a la
 * más lejana según el umbral de su criterio.
 */
fun badgeOrder(achievements: List<Achievement>): List<Achievement> {
    val (earned, pending) = achievements.filter { it.active || it.awardedAt != null }.partition { it.awardedAt != null }
    return earned.sortedByDescending { it.awardedAt.orEmpty() } + pending.sortedBy { it.criterionThreshold }
}

/**
 * Constancias del perfil: los cursos de las asignaciones completadas, del más reciente al más antiguo. El servidor no
 * entrega aquí el código ni el PDF; cada fila lleva al curso, donde está su constancia.
 */
fun certificateCourses(assignments: List<MyAssignment>): List<MyAssignment> =
    assignments.filter { it.status == AssignmentProgress.COMPLETED }.sortedByDescending { it.completedAt.orEmpty() }

/**
 * Fecha de hoy en la zona del dispositivo (días desde 1970-01-01) a partir de la fecha legible que ya da cada plataforma
 * (`dd/MM/yyyy …`); si el texto no se puede leer, cae a la fecha UTC.
 */
internal fun deviceToday(nowEpochMillis: Long): Long =
    parseDeviceDate(pe.bpm.core.presentation.localDateTime(nowEpochMillis)) ?: nowEpochMillis.floorDiv(86_400_000L)

/** `22/11/2026 · 10:30 …` o `22/11/2026, 10:30` → días desde 1970-01-01 (admite día/mes sin cero y año de dos cifras). */
internal fun parseDeviceDate(text: String): Long? {
    val parts = text.trim().takeWhile { it.isDigit() || it == '/' }.split('/')
    if (parts.size != 3) return null
    val day = parts[0].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
    val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val year = parts[2].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: return null
    return epochDay(year, month, day)
}
