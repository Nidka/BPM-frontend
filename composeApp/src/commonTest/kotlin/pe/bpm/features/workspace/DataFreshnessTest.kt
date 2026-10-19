package pe.bpm.features.workspace

import pe.bpm.core.presentation.DataFreshness
import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.ExperimentalTime
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalTime::class)
class DataFreshnessTest {
    @Test fun onlyBecomesStaleAfterTheFreshnessWindowAndResetsAcrossContexts() {
        val clock = TestTimeSource()
        val freshness = DataFreshness(clock)
        assertTrue(freshness.stale)
        freshness.loaded(); clock += 1.minutes
        assertFalse(freshness.stale)
        clock += 1.minutes
        assertTrue(freshness.stale)
        freshness.loaded(); assertFalse(freshness.stale)
        freshness.reset(); assertTrue(freshness.stale)
    }
}
