package pe.bpm.features.accounts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.accounts.domain.*

/**
 * Formulario de invitación. [id] se genera en el cliente y se conserva mientras el contenido no cambia:
 * reintentar el mismo envío no duplica la invitación; editar el contenido genera otro identificador.
 */
data class InviteForm(val id: String, val email: String = "", val role: MemberRole = MemberRole.WORKER,
    val locationIds: Set<String> = emptySet(), val fields: Map<String, String> = emptyMap())

/** Panel para compartir la invitación creada o reenviada. */
data class ShareState(val email: String, val share: InvitationShare, val resent: Boolean) {
    fun message(organizationName: String) = InvitationSharing.message(organizationName, email, share)
    fun whatsappUrl(organizationName: String) = InvitationSharing.whatsappUrl(message(organizationName))
}

data class InvitationsState(
    val organizationId: String? = null,
    val allowed: Boolean = false,
    /** Nulo = todas. */
    val status: InvitationStatus? = null,
    val page: AccountPage<Invitation> = AccountPage(),
    val locations: List<AccountLocation> = emptyList(),
    val locationsFailure: AccountFailure? = null,
    val form: InviteForm? = null,
    /** Enlace o código recién entregado; vive solo en memoria y se descarta al cerrar el panel. */
    val share: ShareState? = null,
    val organizationName: String = "",
    val revoking: Invitation? = null,
    val busy: Boolean = false,
    val failure: AccountFailure? = null,
    val notice: String? = null,
    /** Reloj de la pantalla para la cuenta regresiva del reenvío. */
    val now: Instant? = null,
) {
    fun resendWait(invitation: Invitation): Long = now?.let { AccountsRules.resendWaitSeconds(invitation.lastSentAt, it) } ?: 0
}

