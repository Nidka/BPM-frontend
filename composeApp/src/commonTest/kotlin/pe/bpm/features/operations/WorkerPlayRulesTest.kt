package pe.bpm.features.operations

import kotlin.test.*
import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.designsystem.BpmSymbol
import pe.bpm.features.operations.domain.*
import pe.bpm.features.operations.presentation.*

/** Reglas puras de Hoy, Tareas y el registro rápido del personal: meta del día, misiones, grupos y avance del turno. */
class WorkerPlayRulesTest {
    private val site = "33333333-3333-4333-8333-333333333333"

    private fun plan(
        id: String, task: String = id, area: String = "Cocina", due: String = "", frequency: CleaningFrequency = CleaningFrequency.DAILY,
        done: Boolean = false, overdue: Boolean = false, byMe: Boolean = false, shift: CleaningPlanShift? = null,
        periodStart: String = "2026-11-20T05:00:00Z",
    ) = CleaningPlan(id, site, area, task, dueTime = due, frequency = frequency,
        today = CleaningPlanToday(periodStart, "2026-11-21T05:00:00Z", "America/Lima", done, byMe, overdue = overdue, shift = shift))

    // ---- Meta de hoy -------------------------------------------------------------------------

    @Test fun dailyGoalCountsOneLessonPlusTheShiftTasks() {
        val goal = dailyGoal(lessonToday = false, tasksDone = 1, tasksTotal = 3)
        assertEquals(4, goal.total)
        assertEquals(1, goal.done)
        assertEquals("1/4", goal.label)
        assertEquals("1 de 4", goal.chip)
        assertEquals(0.25f, goal.progress)
        assertEquals(2, goal.missingTasks)
        assertTrue(goal.missingLesson)
        assertFalse(goal.complete)
    }

    @Test fun dailyGoalIsCompleteOnlyWithTheLessonAndEveryTask() {
        assertTrue(dailyGoal(true, 3, 3).complete)
        assertFalse(dailyGoal(true, 2, 3).complete)
        assertFalse(dailyGoal(false, 3, 3).complete)
        assertEquals(1f, dailyGoal(true, 3, 3).progress)
    }

    @Test fun dailyGoalWithoutTasksIsJustTheLesson() {
        assertEquals("0/1", dailyGoal(false, 0, 0).label)
        assertEquals("1/1", dailyGoal(true, 0, 0).label)
        assertTrue(dailyGoal(true, 0, 0).complete)
    }

    @Test fun dailyGoalClampsOutOfRangeCounts() {
        val goal = dailyGoal(true, tasksDone = 9, tasksTotal = 2)
        assertEquals(2, goal.tasksDone)
        assertEquals("3/3", goal.label)
        assertEquals(0, dailyGoal(false, -4, 2).tasksDone)
        assertEquals(0, dailyGoal(false, 0, -3).tasksTotal)
    }

    @Test fun phraseNamesWhatIsMissing() {
        assertEquals("¡Hola, Lucía! Te faltan 2 tareas y 1 lección para cerrar el día.", goalPhrase(dailyGoal(false, 1, 3), "Lucía").plain())
        assertEquals("¡Hola, Lucía! Te falta 1 tarea para cerrar el día.", goalPhrase(dailyGoal(true, 2, 3), "Lucía").plain())
        assertEquals("¡Hola, Lucía! Te faltan 2 tareas para cerrar el día.", goalPhrase(dailyGoal(true, 1, 3), "Lucía").plain())
        assertEquals("¡Hola, Lucía! Te falta 1 lección para cerrar el día.", goalPhrase(dailyGoal(false, 3, 3), "Lucía").plain())
        assertEquals("¡Hola! Te falta 1 lección para cerrar el día.", goalPhrase(dailyGoal(false, 0, 0), "").plain())
    }

    @Test fun phraseHighlightsTheMissingPartsAndCelebratesTheCompleteDay() {
        val parts = goalPhrase(dailyGoal(false, 1, 3), "Lucía")
        assertEquals(listOf("2 tareas", "1 lección"), parts.filter { it.bold }.map { it.text })
        assertTrue(goalPhrase(dailyGoal(true, 3, 3), "Lucía").plain().startsWith("¡Día completo!"))
    }

