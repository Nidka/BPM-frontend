package pe.bpm.features.operations.presentation

import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.domain.CleaningResult

/*
 * Reglas puras de la lista «Mis tareas» del personal (WORKER). No existe un modelo de «pendiente de hoy»:
 * los planes solo se ordenan por su hora límite (`dueTime`, formato HH:mm o vacío).
 */

/** Planes por hora límite ascendente; los que no tienen hora van al final. Es estable: conserva el orden previo en empates. */
internal fun sortPlansByDueTime(plans: List<CleaningPlan>): List<CleaningPlan> = sortByDueTime(plans) { it.dueTime }

/** Misma regla de [sortPlansByDueTime] para cualquier elemento con hora `HH:mm` opcional (vacía = al final). */
internal fun <T> sortByDueTime(items: List<T>, dueTime: (T) -> String): List<T> =
    items.sortedWith(compareBy<T> { dueTime(it).isBlank() }.thenBy { dueTime(it) })

/** Texto del chip de vencimiento («hasta 10:00») o `null` si el plan no tiene hora. */
internal fun dueChipText(dueTime: String): String? = dueTime.takeIf { it.isNotBlank() }?.let { "hasta $it" }

/** Minutos desde medianoche de un `HH:mm`, o `null` si el texto no es una hora válida. */
internal fun minutesOfDay(time: String): Int? {
    val parts = time.split(':')
    if (parts.size != 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    return if (hours in 0..23 && minutes in 0..59) hours * 60 + minutes else null
}

/**
 * Tono del chip de vencimiento: Warning si la hora límite ya pasó respecto de [localMinutes] (hora local del
 * dispositivo en minutos desde medianoche); Neutral si no pasó, no hay hora o no se conoce la hora local.
 */
internal fun dueTone(dueTime: String, localMinutes: Int?): BpmTone {
    val due = minutesOfDay(dueTime) ?: return BpmTone.Neutral
    return if (localMinutes != null && localMinutes > due) BpmTone.Warning else BpmTone.Neutral
}

/** Tono de una limpieza registrada: Completada = Success, Observada = Warning, No completada = Danger. */
internal fun resultTone(result: CleaningResult): BpmTone =
    when (result) {
        CleaningResult.COMPLETED -> BpmTone.Success
        CleaningResult.OBSERVED -> BpmTone.Warning
        CleaningResult.NOT_COMPLETED -> BpmTone.Danger
    }