class InvitationsViewModel(
    private val listInvitations: ListInvitations,
    private val loadLocations: LoadActiveLocations,
    private val inviteMember: InviteMember,
    private val resendInvitation: ResendInvitation,
    private val revokeInvitation: RevokeInvitation,
    private val newId: () -> String,
    private val clock: () -> Instant,
) : ViewModel() {
    private val mutable = MutableStateFlow(InvitationsState())
    val state = mutable.asStateFlow()
    private var listJob: Job? = null
    private var actionJob: Job? = null

    fun open(organizationId: String, allowed: Boolean, organizationName: String = "") {
        reset()
        mutable.value = InvitationsState(organizationId = organizationId, allowed = allowed, organizationName = organizationName, now = clock())
        if (!allowed) return
        fetch(mutable.value.page, null)
        viewModelScope.launch {
            val result = loadLocations(organizationId)
            currentCoroutineContext().ensureActive()
            if (mutable.value.organizationId != organizationId) return@launch
            mutable.update {
                when (result) {
                    is AccountsResult.Success -> it.copy(locations = result.value, locationsFailure = null)
                    is AccountsResult.Failure -> it.copy(locationsFailure = result.reason)
                }
            }
        }
    }

    fun reset() { listJob?.cancel(); actionJob?.cancel(); mutable.value = InvitationsState() }
    fun clearMessages() = mutable.update { it.copy(failure = null, notice = null) }
    /** Descarta el enlace o código en memoria; si se pierde, se reenvía la invitación. */
    fun closeShare() = mutable.update { it.copy(share = null) }
    /** La pantalla lo llama cada segundo mientras está visible. */
    fun tick() = mutable.update { it.copy(now = clock()) }

    fun refresh() { val s = mutable.value; if (s.allowed) fetch(s.page.restart(), s.status) }
    fun selectStatus(status: InvitationStatus?) {
        val s = mutable.value
        if (!s.allowed || s.busy || status == s.status) return
        mutable.update { it.copy(status = status, notice = null) }
        fetch(s.page.search(s.page.request.query), status)
    }
    fun search(query: String) { val s = mutable.value; if (s.allowed) fetch(s.page.search(query), s.status, debounce = true) }
    fun next() { val s = mutable.value; s.page.forward()?.let { fetch(it, s.status) } }
    fun previous() { val s = mutable.value; s.page.backward()?.let { fetch(it, s.status) } }
    fun retry() { val s = mutable.value; fetch(s.page.retry(), s.status) }

    private fun fetch(page: AccountPage<Invitation>, status: InvitationStatus?, debounce: Boolean = false) {
        val organization = mutable.value.organizationId ?: return
        listJob?.cancel()
        mutable.update { it.copy(page = page.copy(loading = true, failure = null)) }
        listJob = viewModelScope.launch {
            if (debounce) delay(300)
            val result = listInvitations(organization, status, page.request)
            currentCoroutineContext().ensureActive()
            if (mutable.value.organizationId != organization || mutable.value.status != status) return@launch
            mutable.update { s ->
                when (result) {
                    is AccountsResult.Success -> s.copy(page = page.copy(items = result.value.items, nextToken = result.value.nextToken, loading = false, failure = null), now = clock())
                    is AccountsResult.Failure -> s.copy(page = page.copy(items = emptyList(), nextToken = "", loading = false, failure = result.reason))
                }
            }
        }
    }

    // ---- Invitar -----------------------------------------------------------------------------

    fun openForm() { val s = mutable.value; if (s.allowed && !s.busy) mutable.update { it.copy(form = InviteForm(newId()), share = null, revoking = null, failure = null, notice = null) } }
    fun cancelForm() { if (!mutable.value.busy) mutable.update { it.copy(form = null) } }
    private fun edit(change: (InviteForm) -> InviteForm) = mutable.update { s -> s.form?.let { s.copy(form = change(it).copy(id = newId(), fields = emptyMap())) } ?: s }
    fun editEmail(email: String) = edit { it.copy(email = email.take(320)) }
    fun editRole(role: MemberRole) = edit { it.copy(role = role) }
    fun toggleLocation(id: String) = edit { it.copy(locationIds = if (id in it.locationIds) it.locationIds - id else it.locationIds + id) }

    fun submit() {
        val s = mutable.value
        val form = s.form ?: return
        val organization = s.organizationId ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, failure = null, notice = null) }
        actionJob = viewModelScope.launch {
            val result = inviteMember(organization, form.id, form.email, form.role, form.locationIds.toList())
            currentCoroutineContext().ensureActive()
            when (result) {
                is AccountsResult.Success -> {
                    mutable.update { it.copy(busy = false, form = null, share = ShareState(result.value.invitation.email, result.value.share, resent = false), notice = "Enviamos la invitación a ${result.value.invitation.email}. Tiene 7 días de vigencia.") }
                    refresh()
                }
                is AccountsResult.Failure ->
                    if (result.reason == AccountFailure.VALIDATION && result.fields.isNotEmpty())
                        mutable.update { it.copy(busy = false, form = it.form?.copy(fields = result.fields)) }
                    else mutable.update { it.copy(busy = false, failure = result.reason) }
            }
        }
    }

    // ---- Reenviar y revocar ------------------------------------------------------------------

    fun resend(invitation: Invitation) {
        val s = mutable.value
        val organization = s.organizationId ?: return
        if (s.busy || !s.allowed) return
        // La espera de 1 minuto se respeta antes de llamar; el servidor la vuelve a validar (429).
        if (AccountsRules.resendWaitSeconds(invitation.lastSentAt, clock()) > 0) {
            mutable.update { it.copy(failure = AccountFailure.RESEND_TOO_SOON, notice = null, now = clock()) }
            return
        }
        mutable.update { it.copy(busy = true, share = null, failure = null, notice = null) }
        actionJob = viewModelScope.launch {
            val result = resendInvitation(organization, invitation)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AccountsResult.Success -> { mutable.update { it.copy(busy = false, share = ShareState(result.value.invitation.email, result.value.share, resent = true), notice = "Reenviamos la invitación a ${result.value.invitation.email}. El enlace anterior dejó de servir.") }; refresh() }
                is AccountsResult.Failure -> {
                    mutable.update { it.copy(busy = false, failure = result.reason) }
                    if (result.reason == AccountFailure.INVITATION_NOT_PENDING || result.reason == AccountFailure.NOT_FOUND || result.reason == AccountFailure.VERSION_CONFLICT) refresh()
                }
            }
        }
    }

    fun askRevoke(invitation: Invitation) { val s = mutable.value; if (s.allowed && !s.busy) mutable.update { it.copy(revoking = invitation, form = null, share = null, failure = null, notice = null) } }
    fun cancelRevoke() { if (!mutable.value.busy) mutable.update { it.copy(revoking = null) } }
    fun confirmRevoke() {
        val s = mutable.value
        val invitation = s.revoking ?: return
        val organization = s.organizationId ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, failure = null, notice = null) }
        actionJob = viewModelScope.launch {
            val result = revokeInvitation(organization, invitation)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AccountsResult.Success -> { mutable.update { it.copy(busy = false, revoking = null, notice = "Invitación revocada.") }; refresh() }
                is AccountsResult.Failure -> {
                    mutable.update { it.copy(busy = false, revoking = null, failure = result.reason) }
                    refresh()
                }
            }
        }
    }
}
