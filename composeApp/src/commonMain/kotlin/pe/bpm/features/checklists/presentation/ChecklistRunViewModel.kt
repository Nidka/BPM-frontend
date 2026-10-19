package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.DraftAutosave
import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.checklists.domain.*

data class ChecklistRunState(val userId: String? = null, val organizationId: String? = null,
    val run: ChecklistRun? = null, val inputs: Map<String, RunAnswerInput> = emptyMap(), val busy: Boolean = false,
    val failure: RunFailure? = null, val pending: Boolean = false, val reviewing: Boolean = false,
    val notice: String? = null, val dirty: Boolean = false, val localStatus: DraftSaveStatus = DraftSaveStatus.IDLE,
    val resumeId: String? = null, val conflicted: Boolean = false) {
    val editable: Boolean get() = run?.status == "DRAFT" && run.performedBy == userId && !busy && !pending && !conflicted && failure != RunFailure.CONFLICT
}

/** Separa copia local, autorización remota y confirmación de operaciones idempotentes. */
class ChecklistRunViewModel(private val load: LoadChecklistRun, private val start: StartChecklistRun,
    private val save: SaveChecklistRun, private val submit: SubmitChecklistRun,
    private val newId: () -> String, private val now: () -> String,
    private val drafts: ManageRunDraft? = null) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistRunState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var action: RunOperation? = null
    private var mayHaveReachedServer = false
    private var requestedId: String? = null
    private var recovery: RunDraft? = null
    private val draining = mutableListOf<Job>()
    private var autosave: DraftAutosave<RunDraft?>? = null
    private var opened = false
    private var recoveryBase: ChecklistRun? = null
    private var discardOnRead = false

    fun reset() {
        job?.cancel(); autosave?.finish()?.let { draining += it }; draining.removeAll { it.isCompleted }; autosave = null
        action = null; mayHaveReachedServer = false; recovery = null; recoveryBase = null; discardOnRead = false; requestedId = null; opened = false
        mutable.value = ChecklistRunState()
    }
    fun open(user: String, org: String, id: String? = null) {
        if (opened && mutable.value.userId == user && mutable.value.organizationId == org &&
            (id == null || id == mutable.value.run?.id || id == requestedId)) return
        reset(); opened = true; requestedId = id
        val owner = DraftOwner(user, org)
        mutable.value = ChecklistRunState(userId = user, organizationId = org, busy = true)
        job = viewModelScope.launch {
            try { draining.toList().joinAll(); recovery = drafts?.read(owner) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(busy = false, localStatus = DraftSaveStatus.FAILED,
                    notice = "No pudimos recuperar el borrador local. Reintenta antes de iniciar otro control.")
                return@launch
            }
            currentCoroutineContext().ensureActive()
            var writer: DraftAutosave<RunDraft?>? = null
            if (drafts != null) writer = DraftAutosave(viewModelScope, { drafts.write(owner, it) }) { status ->
                if (autosave === writer) mutable.value = mutable.value.copy(localStatus = status)
            }
            autosave = writer
            val cached = recovery
            val target = cached?.run?.id ?: (cached?.operation as? RunOperation.Start)?.input?.id ?: id
            requestedId = target
            if (target == null) mutable.value = mutable.value.copy(busy = false)
            else accept(load(org, target), reading = true)
        }
    }
    fun reload(id: String? = null) {
        if (mutable.value.busy || action != null) return
        if (autosave == null && drafts != null) {
            val user = mutable.value.userId ?: return; val org = mutable.value.organizationId ?: return
            val target = id ?: requestedId
            opened = false; open(user, org, target); return
        }
        val org = mutable.value.organizationId ?: return
        val runId = id ?: mutable.value.run?.id ?: requestedId ?: return
        // Un reintento de lectura no elimina la copia; el descarte solo se aplica tras una respuesta autorizada.
        if (mutable.value.conflicted) discardOnRead = true
        mutable.value = mutable.value.copy(busy = true, failure = null)
        job = viewModelScope.launch {
            accept(load(org, runId), reading = true)
        }
    }
    fun begin(locationId: String, versionId: String) {
        if (mutable.value.busy || mutable.value.run != null || action != null || drafts != null && autosave == null) return
        mayHaveReachedServer = false
        action = RunOperation.Start(StartChecklistInput(newId(), locationId, versionId, newId(), now()))
        retry()
    }
    fun edit(id: String, value: RunAnswerInput) {
        if (!mutable.value.editable || mutable.value.run?.items?.none { it.id == id } != false) return
        mutable.value = mutable.value.copy(inputs = mutable.value.inputs + (id to value.copy(value = value.value.take(2000), observation = value.observation.take(2000))),
            dirty = true, reviewing = false, notice = null, failure = null)
        protectDraft()
    }
    private fun snapshot(): RunDraft? = mutable.value.let { s ->
        if (s.dirty || action != null) RunDraft(if (s.conflicted) recoveryBase ?: s.run else s.run, s.inputs, action) else null
    }
    fun protectDraft() { autosave?.schedule(snapshot()) }
    fun save() {
        val state = mutable.value
        val run = state.run ?: return
        if (!state.editable) return
        val answers = answers(run, state.inputs)
        if (answers == null || !validRunAnswers(run.items, answers, complete = false)) {
            mutable.value = state.copy(failure = RunFailure.INVALID); return
        }
        mayHaveReachedServer = false
        action = RunOperation.Save(run, newId(), answers)
        retry()
    }
    fun review() {
        val current = mutable.value
        val run = current.run ?: return
        if (!current.editable || current.dirty) return
        mutable.value = if (validRunAnswers(run.items, run.answers, complete = true)) current.copy(reviewing = true, failure = null)
            else current.copy(failure = RunFailure.INVALID, notice = "Completa y guarda las preguntas obligatorias. Debe existir al menos una respuesta evaluable; la evidencia obligatoria requiere el módulo de archivos.")
    }
    fun backToEdit() { if (!mutable.value.busy && !mutable.value.pending) mutable.value = mutable.value.copy(reviewing = false) }
    fun confirmSubmit() {
        val current = mutable.value
        if (!current.editable || current.dirty || !current.reviewing) return
        mayHaveReachedServer = false
        action = RunOperation.Submit(current.run!!, newId()); retry()
    }
    fun retry() {
        val org = mutable.value.organizationId ?: return
        if (mutable.value.busy) return
        val pending = action ?: return
        mutable.value = mutable.value.copy(busy = true, pending = true, failure = null, notice = null)
        val checkpoint = snapshot()
        job = viewModelScope.launch {
            // No se envía nada hasta que cuerpo, clave y versión estén duraderos.
            if (autosave?.flush(checkpoint) == false) {
                if (!mayHaveReachedServer) action = null
                mutable.value = mutable.value.copy(busy = false, pending = action != null)
                return@launch
            }
            mayHaveReachedServer = true
            val result = when (pending) {
                is RunOperation.Start -> start(org, pending.input)
                is RunOperation.Save -> save(pending.run, pending.key, pending.answers)
                is RunOperation.Submit -> submit(pending.run, pending.key)
            }
            accept(result, reading = false)
        }
    }
    fun discard(onDiscarded: () -> Unit) {
        if (mutable.value.busy || action != null) return
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            if (autosave?.flush(null) == false) { mutable.value = mutable.value.copy(busy = false); return@launch }
            reset(); onDiscarded()
        }
    }
    fun leave(onLeft: () -> Unit) {
        val checkpoint = recovery ?: snapshot()
        job?.cancel()
        mutable.value = mutable.value.copy(busy = true)
        job = viewModelScope.launch {
            if (autosave?.flush(checkpoint) == false) { mutable.value = mutable.value.copy(busy = false); return@launch }
            reset(); onLeft()
        }
    }
    private suspend fun accept(result: RunResult, reading: Boolean) {
        currentCoroutineContext().ensureActive()
        val cached = recovery.takeUnless { discardOnRead }
        if (reading && cached?.operation is RunOperation.Start && result is RunResult.Failure && result.reason == RunFailure.NOT_FOUND) {
            action = cached.operation; mayHaveReachedServer = true; recovery = null
            mutable.value = mutable.value.copy(busy = false, pending = true, resumeId = requestedId,
                notice = "Hay un inicio de control pendiente. Reintenta para comprobarlo sin duplicarlo.")
            return
        }
        when (result) {
            is RunResult.Success -> {
                val run = result.run
                if (reading && cached != null) {
                    if (run.performedBy != mutable.value.userId || run.organizationId != mutable.value.organizationId) {
                        mutable.value = mutable.value.copy(busy = false, failure = RunFailure.DENIED); return
                    }
                    recovery = null
                    action = cached.operation
                    mayHaveReachedServer = action != null
                    if (action != null) {
                        mutable.value = mutable.value.copy(run = cached.run, inputs = cached.inputs, busy = false, pending = true,
                            dirty = cached.inputs.isNotEmpty(), resumeId = requestedId,
                            notice = "Recuperamos una operación pendiente. Reintenta para confirmar su resultado antes de continuar.")
                        return
                    }
                    recoveryBase = cached.run
                    val conflict = run.rowVersion != cached.run?.rowVersion || run.status != "DRAFT"
                    mutable.value = mutable.value.copy(run = run, inputs = cached.inputs, busy = false, dirty = true, resumeId = run.id,
                        failure = if (conflict) RunFailure.CONFLICT else null, conflicted = conflict,
                        localStatus = DraftSaveStatus.SAVED,
                        notice = if (conflict) "Conservamos tus respuestas, pero la versión guardada cambió. Revisa el conflicto antes de continuar."
                            else "Recuperamos tus respuestas sin enviar. Revísalas antes de guardarlas.")
                    return
                }
                action = null; mayHaveReachedServer = false; recovery = null; recoveryBase = null; discardOnRead = false
                mutable.value = mutable.value.copy(run = run, inputs = run.answers.associate { it.itemId to RunAnswerInput(
                    it.complianceResult.ifEmpty { it.numericValue?.toString() ?: it.textValue }, it.observation) },
                    busy = false, pending = false, failure = null, dirty = false, reviewing = false, resumeId = null, conflicted = false,
                    notice = if (reading) null else if (run.status == "SUBMITTED") "Control enviado correctamente." else "Borrador guardado en el servidor.")
                autosave?.flush(null)
            }
            is RunResult.Failure -> {
                val locked = result.reason in setOf(RunFailure.SESSION, RunFailure.DENIED)
                if (!locked && result.reason in setOf(RunFailure.NOT_FOUND, RunFailure.INVALID, RunFailure.CONFLICT)) action = null
                mutable.value = mutable.value.copy(busy = false, failure = if (mutable.value.conflicted && !locked) RunFailure.CONFLICT else result.reason,
                    conflicted = mutable.value.conflicted || result.reason == RunFailure.CONFLICT, pending = action != null,
                    run = if (locked) null else mutable.value.run, inputs = if (locked) emptyMap() else mutable.value.inputs)
                if (!reading && !locked) protectDraft()
            }
        }
    }
    private fun answers(run: ChecklistRun, inputs: Map<String, RunAnswerInput>): List<ChecklistAnswer>? {
        val result = mutableListOf<ChecklistAnswer>()
        for (item in run.items) {
            val input = inputs[item.id] ?: continue
            if (input.value.isBlank()) { if (input.observation.isNotBlank()) return null else continue }
            result += when (item.responseType) {
                ChecklistResponseType.COMPLIANCE -> ChecklistAnswer(item.id, complianceResult = input.value, observation = input.observation)
                ChecklistResponseType.TEXT -> ChecklistAnswer(item.id, textValue = input.value.trim(), observation = input.observation)
                else -> ChecklistAnswer(item.id, numericValue = input.value.replace(',', '.').toDoubleOrNull() ?: return null, observation = input.observation)
            }
        }
        return result
    }
}