    // ---- Misiones ---------------------------------------------------------------------------

    @Test fun missionsListLateFirstThenByTimeAndDoneLast() {
        val plans = listOf(
            plan("hecha", due = "08:00", done = true), plan("sin-hora"), plan("tarde", due = "15:00"),
            plan("vencida", due = "10:00", overdue = true), plan("temprano", due = "12:00"),
        )
        assertEquals(listOf("vencida", "temprano", "tarde", "sin-hora", "hecha"), missionOrder(plans).map { it.id })
    }

    @Test fun missionDetailSaysLateTimeShiftOrWhoDidIt() {
        assertEquals("Venció a las 10:00 a. m. · Cocina", missionDetail(plan("a", due = "10:00", overdue = true)))
        assertEquals("Hasta las 12:00 p. m. · Cocina", missionDetail(plan("b", due = "12:00")))
        assertEquals("Hecho por ti · Cocina", missionDetail(plan("c", done = true, byMe = true)))
        assertEquals("Hecho por tu equipo · Cocina", missionDetail(plan("d", done = true)))
        assertEquals("Turno tarde · Salón", missionDetail(plan("e", area = "Salón", shift = CleaningPlanShift("s", "Turno tarde", "OPEN"))))
        assertEquals("Semanal · Cocina", missionDetail(plan("f", frequency = CleaningFrequency.WEEKLY)))
        assertEquals("Vencida", missionDetail(plan("g", area = "", overdue = true)))
    }

    @Test fun lateOnlyAppliesToPendingTasks() {
        assertTrue(isLate(plan("a", overdue = true)))
        assertFalse(isLate(plan("b", overdue = true, done = true)))
        assertFalse(isLate(plan("c")))
    }

    @Test fun missionIconFollowsKeywordsOfTheTask() {
        assertEquals(BpmSymbol.Thermometer, missionIcon(plan("a", task = "Temperatura de la cámara")).symbol)
        assertEquals(BpmSymbol.Drop, missionIcon(plan("b", task = "Lavado de manos al ingresar")).symbol)
        assertEquals(BpmSymbol.Box, missionIcon(plan("c", task = "Recepción de insumos")).symbol)
        assertEquals(BpmSymbol.Wrench, missionIcon(plan("d", task = "Limpieza de campana extractora")).symbol)
        val spray = missionIcon(plan("e", task = "Desinfectar mesas de preparación"))
        assertEquals(BpmSymbol.Spray, spray.symbol)
        assertEquals(BpmPlayTone.Streak, spray.tone)
    }

    // ---- Grupos de Tareas -----------------------------------------------------------------------

    // Inicio del periodo = 05:00Z = medianoche en Lima; «ahora» a las 10:00 locales = 15:00Z.
    private val tenLocal = kotlin.time.Instant.parse("2026-11-20T15:00:00Z").toEpochMilliseconds()

    @Test fun tasksGroupIntoNowLaterAndDone() {
        val plans = listOf(
            plan("hecha", due = "08:00", done = true),
            plan("vencida", due = "09:00", overdue = true),
            plan("pronto", due = "11:30"),          // dentro de 2 h
            plan("tarde", due = "15:00"),           // faltan 5 h
            plan("diaria-sin-hora"),
            plan("semanal-sin-hora", frequency = CleaningFrequency.WEEKLY),
            plan("turno", frequency = CleaningFrequency.PER_SHIFT, shift = CleaningPlanShift("s", "Turno mañana", "OPEN")),
        )
        val groups = taskGroups(plans, tenLocal)
        assertEquals(setOf("vencida", "pronto", "turno", "diaria-sin-hora"), groups.now.map { it.id }.toSet())
        assertEquals(listOf("tarde", "semanal-sin-hora"), groups.later.map { it.id })
        assertEquals(listOf("hecha"), groups.done.map { it.id })
        assertEquals("vencida", groups.now.first().id) // vencidas primero dentro del grupo
    }

