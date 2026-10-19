package pe.bpm.features.workspace.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.*
import pe.bpm.features.workspace.domain.*

internal data class WorkspaceFormState(val draft: WorkspaceFormDraft? = null, val loading: Boolean = false,
    val busy: Boolean = false, val savedRemote: Boolean = false, val recovered: Boolean = false, val localStatus: DraftSaveStatus = DraftSaveStatus.IDLE)

/** El formulario conserva estado mediante casos de uso; la vista no conoce almacenamiento ni JSON. */
internal class WorkspaceFormViewModel(private val drafts: ManageWorkspaceFormDraft, private val newId: () -> String) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceFormState())
    val state = mutable.asStateFlow()
    private var owner: DraftOwner? = null
    private var kind: WorkspaceFormKind? = null
    private var job: Job? = null
    private var afterCompletion: (() -> Unit)? = null
    private val draining = mutableListOf<Job>()
    private var autosave: DraftAutosave<WorkspaceFormDraft?>? = null
    fun reset() { afterCompletion = null; job?.cancel(); autosave?.finish()?.let { draining += it }; draining.removeAll { it.isCompleted }; autosave = null; owner = null; kind = null; mutable.value = WorkspaceFormState() }
    fun open(user: String, org: String, form: WorkspaceFormKind) {
        val context = DraftOwner(user, if (form == WorkspaceFormKind.Organization) "platform" else org)
        if (owner == context && kind == form && autosave != null) return
        reset(); owner = context; kind = form; mutable.value = WorkspaceFormState(loading = true)
        job = viewModelScope.launch {
            try {
                draining.toList().joinAll()
                val draft = drafts.read(context, form.name)
                currentCoroutineContext().ensureActive()
                var writer: DraftAutosave<WorkspaceFormDraft?>? = null
                writer = DraftAutosave(viewModelScope, { drafts.write(context, form.name, it) }) { status ->
                    if (autosave === writer) mutable.value = mutable.value.copy(localStatus = status)
                }
                autosave = writer
                mutable.value = WorkspaceFormState(draft ?: WorkspaceFormDraft(newId()), recovered = draft != null,
                    localStatus = if (draft != null) DraftSaveStatus.SAVED else DraftSaveStatus.IDLE)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutable.value = WorkspaceFormState(localStatus = DraftSaveStatus.FAILED) }
        }
    }
    fun edit(change: (WorkspaceFormDraft) -> WorkspaceFormDraft) {
        if (mutable.value.busy || mutable.value.savedRemote) return
        val current = mutable.value.draft ?: return
        val next = change(current).let { d -> d.copy(id = current.id, name = d.name.take(2000), trade = d.trade.take(2000),
            tax = d.tax.take(2000), contactName = d.contactName.take(2000), address = d.address.take(2000), district = d.district.take(2000),
            phone = d.phone.take(2000), email = d.email.take(2000), locations = d.locations.take(100).toSet()) }
        mutable.value = mutable.value.copy(draft = next)
        autosave?.schedule(next)
    }
    fun retry() {
        if (mutable.value.savedRemote) { afterCompletion?.let(::complete); return }
        val value = mutable.value.draft
        if (value != null) autosave?.schedule(value)
        else { val user = owner?.userId ?: return; val org = owner?.organizationId ?: return; kind?.let { open(user, org, it) } }
    }
    fun submit(send: (WorkspaceFormDraft) -> Unit) {
        val draft = mutable.value.draft ?: return
        if (mutable.value.busy || mutable.value.savedRemote) return
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            val ready = autosave?.flush(draft) == true
            currentCoroutineContext().ensureActive()
            mutable.value = mutable.value.copy(busy = false)
            if (ready) send(draft)
        }
    }
    fun complete(onCompleted: () -> Unit) {
        afterCompletion = onCompleted
        mutable.value = mutable.value.copy(savedRemote = true)
        clear(onCompleted)
    }
    fun discard(onDiscarded: () -> Unit) {
        if (mutable.value.busy) return
        clear(onDiscarded)
    }
    private fun clear(onCompleted: () -> Unit) {
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            if (autosave?.flush(null) == false) { mutable.value = mutable.value.copy(busy = false); return@launch }
            reset(); onCompleted()
        }
    }
}
