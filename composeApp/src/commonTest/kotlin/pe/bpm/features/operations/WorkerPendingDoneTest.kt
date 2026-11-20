package pe.bpm.features.operations

import kotlin.test.Test
import kotlin.test.assertEquals
import pe.bpm.features.operations.domain.*
import pe.bpm.features.operations.presentation.*

/** «Por hacer» solo con lo pendiente, «Hechas · Hoy» con lo hecho y meta de fila «Área · Frecuencia». */
class WorkerPendingDoneTest {
    private fun plan(id: String, due: String = "", done: Boolean = false, overdue: Boolean = false, shift: String? = null, execution: String? = null,
        frequency: CleaningFrequency = CleaningFrequency.DAILY) =
        CleaningPlan(id, "s", "Cocina", "Tarea $id", dueTime = due, frequency = frequency,
            today = CleaningPlanToday("a", "b", "America/Lima", done, doneByMe = done, lastExecutionId = execution, overdue = overdue,
                shift = shift?.let { CleaningPlanShift("t", it, "OPEN") }))

    @Test fun pendingExcludesDoneAndOrdersOverdueThenTimeThenUntimed() {
        val plans = listOf(plan("sin-hora"), plan("hecho", "07:00", done = true), plan("tarde", "17:00"), plan("temprano", "09:00"), plan("vencida", "18:00", overdue = true))
        assertEquals(listOf("vencida", "temprano", "tarde", "sin-hora"), pendingPlans(plans).map { it.id })
    }

    @Test fun doneKeepsOnlyDoneByTime() {
        val plans = listOf(plan("a", "10:00"), plan("c", done = true), plan("b", "08:00", done = true))
        assertEquals(listOf("b", "c"), donePlans(plans).map { it.id })
    }

    @Test fun historyDropsExecutionsAlreadyShownInToday() {
        val done = listOf(plan("a", done = true, execution = "e1"), plan("b", done = true))
        assertEquals(listOf("e2", "e3"), withoutDoneToday(listOf("e1", "e2", "e3"), done) { it })
        assertEquals(listOf("e1"), withoutDoneToday(listOf("e1"), emptyList()) { it })
    }

    @Test fun metaIsAreaAndFrequencyWithoutRepeatingTheShiftChip() {
        assertEquals("Cocina · Diaria", todayMeta(plan("a", "10:00")))
        // Sin hora, el chip ya dice el turno: no se repite en la meta.
        assertEquals("Cocina · Por turno", todayMeta(plan("a", shift = "Turno tarde", frequency = CleaningFrequency.PER_SHIFT)))
        // Con hora, el chip es «hasta» y el turno sí aporta.
        assertEquals("Cocina · Por turno · Turno tarde", todayMeta(plan("a", "10:00", shift = "Turno tarde", frequency = CleaningFrequency.PER_SHIFT)))
        assertEquals("Cocina · Diaria · Hecho por ti", todayMeta(plan("a", done = true)))
    }
}
