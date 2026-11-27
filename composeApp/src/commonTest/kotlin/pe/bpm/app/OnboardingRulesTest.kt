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
import pe.bpm.core.presentation.introAlreadySeen
import pe.bpm.core.presentation.OnboardingSync
import pe.bpm.core.offline.InMemoryOfflineDatabase
import pe.bpm.core.offline.OfflinePlatform
import pe.bpm.core.offline.OfflineRuntime
import pe.bpm.core.offline.OfflineScope

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

    @Test fun introShowsOnlyIfNobodyMarkedItSeen() {
        assertFalse(introAlreadySeen(serverSeen = false, accountSeen = false, deviceSeen = false))
        assertTrue(introAlreadySeen(serverSeen = true, accountSeen = false, deviceSeen = false))
        assertTrue(introAlreadySeen(serverSeen = false, accountSeen = true, deviceSeen = false))
        assertTrue(introAlreadySeen(serverSeen = false, accountSeen = false, deviceSeen = true))
    }

    private class RecordingSync(var ok: Boolean = true) : OnboardingSync {
        val calls = mutableListOf<Boolean>()
        override suspend fun setSeen(seen: Boolean): Boolean { calls += seen; return ok }
    }

    @Test fun introSeenSurvivesLogoutAndSyncsWithTheServer() = runTest {
        val runtime = OfflineRuntime(OfflinePlatform(database = InMemoryOfflineDatabase()), { 1_000L }, { "op" })
        runtime.activate(OfflineScope("https://api.test", "ana"))
        val sync = RecordingSync()
        val prefs = OnboardingPreferences(runtime.store, runtime.device, sync)
        prefs.load("ana", serverSeen = false)
        assertFalse(prefs.state.value.introSeen)
        assertTrue(sync.calls.isEmpty())
        prefs.markIntroSeen()
        assertEquals(listOf(true), sync.calls)
        // Cierre de sesión explícito: se borra la cuenta, pero la marca del dispositivo queda.
        runtime.wipe(); prefs.reset()
        runtime.activate(OfflineScope("https://api.test", "ana"))
        prefs.load("ana", serverSeen = false)
        assertTrue(prefs.state.value.introSeen)
        // El servidor aún no lo tenía: se vuelve a subir la marca.
        assertEquals(listOf(true, true), sync.calls)
        // Otra persona en el mismo teléfono sí la ve.
        prefs.reset(); runtime.activate(OfflineScope("https://api.test", "beto"))
        prefs.load("beto", serverSeen = false)
        assertFalse(prefs.state.value.introSeen)
    }

    @Test fun serverSeenWinsOnANewDeviceAndReplayClearsEverywhere() = runTest {
        val runtime = OfflineRuntime(OfflinePlatform(database = InMemoryOfflineDatabase()), { 1_000L }, { "op" })
        runtime.activate(OfflineScope("https://api.test", "ana"))
        val sync = RecordingSync(ok = false)
        val prefs = OnboardingPreferences(runtime.store, runtime.device, sync)
        prefs.load("ana", serverSeen = true)
        assertTrue(prefs.state.value.introSeen)
        assertTrue(sync.calls.isEmpty(), "no hace falta subir lo que el servidor ya sabe")
        prefs.replay()
        assertFalse(prefs.state.value.introSeen)
        assertEquals(listOf(false), sync.calls)
        prefs.load("ana", serverSeen = false)
        assertFalse(prefs.state.value.introSeen)
    }
}
