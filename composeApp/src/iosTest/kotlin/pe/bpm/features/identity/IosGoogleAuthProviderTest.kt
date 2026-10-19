package pe.bpm.features.identity

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.identity.data.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class IosGoogleAuthProviderTest {
    private class Driver : IosGoogleSignInDriver {
        override val configured = true
        var callback: ((GoogleAuthResult) -> Unit)? = null
        var clears = 0
        override fun start(onResult: (GoogleAuthResult) -> Unit) { callback = onResult }
        override fun clear() { clears++ }
    }

    @Test fun cancellingSelectionIgnoresLateCallbackAndAllowsAnotherAttempt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val driver = Driver()
            val provider = IosGoogleAuthProvider(driver)
            val first = async { provider.signIn() }
            runCurrent()
            val lateCallback = assertNotNull(driver.callback)
            first.cancelAndJoin()
            assertEquals(1, driver.clears)
            val second = async { provider.signIn() }
            runCurrent()
            lateCallback(GoogleAuthResult.Federated("late-token", "late-nonce"))
            assertFalse(second.isCompleted)
            assertNotNull(driver.callback)(GoogleAuthResult.Failure("GOOGLE_CANCELLED"))
            assertEquals("GOOGLE_CANCELLED", assertIs<GoogleAuthResult.Failure>(second.await()).code)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun repeatedNativeCallbackCannotReplaceTheSelectedCredential() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val driver = Driver()
            val provider = IosGoogleAuthProvider(driver)
            val operation = async { provider.signIn() }
            runCurrent()
            val result = GoogleAuthResult.Federated("google-test-token", "nonce-test")
            val callback = assertNotNull(driver.callback)
            callback(result)
            callback(GoogleAuthResult.Failure("GOOGLE_UNAVAILABLE"))
            assertSame(result, operation.await())
        } finally { Dispatchers.resetMain() }
    }
}