    @Test fun withoutTheClockOnlyLateOpenShiftAndDailyWithoutHourAreNow() {
        val plans = listOf(plan("tarde", due = "15:00"), plan("vencida", due = "09:00", overdue = true), plan("diaria"))
        val groups = taskGroups(plans, null)
        assertEquals(setOf("vencida", "diaria"), groups.now.map { it.id }.toSet())
        assertEquals(listOf("tarde"), groups.later.map { it.id })
    }

    @Test fun siteMinutesComeFromTheStartOfThePeriod() {
        assertEquals(600, siteMinutesOfDay(plan("a"), tenLocal))
        // Un periodo semanal (empieza hace 3 días) da la misma hora local.
        assertEquals(600, siteMinutesOfDay(plan("b", periodStart = "2026-11-17T05:00:00Z"), tenLocal))
        assertNull(siteMinutesOfDay(CleaningPlan("c", site, "Cocina", "c"), tenLocal))
    }

    @Test fun shiftTitleUsesTheOpenShift() {
        assertEquals("Turno mañana", shiftTitle(listOf(plan("a", shift = CleaningPlanShift("s", "mañana", "OPEN")))))
        assertEquals("Turno tarde", shiftTitle(listOf(plan("a", shift = CleaningPlanShift("s", "Turno tarde", "OPEN")))))
        assertEquals("Tu turno", shiftTitle(listOf(plan("a", shift = CleaningPlanShift("s", "Turno tarde", "UPCOMING")))))
        assertEquals("Tu turno", shiftTitle(emptyList()))
    }

    // ---- Avance del turno ------------------------------------------------------------------

    @Test fun shiftProgressCountsDoneAndOffersTheNextPending() {
        val plans = listOf(plan("hecha", done = true), plan("vencida", due = "09:00", overdue = true), plan("tarde", due = "15:00"))
        val progress = shiftProgress(plans)
        assertEquals(1, progress.done)
        assertEquals(3, progress.total)
        assertEquals("1 de 3", progress.label)
        assertEquals("vencida", progress.next?.id)
        assertFalse(progress.complete)
    }

    @Test fun theTaskJustDoneCountsAndIsNeverOfferedAgain() {
        val plans = listOf(plan("hecha", done = true), plan("vencida", due = "09:00", overdue = true), plan("tarde", due = "15:00"))
        val progress = shiftProgress(plans, justDoneId = "vencida")
        assertEquals(2, progress.done)
        assertEquals("tarde", progress.next?.id)
        val last = shiftProgress(listOf(plan("a", done = true), plan("b")), justDoneId = "b")
        assertTrue(last.complete)
        assertNull(last.next)
        assertEquals(1f, last.progress)
    }

    @Test fun shiftProgressWithoutTasksIsEmpty() {
        val progress = shiftProgress(emptyList())
        assertEquals(0, progress.total)
        assertEquals(0f, progress.progress)
        assertFalse(progress.complete)
    }

    @Test fun nextTaskTextAddsTheDueTimeWhenThereIsOne() {
        assertEquals("Temperatura de la cámara (hasta 12:00 p. m.)", nextTaskText(plan("a", task = "Temperatura de la cámara", due = "12:00")))
        assertEquals("Barrer", nextTaskText(plan("b", task = "Barrer")))
    }

    // ---- Registro rápido ---------------------------------------------------------------------

    @Test fun noteStepOnlyExistsWhenTheTaskDidNotGoWell() {
        assertEquals(listOf(QuickStep.Result, QuickStep.Photo), quickSteps(CleaningResult.COMPLETED))
        assertEquals(listOf(QuickStep.Result, QuickStep.Note, QuickStep.Photo), quickSteps(CleaningResult.OBSERVED))
        assertEquals(listOf(QuickStep.Result, QuickStep.Note, QuickStep.Photo), quickSteps(CleaningResult.NOT_COMPLETED))
    }

    @Test fun clockIsTheTimeOfADisplayDate() {
        assertEquals("10:42 p. m.", clockOf("20/11/2026 · 10:42 p. m."))
        assertEquals("10:42", clockOf("20/11/2026 · 10:42"))
        assertEquals("8:05 a. m.", clockOf("20/11/26, 8:05 a. m."))
        assertEquals("", clockOf(""))
    }
}
