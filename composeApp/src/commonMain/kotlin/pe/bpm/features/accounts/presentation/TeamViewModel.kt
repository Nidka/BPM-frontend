package pe.bpm.features.accounts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.accounts.domain.*

/** Edición del rol y de las sedes de una persona; [fields] viene de la validación local o del servidor. */
data class MemberEditor(val member: AccountMember, val role: MemberRole, val locationIds: Set<String>, val fields: Map<String, String> = emptyMap())

/** Acción de alto impacto pendiente de confirmación. */
sealed interface MemberAction {
    val member: AccountMember
    data class Deactivate(override val member: AccountMember) : MemberAction
    data class Reactivate(override val member: AccountMember) : MemberAction
    /** [actorRole] y [actorLocationIds] solo se piden cuando quien transfiere es un ADMIN miembro. */
    data class Transfer(override val member: AccountMember, val actorRole: MemberRole? = null, val actorLocationIds: Set<String> = emptySet(),
        val fields: Map<String, String> = emptyMap()) : MemberAction
}

data class TeamState(
    val organizationId: String? = null,
    val allowed: Boolean = false,
    val actorIsMember: Boolean = false,
    val status: RecordStatus = RecordStatus.ACTIVE,
    val page: AccountPage<AccountMember> = AccountPage(),
    /** Sedes activas para elegir al asignar; se leen hasta 5 páginas de 100. */
    val locations: List<AccountLocation> = emptyList(),
    val locationsFailure: AccountFailure? = null,
    val editor: MemberEditor? = null,
    val action: MemberAction? = null,
    val busy: Boolean = false,
    val failure: AccountFailure? = null,
    val notice: String? = null,
)

