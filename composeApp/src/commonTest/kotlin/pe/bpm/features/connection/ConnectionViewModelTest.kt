package pe.bpm.features.connection

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import pe.bpm.core.domain.AppFailure
import pe.bpm.core.domain.AppResult
import pe.bpm.features.connection.domain.CheckConnection
import pe.bpm.features.connection.domain.ConnectionRepository
import pe.bpm.features.connection.domain.ConnectionStatus
import pe.bpm.features.connection.presentation.ConnectionUiState
import pe.bpm.features.connection.presentation.ConnectionViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    @Test
    fun exposesSafeFailureAndAllowsRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var attempts = 0
        val repository = object : ConnectionRepository {
            override suspend fun check(): AppResult<ConnectionStatus> =
                if (++attempts == 1) AppResult.Failure(AppFailure.NETWORK)
                else AppResult.Success(ConnectionStatus("version-2"))
        }
        try {
            val model = ConnectionViewModel(CheckConnection(repository))
            store.put("connection", model)
            assertIs<ConnectionUiState.Checking>(model.state.value)
            advanceUntilIdle()
            assertEquals("Sin conexión. Revisa tu internet y vuelve a intentarlo.",
                assertIs<ConnectionUiState.Unavailable>(model.state.value).message)
            model.retry()
            advanceUntilIdle()
            assertEquals(ConnectionUiState.Connected("version-2"), model.state.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retryCancelsPreviousRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var attempts = 0
        var cancelled = false
        val repository = object : ConnectionRepository {
            override suspend fun check(): AppResult<ConnectionStatus> {
                if (++attempts == 1) {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                return AppResult.Success(ConnectionStatus("latest"))
            }
        }
        try {
            val model = ConnectionViewModel(CheckConnection(repository))
            store.put("connection", model)
            runCurrent()
            model.retry()
            advanceUntilIdle()
            assertTrue(cancelled)
            assertEquals(ConnectionUiState.Connected("latest"), model.state.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
