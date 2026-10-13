package pe.bpm.features.identity.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.identity.domain.*

data class IdentityUiState(
    val busy: Boolean = false,
    val profile: Profile? = null,
    val errors: List<IdentityError> = emptyList(),
)

class IdentityViewModel(
    private val signIn: SignIn,
    private val getCurrentProfile: GetCurrentProfile,
    private val signOut: SignOut,
    private val signInWithGoogle: SignInWithGoogle,
    val googleSupported: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(IdentityUiState())
    val state = mutableState.asStateFlow()
    private var requestJob: Job? = null

    fun login(email: String, password: String) {
        if (mutableState.value.busy) return
        mutableState.value = IdentityUiState(busy = true)
        requestJob = viewModelScope.launch {
            applyResult(signIn(email, password))
        }
    }

    fun verifySession() {
        if (mutableState.value.busy || mutableState.value.profile == null) return
        mutableState.value = mutableState.value.copy(busy = true, errors = emptyList())
        requestJob = viewModelScope.launch {
            applyResult(getCurrentProfile(), keepProfileOnTransientFailure = true)
        }
    }

    fun loginWithGoogle() {
        if (mutableState.value.busy) return
        mutableState.value = IdentityUiState(busy = true)
        // UNDISPATCHED conserva el gesto del usuario al abrir el popup en web.
        requestJob = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            applyResult(signInWithGoogle())
        }
    }

    fun logout() {
        requestJob?.cancel()
        signOut()
        mutableState.value = IdentityUiState()
    }

    private fun applyResult(result: IdentityResult<Profile>, keepProfileOnTransientFailure: Boolean = false) {
        mutableState.value = when (result) {
            is IdentityResult.Success -> IdentityUiState(profile = result.value)
            is IdentityResult.Failure -> {
                val sessionDenied = result.errors.any {
                    it.code in setOf("UNAUTHENTICATED", "USER_INACTIVE", "USER_NOT_REGISTERED")
                }
                IdentityUiState(profile = mutableState.value.profile.takeIf { keepProfileOnTransientFailure && !sessionDenied },
                    errors = result.errors)
            }
        }
    }
}
