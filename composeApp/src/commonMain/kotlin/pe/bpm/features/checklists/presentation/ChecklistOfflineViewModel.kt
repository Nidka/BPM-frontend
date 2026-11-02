package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.DraftAutosave
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.checklists.domain.*

data class ChecklistOfflineState(
    val owner: DraftOwner? = null, val entries: List<OfflineChecklist> = emptyList(),
    val selectedId: String? = null, val busy: Boolean = false, val syncing: Boolean = false,
    val localStatus: DraftSaveStatus = DraftSaveStatus.IDLE, val failure: RunFailure? = null,
    val notice: String? = null, val reviewing: Boolean = false, val preparedId: String? = null, val localConflict: Boolean = false,
) {
    val selected get() = entries.find { it.run.id == selectedId }
    val pendingCount get() = entries.count { it.upload != null }
}

/** Coordina edición protegida y envíos de primer plano; nunca crea sesiones sin conexión. */
class ChecklistOfflineViewModel(
    private val local: ManageOfflineChecklists,
    private val synchronize: SyncOfflineChecklists,
) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistOfflineState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var syncJob: Job? = null
    private var writer: DraftAutosave<Map<String, RunAnswerInput>>? = null
    private val draining = mutableListOf<Job>()
    private val authorized = mutableSetOf<String>()
    private var generation = 0L

    fun reset() {
        generation++
        job?.cancel(); syncJob?.cancel()
        writer?.finish()?.let { draining += it }; writer = null
        draining.removeAll { it.isCompleted }; authorized.clear()
        mutable.value = ChecklistOfflineState()
    }

    fun open(user: String, org: String) {
        val owner = DraftOwner(user, org)
        if (mutable.value.owner == owner) return
        reset(); mutable.value = ChecklistOfflineState(owner = owner, busy = true)
        job = viewModelScope.launch {
            try {
                draining.toList().joinAll()
                var failure: RunFailure? = null
                val entries = local.list(owner)
                for (entry in entries) {
                    when (val result = local.authorize(owner, entry)) {
                        is RunResult.Success -> authorized += entry.run.id
                        is RunResult.Failure -> {
                            failure = result.reason
                            if (result.reason == RunFailure.SESSION) { authorized.clear(); break }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                mutable.value = mutable.value.copy(entries = entries.filter { it.run.id in authorized }, busy = false, failure = failure)
                if (failure != RunFailure.SESSION) sync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localFailure() }
        }
    }

    fun consumePrepared() { mutable.value = mutable.value.copy(preparedId = null) }

    fun retryOpen() {
        val owner = mutable.value.owner ?: return
        reset(); open(owner.userId, owner.organizationId)
    }

    fun prepare(id: String) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, failure = null, notice = null, preparedId = null)
        job = viewModelScope.launch {
            try {
                when (val result = local.prepare(owner, id)) {
                    is RunResult.Failure -> mutable.value = mutable.value.copy(busy = false, failure = result.reason)
                    is RunResult.Success -> {
                        currentCoroutineContext().ensureActive(); authorized += id
                        refresh(); mutable.value = mutable.value.copy(busy = false, preparedId = id)
                        select(id)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localFailure() }
        }
    }

    fun select(id: String?) {
        if (mutable.value.localConflict) return
        if (id == mutable.value.selectedId && (id == null || writer != null)) return
        if (id != null && mutable.value.entries.none { it.run.id == id }) return
        writer?.finish()?.let { draining += it }; writer = null
        mutable.value = mutable.value.copy(selectedId = id, reviewing = false, localStatus = DraftSaveStatus.IDLE, notice = null)
        val owner = mutable.value.owner ?: return
        if (id == null) return
        var next: DraftAutosave<Map<String, RunAnswerInput>>? = null
        var baseline = mutable.value.selected?.inputs.orEmpty()
        next = DraftAutosave(viewModelScope, { inputs ->
            try { local.replaceInputs(owner, id, inputs, baseline); baseline = inputs }
            catch (conflict: OfflineInputConflict) {
                if (writer === next) mutable.value = mutable.value.copy(localConflict = true, reviewing = false)
                throw conflict
            }
        }) { status ->
            if (writer === next) mutable.value = mutable.value.copy(localStatus = status)
        }
        writer = next
    }

    fun edit(item: String, input: RunAnswerInput) {
        val entry = mutable.value.selected ?: return
        if (!entry.editable || mutable.value.busy || mutable.value.localStatus == DraftSaveStatus.FAILED || entry.run.items.none { it.id == item }) return
        val inputs = entry.inputs + (item to input.copy(value = input.value.take(2000), observation = input.observation.take(2000)))
        mutable.value = mutable.value.copy(entries = mutable.value.entries.map {
            if (it.run.id == entry.run.id) it.copy(inputs = inputs, status = OfflineRunStatus.LOCAL) else it
        }, reviewing = false, notice = null)
        writer?.schedule(inputs)
    }

    fun protect() {
        val owner = mutable.value.owner ?: return
        val entry = mutable.value.selected ?: return
        if (mutable.value.busy || mutable.value.localConflict) return
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            try {
                val stored = local.list(owner).find { it.run.id == entry.run.id }
                if (stored?.upload != null) {
                    refresh(); mutable.value = mutable.value.copy(busy = false, localStatus = DraftSaveStatus.SAVED)
                    sync()
                } else {
                    if (writer == null) select(entry.run.id)
                    writer?.flush(entry.inputs)
                    mutable.value = mutable.value.copy(busy = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localFailure() }
        }
    }

    /** Conserva los recuentos confirmados sin tocar el borrador ni una operación en cola. */
    fun evidenceCount(user: String, org: String, runId: String, itemId: String, count: Int) {
        val current = mutable.value
        val owner = current.owner ?: return
        val entry = current.entries.find { it.run.id == runId } ?: return
        if (owner.userId != user || owner.organizationId != org || !entry.editable || count !in 0..3 ||
            entry.run.items.none { it.id == itemId }) return
        val context = generation
        mutable.value = current.copy(entries = current.entries.map {
            if (it.run.id == runId) it.copy(run = it.run.copy(evidenceCounts = it.run.evidenceCounts + (itemId to count))) else it
        })
        viewModelScope.launch {
            try { local.evidenceCount(owner, runId, itemId, count) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == context) localFailure() }
        }
    }

    fun review() {
        val entry = mutable.value.selected ?: return
        val answers = checklistAnswers(entry.run, entry.inputs)
        if (!entry.editable || mutable.value.busy) return
        mutable.value = if (answers != null && validRunAnswers(entry.run.items, answers, complete = true, evidenceCounts = entry.run.evidenceCounts))
            mutable.value.copy(reviewing = true, failure = null)
        else mutable.value.copy(failure = RunFailure.INVALID)
    }

    fun backToEdit() { mutable.value = mutable.value.copy(reviewing = false) }

    fun enqueue(finalize: Boolean) {
        val owner = mutable.value.owner ?: return
        val entry = mutable.value.selected ?: return
        if (!entry.editable || mutable.value.busy || mutable.value.localConflict || finalize && !mutable.value.reviewing) return
        val answers = checklistAnswers(entry.run, entry.inputs)
        if (answers == null || !validRunAnswers(entry.run.items, answers, complete = finalize, evidenceCounts = entry.run.evidenceCounts)) {
            mutable.value = mutable.value.copy(failure = RunFailure.INVALID); return
        }
        mutable.value = mutable.value.copy(busy = true, failure = null)
        job = viewModelScope.launch {
            try {
                if (writer?.flush(entry.inputs) == false) { mutable.value = mutable.value.copy(busy = false); return@launch }
                val entries = local.enqueue(owner, entry.run.id, finalize, entry.inputs)
                mutable.value = mutable.value.copy(entries = entries.filter { it.run.id in authorized }, busy = false, reviewing = false, notice = "Pendiente de enviar. Conservamos tus respuestas en este dispositivo.")
                sync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OfflineInputConflict) { mutable.value = mutable.value.copy(busy = false, localConflict = true, reviewing = false) }
            catch (_: Exception) { localFailure() }
        }
    }

    /** Tres intentos como máximo por tanda. Sin red, la cola queda conservada hasta reintentar. */
    fun sync(manual: Boolean = false) {
        val owner = mutable.value.owner ?: return
        if (syncJob?.isActive == true || mutable.value.busy) return
        val contextGeneration = generation
        syncJob = viewModelScope.launch {
            try {
                if (manual) local.retry(owner)
                repeat(3) { attempt ->
                    if (attempt > 0) delay(if (attempt == 1) 5_000 else 20_000)
                    val pending = local.list(owner).any { it.run.id in authorized && it.status in setOf(OfflineRunStatus.PENDING, OfflineRunStatus.SENDING) && it.attempts < 3 }
                    if (!pending) return@launch
                    mutable.value = mutable.value.copy(syncing = true)
                    val failure = synchronize(owner, authorized.toSet())
                    currentCoroutineContext().ensureActive()
                    refresh()
                    mutable.value = mutable.value.copy(syncing = false)
                    if (failure == RunFailure.SESSION) {
                        authorized.clear()
                        mutable.value = mutable.value.copy(entries = emptyList(), selectedId = null, failure = failure)
                        return@launch
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localFailure() }
            finally { if (generation == contextGeneration && mutable.value.owner == owner) mutable.value = mutable.value.copy(syncing = false) }
        }
    }

    fun remove(id: String) {
        val owner = mutable.value.owner ?: return
        if (mutable.value.busy || mutable.value.entries.find { it.run.id == id }?.upload != null) return
        writer?.finish()?.let { draining += it }; writer = null
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            try {
                draining.toList().joinAll(); local.remove(owner, id); authorized -= id; refresh()
                mutable.value = mutable.value.copy(busy = false, selectedId = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localFailure() }
        }
    }

    fun useServerVersion() {
        val owner = mutable.value.owner ?: return
        val id = mutable.value.selectedId ?: return
        if (mutable.value.busy) return
        val localConflict = mutable.value.localConflict
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            try {
                if (localConflict) {
                    writer?.finish()?.let { draining += it }; writer = null
                    draining.toList().joinAll()
                    val entries = local.list(owner)
                    currentCoroutineContext().ensureActive()
                    mutable.value = mutable.value.copy(entries = entries.filter { it.run.id in authorized }, busy = false,
                        localConflict = false, selectedId = null, localStatus = DraftSaveStatus.IDLE, failure = null)
                    select(id)
                    return@launch
                }
                when (val result = local.useServerVersion(owner, id)) {
                    is RunResult.Success -> { refresh(); mutable.value = mutable.value.copy(busy = false, failure = null) }
                    is RunResult.Failure -> mutable.value = mutable.value.copy(busy = false, failure = result.reason)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localFailure() }
        }
    }

    private suspend fun refresh() {
        val owner = mutable.value.owner ?: return
        draining.toList().joinAll(); draining.removeAll { it.isCompleted }
        val entries = local.list(owner)
        currentCoroutineContext().ensureActive()
        val selected = mutable.value.selected
        mutable.value = mutable.value.copy(entries = entries.filter {
            it.run.id in authorized && it.failure !in setOf(RunFailure.DENIED, RunFailure.NOT_FOUND)
        }.map { if (it.run.id == selected?.run?.id && it.upload == null && selected.editable &&
                mutable.value.localStatus in setOf(DraftSaveStatus.SAVING, DraftSaveStatus.FAILED)) it.copy(inputs = selected.inputs) else it })
    }

    private fun localFailure() {
        mutable.value = mutable.value.copy(busy = false, localStatus = DraftSaveStatus.FAILED,
            notice = "No pudimos conservar los cambios. Reintenta; no borres los datos de la aplicación.")
    }
}
