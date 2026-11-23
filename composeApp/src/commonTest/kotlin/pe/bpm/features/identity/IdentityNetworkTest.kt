package pe.bpm.features.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import pe.bpm.features.identity.data.IDENTITY_BACKOFF_MS
import pe.bpm.features.identity.data.IDENTITY_REQUEST_TIMEOUT_MS
import pe.bpm.features.identity.data.IdentityDataException
import pe.bpm.features.identity.data.identityFailure
import pe.bpm.features.identity.data.retryingNetwork

/** El acceso tolera una red lenta o un corte breve: 30 s por llamada y hasta dos reintentos con espera creciente. */
class IdentityNetworkTest {
    @Test fun aBriefNetworkFailureIsRetriedAndTheLoginSucceeds() = runTest {
        var calls = 0
        val result = retryingNetwork { if (++calls < 3) error("sin red") else "ok" }
        assertEquals("ok", result); assertEquals(3, calls)
    }

    @Test fun itGivesUpAfterThreeAttemptsAndShowsTheLastError() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> { retryingNetwork<String> { calls++; error("sin red") } }
        assertEquals(1 + IDENTITY_BACKOFF_MS.size, calls)
    }

    @Test fun aRejectedAnswerIsNeverRetried() = runTest {
        var calls = 0
        assertFailsWith<IdentityDataException> { retryingNetwork<String> { calls++; throw IdentityDataException(identityFailure("INVALID_CREDENTIALS")) } }
        assertEquals(1, calls)
    }

    @Test fun theLimitIsLongerThanTheGeneralTwelveSeconds() { assertEquals(30_000L, IDENTITY_REQUEST_TIMEOUT_MS) }
}
