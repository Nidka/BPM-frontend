package pe.bpm.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import pe.bpm.core.presentation.OnboardingPreferences
import pe.bpm.core.presentation.OnboardingState
import pe.bpm.core.presentation.decodeOnboarding
import pe.bpm.core.presentation.encodeOnboarding

/** «Primeros pasos» y «Ayuda»: tarjetas por rol, pasos que se marcan solos, ayuda por ruta y persistencia. */
class OnboardingRulesTest {
    @Test fun everyRoleHasAShortIntroAndEveryCardSaysSomething() {
        GuideRole.entries.forEach { role ->
            val cards = introCards(role)
            assertTrue(cards.size in 4..5, "$role: ${cards.size} tarjetas")
            assertTrue(cards.all { it.title.isNotBlank() && it.body.length in 20..220 })
            assertEquals(cards.size, cards.map { it.title }.toSet().size)
        }
    }

    @Test fun adminStepsCompleteThemselvesFromRealData() {
        val empty = firstSteps(GuideRole.Admin, StepSignals())
        assertEquals(listOf("a-site", "a-team", "a-control", "a-course"), empty.map { it.id })
        assertEquals(0, empty.doneCount())
        val some = firstSteps(GuideRole.Admin, StepSignals(locations = 1, members = 3, scheduledControls = true))
        assertEquals(listOf(true, true, true, false), some.map { it.done })
        // Un solo miembro (el propio administrador) no cuenta como «invitar a tu equipo».
        assertFalse(firstSteps(GuideRole.Admin, StepSignals(members = 1)).first { it.id == "a-team" }.done)
    }

    @Test fun manualMarksCountAndWorkerStepsUseRealSignals() {
        val worker = firstSteps(GuideRole.Worker, StepSignals(tasksDone = 1, manual = setOf("w-report")))
        assertEquals(listOf(false, true, true), worker.map { it.done })
        assertTrue(firstSteps(GuideRole.Worker, StepSignals(lessonsStarted = true)).first().done)
        assertEquals(4, firstSteps(GuideRole.Chef, StepSignals()).size)
        assertEquals(2, firstSteps(GuideRole.Super, StepSignals(organizations = 3)).doneCount() + 1)
    }

    @Test fun helpFollowsTheRouteOfTheScreenItWasOpenedFrom() {
        assertEquals(HelpTopic.Home, helpTopicForRoute("inicio"))
        assertEquals(HelpTopic.Learn, helpTopicForRoute("aprender"))
        assertEquals(HelpTopic.Course, helpTopicForRoute("aprender/abc"))
        assertEquals(HelpTopic.Tasks, helpTopicForRoute("tareas"))
        assertEquals(HelpTopic.Report, helpTopicForRoute("tareas/incidencias"))
        assertEquals(HelpTopic.Report, helpTopicForRoute("reportar"))
        assertEquals(HelpTopic.Controls, helpTopicForRoute("control/plantillas"))
        assertEquals(HelpTopic.Team, helpTopicForRoute("equipo"))
        assertEquals(HelpTopic.Company, helpTopicForRoute("empresa"))
        assertEquals(HelpTopic.Settings, helpTopicForRoute("ajustes"))
        assertEquals(HelpTopic.Generic, helpTopicForRoute("lo-que-sea"))
    }

    @Test fun everyTopicAndRoleHasPlainHelpWithAtMostFourSteps() {
        HelpTopic.entries.forEach { topic -> GuideRole.entries.forEach { role ->
            val entry = helpEntry(topic, role)
            assertTrue(entry.title.isNotBlank() && entry.purpose.isNotBlank(), "$topic/$role")
            assertTrue(entry.steps.size in 1..4, "$topic/$role: ${entry.steps.size} pasos")
        } }
        assertTrue(glossaryFor(GuideRole.Worker).any { it.term == "BPM" })
        assertTrue(glossaryFor(GuideRole.Admin).none { it.term == "Racha" })
    }

    @Test fun preferencesRoundTripAndBrokenJsonMeansNothingSeen() = runTest {
        val state = OnboardingState(introSeen = true, done = setOf("a-site", "a-team"), hidden = true)
        assertEquals(state, decodeOnboarding(encodeOnboarding(state)))
        assertEquals(OnboardingState(), decodeOnboarding("no es json"))
        val prefs = OnboardingPreferences()
        prefs.load()
        prefs.markStep("c-turn"); prefs.markIntroSeen()
        assertTrue(prefs.state.value.introSeen && "c-turn" in prefs.state.value.done)
        prefs.hide(true); prefs.replay()
        assertFalse(prefs.state.value.introSeen); assertFalse(prefs.state.value.hidden)
        assertTrue("c-turn" in prefs.state.value.done)
    }
}
