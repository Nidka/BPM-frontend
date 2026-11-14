package pe.bpm.features.accounts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.accounts.domain.*

data class InvitationAcceptState(
    val input: String = "",
    val busy: Boolean = false,
    val failure: AccountFailure? = null,
    val fieldError: String? = null,
    val accepted: AcceptedInvitation? = null,
)

/**
 * Aceptación de una invitación con el token pegado o recibido en el enlace del correo. El token solo se conserva
 * en memoria mientras la pantalla está abierta y nunca se registra ni se guarda en disco.
 */
class InvitationAcceptViewModel(private val acceptInvitation: AcceptInvitation) : ViewModel() {
    private val mutable = MutableStateFlow(InvitationAcceptState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var linkToken: String? = null

    /** Token recibido en el enlace; se acepta una sola vez por pantalla abierta, sin importar recomposiciones. */
    fun openWithLink(token: String?) {
        val valid = token?.let(AccountsRules::invitationToken) ?: return
        if (linkToken == valid) return
        linkToken = valid
        mutable.update { it.copy(input = valid, failure = null, fieldError = null) }
    }

    fun reset() { job?.cancel(); linkToken = null; mutable.value = InvitationAcceptState() }
    fun edit(text: String) = mutable.update { it.copy(input = text.take(2048), failure = null, fieldError = null) }

    fun accept() {
        val s = mutable.value
        if (s.busy || s.accepted != null) return
        mutable.update { it.copy(busy = true, failure = null, fieldError = null) }
        job = viewModelScope.launch {
            val result = acceptInvitation(s.input)
            currentCoroutineContext().ensureActive()
            mutable.update {
                when (result) {
                    is AccountsResult.Success -> it.copy(busy = false, accepted = result.value, input = "")
                    is AccountsResult.Failure ->
                        if (result.reason == AccountFailure.VALIDATION && result.fields.containsKey("token")) it.copy(busy = false, fieldError = result.fields["token"])
                        else it.copy(busy = false, failure = result.reason)
                }
            }
        }
    }
}