/** Equipo: miembros ACTIVE/INACTIVE, edición de rol y sedes, baja/reactivación y transferencia de administración. */
class TeamViewModel(
    private val listMembers: ListAccountMembers,
    private val loadLocations: LoadActiveLocations,
    private val updateMember: UpdateAccountMember,
    private val setActive: SetMemberActive,
    private val transfer: TransferAdministration,
) : ViewModel() {
    private val mutable = MutableStateFlow(TeamState())
    val state = mutable.asStateFlow()
    private var listJob: Job? = null
    private var actionJob: Job? = null

    fun open(organizationId: String, allowed: Boolean, actorIsMember: Boolean) {
        reset()
        mutable.value = TeamState(organizationId = organizationId, allowed = allowed, actorIsMember = actorIsMember)
        if (allowed) { fetch(mutable.value.page, mutable.value.status); readLocations(organizationId) }
    }

    fun reset() {
        listJob?.cancel(); actionJob?.cancel()
        mutable.value = TeamState()
    }

    fun clearMessages() = mutable.update { it.copy(failure = null, notice = null) }

    fun refresh() { val s = mutable.value; if (s.allowed) fetch(s.page.restart(), s.status) }
    fun selectStatus(status: RecordStatus) {
        val s = mutable.value
        if (!s.allowed || s.busy || status == s.status) return
        mutable.update { it.copy(status = status, notice = null) }
        fetch(s.page.search(s.page.request.query), status)
    }
    fun search(query: String) { val s = mutable.value; if (s.allowed) fetch(s.page.search(query), s.status, debounce = true) }
    fun next() { val s = mutable.value; s.page.forward()?.let { fetch(it, s.status) } }
    fun previous() { val s = mutable.value; s.page.backward()?.let { fetch(it, s.status) } }
    fun retry() { val s = mutable.value; fetch(s.page.retry(), s.status) }

    private fun fetch(page: AccountPage<AccountMember>, status: RecordStatus, debounce: Boolean = false) {
        val organization = mutable.value.organizationId ?: return
        listJob?.cancel()
        mutable.update { it.copy(page = page.copy(loading = true, failure = null)) }
        listJob = viewModelScope.launch {
            if (debounce) delay(300)
            val result = listMembers(organization, status, page.request)
            currentCoroutineContext().ensureActive()
            if (mutable.value.organizationId != organization || mutable.value.status != status) return@launch
            mutable.update { s ->
                when (result) {
                    is AccountsResult.Success -> s.copy(page = page.copy(items = result.value.items, nextToken = result.value.nextToken, loading = false, failure = null))
                    is AccountsResult.Failure -> s.copy(page = page.copy(items = emptyList(), nextToken = "", loading = false, failure = result.reason))
                }
            }
        }
    }

    private fun readLocations(organization: String) {
        viewModelScope.launch {
            val result = loadLocations(organization)
            currentCoroutineContext().ensureActive()
            if (mutable.value.organizationId != organization) return@launch
            mutable.update {
                when (result) {
                    is AccountsResult.Success -> it.copy(locations = result.value, locationsFailure = null)
                    is AccountsResult.Failure -> it.copy(locationsFailure = result.reason)
                }
            }
        }
    }

    // ---- Edición de rol y sedes -------------------------------------------------------------

    fun edit(member: AccountMember) {
        val s = mutable.value
        if (!s.allowed || s.busy) return
        // Una sede desactivada no puede asignarse: se parte solo de las sedes activas conocidas.
        val active = s.locations.map { it.id }.toSet()
        val kept = if (s.locations.isEmpty()) member.locationIds.toSet() else member.locationIds.filter { it in active }.toSet()
        mutable.update { it.copy(editor = MemberEditor(member, member.role, kept), action = null, failure = null, notice = null) }
    }
    fun cancelEdit() { if (!mutable.value.busy) mutable.update { it.copy(editor = null) } }
    fun editRole(role: MemberRole) = mutable.update { s -> s.editor?.let { s.copy(editor = it.copy(role = role, fields = emptyMap())) } ?: s }
    fun toggleLocation(id: String) = mutable.update { s ->
        s.editor?.let { e -> s.copy(editor = e.copy(locationIds = if (id in e.locationIds) e.locationIds - id else e.locationIds + id, fields = emptyMap())) } ?: s
    }

    fun saveEditor() {
        val s = mutable.value
        val editor = s.editor ?: return
        val organization = s.organizationId ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, failure = null, notice = null) }
        actionJob = viewModelScope.launch {
            val result = updateMember(organization, editor.member, editor.role, editor.locationIds.toList())
            currentCoroutineContext().ensureActive()
            when (result) {
                is AccountsResult.Success -> {
                    mutable.update { it.copy(busy = false, editor = null, notice = "Cambios guardados.") }
                    refresh()
                }
                is AccountsResult.Failure -> failed(result, editorFields = { f -> mutable.update { it.copy(editor = it.editor?.copy(fields = f)) } })
            }
        }
    }

    // ---- Baja, reactivación y transferencia --------------------------------------------------

    fun askDeactivate(member: AccountMember) = ask(MemberAction.Deactivate(member))
    fun askReactivate(member: AccountMember) = ask(MemberAction.Reactivate(member))
    fun askTransfer(member: AccountMember) = ask(MemberAction.Transfer(member))
    private fun ask(action: MemberAction) {
        if (!mutable.value.allowed || mutable.value.busy) return
        mutable.update { it.copy(action = action, editor = null, failure = null, notice = null) }
    }
    fun cancelAction() { if (!mutable.value.busy) mutable.update { it.copy(action = null, failure = null) } }

    fun transferRole(role: MemberRole) = mutable.update { s -> (s.action as? MemberAction.Transfer)?.let { s.copy(action = it.copy(actorRole = role, fields = emptyMap())) } ?: s }
    fun toggleTransferLocation(id: String) = mutable.update { s ->
        (s.action as? MemberAction.Transfer)?.let { a ->
            s.copy(action = a.copy(actorLocationIds = if (id in a.actorLocationIds) a.actorLocationIds - id else a.actorLocationIds + id, fields = emptyMap()))
        } ?: s
    }

    fun confirmAction() {
        val s = mutable.value
        val action = s.action ?: return
        val organization = s.organizationId ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, failure = null, notice = null) }
        actionJob = viewModelScope.launch {
            val outcome: AccountsResult<*> = when (action) {
                is MemberAction.Deactivate -> setActive(organization, action.member, active = false)
                is MemberAction.Reactivate -> setActive(organization, action.member, active = true)
                is MemberAction.Transfer -> transfer(organization, action.member, s.actorIsMember, action.actorRole, action.actorLocationIds.toList())
            }
            currentCoroutineContext().ensureActive()
            when (outcome) {
                is AccountsResult.Success -> {
                    val text = when (action) {
                        is MemberAction.Deactivate -> "La persona quedó inactiva y ya no puede entrar a la organización."
                        is MemberAction.Reactivate -> "La persona volvió a estar activa."
                        is MemberAction.Transfer -> "Transferiste la administración."
                    }
                    mutable.update { it.copy(busy = false, action = null, notice = text) }
                    refresh()
                }
                is AccountsResult.Failure -> failed(outcome, editorFields = { f ->
                    mutable.update { st -> (st.action as? MemberAction.Transfer)?.let { st.copy(action = it.copy(fields = f)) } ?: st }
                })
            }
        }
    }

    /**
     * Fallo común. Un conflicto de versión o un recurso ausente cierran el formulario y recargan la lista
     * para que la persona vea los datos vigentes antes de reintentar; nada se reenvía automáticamente.
     */
    private fun failed(result: AccountsResult.Failure, editorFields: (Map<String, String>) -> Unit) {
        when {
            result.reason == AccountFailure.VALIDATION && result.fields.isNotEmpty() -> { mutable.update { it.copy(busy = false) }; editorFields(result.fields) }
            result.reason == AccountFailure.VERSION_CONFLICT || result.reason == AccountFailure.NOT_FOUND -> {
                mutable.update { it.copy(busy = false, editor = null, action = null, failure = result.reason) }
                refresh()
            }
            else -> mutable.update { it.copy(busy = false, failure = result.reason) }
        }
    }
}
