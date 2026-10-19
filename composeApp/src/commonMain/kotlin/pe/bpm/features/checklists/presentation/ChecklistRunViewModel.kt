package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.checklists.domain.*

data class RunAnswerInput(val value: String = "", val observation: String = "")
data class ChecklistRunState(val userId: String? = null, val organizationId: String? = null,
    val run: ChecklistRun? = null, val inputs: Map<String, RunAnswerInput> = emptyMap(), val busy: Boolean = false,
    val failure: RunFailure? = null, val pending: Boolean = false, val reviewing: Boolean = false,
    val notice: String? = null, val dirty: Boolean = false) {
    val editable: Boolean get() = run?.status == "DRAFT" && run.performedBy == userId && !busy && !pending && failure != RunFailure.CONFLICT
}

/** Conserva cuerpo, clave y versión durante cada reintento; cancela al cambiar de identidad o empresa. */
class ChecklistRunViewModel(private val load: LoadChecklistRun, private val start: StartChecklistRun,
    private val save: SaveChecklistRun, private val submit: SubmitChecklistRun,
    private val newId: () -> String, private val now: () -> String) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistRunState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var action: Action? = null
    private var requestedId: String? = null
    private sealed interface Action {
        data class Start(val input: StartChecklistInput) : Action
        data class Save(val run: ChecklistRun, val key: String, val answers: List<ChecklistAnswer>) : Action
        data class Submit(val run: ChecklistRun, val key: String) : Action
    }
    fun reset() { job?.cancel(); action = null; requestedId = null; mutable.value = ChecklistRunState() }
    fun open(user: String, org: String, id: String? = null) {
        if (mutable.value.userId != user || mutable.value.organizationId != org) {
            reset(); mutable.value = ChecklistRunState(userId = user, organizationId = org)
        }
        if (id != null && id != mutable.value.run?.id && id != requestedId) {
            job?.cancel(); action = null; requestedId = id
            mutable.value = ChecklistRunState(userId = user, organizationId = org)
            reload(id)
        }
    }
    fun reload(id: String? = null) {
        if (mutable.value.busy || action != null) return
        val org = mutable.value.organizationId ?: return
        val runId = id ?: mutable.value.run?.id ?: requestedId ?: return
        mutable.value = mutable.value.copy(busy = true, failure = null)
        job = viewModelScope.launch { accept(load(org, runId), reading = true) }
    }
    fun begin(locationId: String, versionId: String) {
        if (mutable.value.busy || mutable.value.run != null || action != null) return
        action = Action.Start(StartChecklistInput(newId(), locationId, versionId, newId(), now()))
        retry()
    }
    fun edit(id: String, value: RunAnswerInput) {
        if (!mutable.value.editable || mutable.value.run?.items?.none { it.id == id } != false) return
        mutable.value = mutable.value.copy(inputs = mutable.value.inputs + (id to value.copy(value = value.value.take(2000), observation = value.observation.take(2000))),
            dirty = true, reviewing = false, notice = null, failure = null)
    }
    fun save() {
        val state = mutable.value
        val run = state.run ?: return
        if (!state.editable) return
        val answers = answers(run, state.inputs)
        if (answers == null || !validRunAnswers(run.items, answers, complete = false)) {
            mutable.value = state.copy(failure = RunFailure.INVALID); return
        }
        action = Action.Save(run, newId(), answers)
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
        action = Action.Submit(current.run!!, newId()); retry()
    }
    fun retry() {
        val org = mutable.value.organizationId ?: return
        if (mutable.value.busy) return
        val pending = action ?: return
        mutable.value = mutable.value.copy(busy = true, pending = true, failure = null, notice = null)
        job = viewModelScope.launch {
            val result = when (pending) {
                is Action.Start -> start(org, pending.input)
                is Action.Save -> save(pending.run, pending.key, pending.answers)
                is Action.Submit -> submit(pending.run, pending.key)
            }
            accept(result, reading = false)
        }
    }
    private suspend fun accept(result: RunResult, reading: Boolean) {
        currentCoroutineContext().ensureActive()
        when (result) {
            is RunResult.Success -> {
                action = null
                val run = result.run
                mutable.value = mutable.value.copy(run = run, inputs = run.answers.associate { it.itemId to RunAnswerInput(
                    it.complianceResult.ifEmpty { it.numericValue?.toString() ?: it.textValue }, it.observation) },
                    busy = false, pending = false, failure = null, dirty = false, reviewing = false,
                    notice = if (reading) null else if (run.status == "SUBMITTED") "Control enviado correctamente." else "Borrador guardado.")
            }
            is RunResult.Failure -> {
                val terminal = result.reason in setOf(RunFailure.SESSION, RunFailure.DENIED, RunFailure.NOT_FOUND, RunFailure.INVALID, RunFailure.CONFLICT)
                if (terminal) action = null
                val denied = result.reason in setOf(RunFailure.SESSION, RunFailure.DENIED)
                mutable.value = mutable.value.copy(busy = false, failure = result.reason, pending = action != null,
                    run = if (denied) null else mutable.value.run, inputs = if (denied) emptyMap() else mutable.value.inputs)
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
