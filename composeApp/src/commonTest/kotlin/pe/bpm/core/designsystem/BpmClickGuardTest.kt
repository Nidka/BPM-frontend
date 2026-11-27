package pe.bpm.core.designsystem

import kotlin.test.*

class BpmClickGuardTest {
    @Test fun oneTapOneActionEvenIfTheFingerBounces() {
        var t = 0L
        val guard = BpmClickGuard(600) { t }
        assertTrue(guard.accept())
        t = 80; assertFalse(guard.accept())
        t = 590; assertFalse(guard.accept())
        t = 700; assertTrue(guard.accept())
    }
}
