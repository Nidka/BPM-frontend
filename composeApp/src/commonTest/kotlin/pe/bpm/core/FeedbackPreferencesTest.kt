package pe.bpm.core

import kotlinx.coroutines.test.runTest
import pe.bpm.core.designsystem.BpmFeedbackSettings
import pe.bpm.core.presentation.FeedbackPreferences
import pe.bpm.core.presentation.decodeFeedbackSettings
import pe.bpm.core.presentation.encodeFeedbackSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackPreferencesTest {
    @Test fun roundTrip() {
        val off = BpmFeedbackSettings(sound = false, vibration = true)
        assertEquals(off, decodeFeedbackSettings(encodeFeedbackSettings(off)))
        val both = BpmFeedbackSettings(sound = false, vibration = false)
        assertEquals(both, decodeFeedbackSettings(encodeFeedbackSettings(both)))
    }

    @Test fun missingOrBrokenMeansOn() {
        assertEquals(BpmFeedbackSettings(), decodeFeedbackSettings("{}"))
        assertEquals(BpmFeedbackSettings(), decodeFeedbackSettings("no es json"))
        assertEquals(BpmFeedbackSettings(sound = false), decodeFeedbackSettings("""{"sound":false}"""))
    }

    @Test fun withoutStoreItStaysInMemory() = runTest {
        val prefs = FeedbackPreferences()
        prefs.setVibration(false)
        assertEquals(BpmFeedbackSettings(sound = true, vibration = false), prefs.state.value)
    }
}
