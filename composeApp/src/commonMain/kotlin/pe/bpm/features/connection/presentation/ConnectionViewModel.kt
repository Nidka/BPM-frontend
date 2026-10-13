package pe.bpm.features.connection.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.core.domain.AppFailure
import pe.bpm.core.domain.AppResult
import pe.bpm.features.connection.domain.CheckConnection

sealed interface ConnectionUiState {
    data object Checking : ConnectionUiState
    data class Connected(val version: String) : ConnectionUiState
    data class Unavailable(val message: String) : ConnectionUiState
}

class ConnectionViewModel(private val checkConnection: CheckConnection) : ViewModel() {
    private val mutableState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.Checking)
    val state = mutableState.asStateFlow()
    private var checkJob: Job? = null

    init { retry() }

    fun retry() {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            mutableState.value = ConnectionUiState.Checking
            mutableState.value = when (val result = checkConnection()) {
                is AppResult.Success -> ConnectionUiState.Connected(result.value.backendVersion)
                is AppResult.Failure -> ConnectionUiState.Unavailable(result.error.safeMessage())
            }
        }
    }
}

private fun AppFailure.safeMessage(): String = when (this) {
    AppFailure.NETWORK -> "Sin conexión. Revisa tu internet y vuelve a intentarlo."
    AppFailure.TIMEOUT -> "La conexión tardó demasiado. Inténtalo nuevamente."
    AppFailure.UNAVAILABLE -> "El servicio no está disponible en este momento."
    AppFailure.INVALID_RESPONSE -> "No pudimos verificar el estado del servicio."
    AppFailure.UNKNOWN -> "No se pudo completar la comprobación."
}
