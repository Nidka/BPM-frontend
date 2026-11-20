package pe.bpm.features.operations.presentation

import pe.bpm.core.designsystem.BpmTone
import pe.bpm.features.operations.domain.CleaningPlan
import pe.bpm.features.operations.domain.CleaningResult

/*
 * Reglas puras de la lista «Mis tareas» del personal (WORKER). El estado de cada plan (hecho, vencida, turno)
 * lo calcula el servidor en la zona de la sede (`view=today`, campo `today`): el cliente no compara horas.
 */

/** Chip de un plan de hoy: texto y tono. */
internal data class TodayChip(val text: String, val tone: BpmTone)

/** Planes por hora límite ascendente; los que no tienen hora van al final. Es estable: conserva el orden previo en empates. */
internal fun sortPlansByDueTime(plans: List<CleaningPlan>): List<CleaningPlan> = sortByDueTime(plans) { it.dueTime }

/** Misma regla de [sortPlansByDueTime] para cualquier elemento con hora `HH:mm` opcional (vacía = al final). */
internal fun <T> sortByDueTime(items: List<T>, dueTime: (T) -> String): List<T> =
    items.sortedWith(compareBy<T> { dueTime(it).isBlank() }.thenBy { dueTime(it) })

/** «Por hacer»: primero los pendientes por hora límite y al final los hechos (también por hora). Estable. */
internal fun sortTodayPlans(plans: List<CleaningPlan>): List<CleaningPlan> =
    plans.sortedWith(compareBy<CleaningPlan> { it.today?.done == true }.thenBy { it.dueTime.isBlank() }.thenBy { it.dueTime })

/** Texto del chip de vencimiento («hasta 10:00») o `null` si el plan no tiene hora. */
internal fun dueChipText(dueTime: String): String? = dueTime.takeIf { it.isNotBlank() }?.let { "hasta $it" }

/**
 * Chip de un plan según el servidor: hecho = «Hecho» (Success); vencida = «Vencida» (Danger); si no, «hasta HH:mm»
 * (Neutral) o el nombre del turno (Neutral). Sin hora ni turno no hay chip. Con un servidor anterior (sin `today`)
 * queda solo «hasta HH:mm» Neutral.
 */
internal fun todayChip(plan: CleaningPlan): TodayChip? {
    val today = plan.today
    return when {
        today?.done == true -> TodayChip("Hecho", BpmTone.Success)
        today?.overdue == true -> TodayChip("Vencida", BpmTone.Danger)
        else -> dueChipText(plan.dueTime)?.let { TodayChip(it, BpmTone.Neutral) }
            ?: today?.shift?.name?.takeIf { it.isNotBlank() }?.let { TodayChip(it, BpmTone.Neutral) }
    }
}

/** Quién lo hizo: «Hecho por ti» o «Hecho por tu equipo» (el servidor no expone nombres ajenos); nulo si no está hecho. */
internal fun todayDoneBy(plan: CleaningPlan): String? {
    val today = plan.today ?: return null
    return if (!today.done) null else if (today.doneByMe) "Hecho por ti" else "Hecho por tu equipo"
}

/** Línea de apoyo de un plan de hoy: frecuencia, turno (si lo hay) y quién lo hizo. */
internal fun todayDetail(plan: CleaningPlan): String {
    val parts = mutableListOf(frequencyLabel(plan.frequency))
    plan.today?.shift?.name?.takeIf { it.isNotBlank() }?.let { parts += it }
    todayDoneBy(plan)?.let { parts += it }
    return parts.joinToString(" · ")
}

/** Hasta [limit] planes para «Tareas de hoy» en la pantalla Hoy, con el orden de «Por hacer». */
internal fun homeTodayPlans(plans: List<CleaningPlan>, limit: Int = 4): List<CleaningPlan> = sortTodayPlans(plans).take(limit)

/** Tono de una limpieza registrada: Completada = Success, Observada = Warning, No completada = Danger. */
internal fun resultTone(result: CleaningResult): BpmTone =
    when (result) {
        CleaningResult.COMPLETED -> BpmTone.Success
        CleaningResult.OBSERVED -> BpmTone.Warning
        CleaningResult.NOT_COMPLETED -> BpmTone.Danger
    }
