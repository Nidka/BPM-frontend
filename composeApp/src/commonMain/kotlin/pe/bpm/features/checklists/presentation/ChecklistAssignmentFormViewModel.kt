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

data class AssignmentFormState(
    val userId: String? = null,
    val organizationId: String? = null,
    val input: AssignmentInput? = null,
    val locationName: String = "",
    val loading: Boolean = false,
    val busy: Boolean = false,
    val pending: Boolean = false,
    val failure: AssignmentFailure? = null,
    val fields: Map<String, String> = emptyMap(),
    val localStatus: DraftSaveStatus = DraftSaveStatus.IDLE,
    val saved: ChecklistAssignment? = null,
) {
    val editable
        get() =
            input != null &&
                !loading &&
                !busy &&
                !pending &&
                saved == null &&
                failure !in setOf(AssignmentFailure.SESSION, AssignmentFailure.DENIED)
}

/**
 * La programación propuesta se hace duradera antes de HTTP y conserva su ID después de una
 * respuesta perdida.
 */
class ChecklistAssignmentFormViewModel(
    private val create: CreateChecklistAssignment,
    private val drafts: ManageAssignmentDraft,
    private val newId: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(AssignmentFormState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var autosave: DraftAutosave<AssignmentDraft?>? = null
    private val draining = mutableListOf<Job>()

    fun reset() {
        job?.cancel()
        autosave?.finish()?.let { draining += it }
        draining.removeAll { it.isCompleted }
        autosave = null
        mutable.value = AssignmentFormState()
    }

    fun open(user: String, org: String, template: String) {
        if (
            mutable.value.userId == user &&
                mutable.value.organizationId == org &&
                mutable.value.input?.templateId == template
        )
            return
        reset()
        mutable.value = AssignmentFormState(userId = user, organizationId = org, loading = true)
        job =
            viewModelScope.launch {
                val owner = DraftOwner(user, org)
                val draft =
                    try {
                        draining.toList().joinAll()
                        drafts.read(owner, template)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        mutable.value =
                            mutable.value.copy(
                                loading = false,
                                localStatus = DraftSaveStatus.FAILED,
                            )
                        return@launch
                    }
                currentCoroutineContext().ensureActive()
                var writer: DraftAutosave<AssignmentDraft?>? = null
                writer =
                    DraftAutosave(viewModelScope, { drafts.write(owner, template, it) }) { status ->
                        if (autosave === writer)
                            mutable.value = mutable.value.copy(localStatus = status)
                    }
                autosave = writer
                mutable.value =
                    mutable.value.copy(
                        loading = false,
                        input = draft?.input ?: AssignmentInput(newId(), template),
                        pending = draft?.pending == true,
                        locationName = draft?.locationName.orEmpty(),
                        localStatus =
                            if (draft != null) DraftSaveStatus.SAVED else DraftSaveStatus.IDLE,
                    )
            }
    }

    fun location(id: String, name: String) {
        if (!mutable.value.editable) return
        mutable.value = mutable.value.copy(locationName = name)
        edit { it.copy(locationId = id) }
    }

    fun frequency(value: ChecklistFrequency) = edit { it.copy(frequency = value) }

    fun dueTime(value: String) = edit { it.copy(dueTime = value.take(32)) }

    private fun edit(change: (AssignmentInput) -> AssignmentInput) {
        val s = mutable.value
        if (!s.editable) return
        mutable.value = s.copy(input = change(s.input!!), failure = null, fields = emptyMap())
        protect()
    }

    fun protect() {
        mutable.value.input?.let {
            autosave?.schedule(
                AssignmentDraft(it, mutable.value.pending, mutable.value.locationName)
            )
        }
    }

    fun save() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val input = s.input ?: return
        val writer = autosave ?: return
        if (
            s.busy ||
                s.saved != null ||
                s.failure in setOf(AssignmentFailure.SESSION, AssignmentFailure.DENIED)
        )
            return
        val fields = AssignmentInputRules.errors(input)
        if (fields.isNotEmpty()) {
            mutable.value = s.copy(fields = fields, failure = AssignmentFailure.INVALID)
            return
        }
        mutable.value = s.copy(busy = true, fields = emptyMap(), failure = null)
        job =
            viewModelScope.launch {
                if (!writer.flush(AssignmentDraft(input, true, s.locationName))) {
                    mutable.value = mutable.value.copy(busy = false)
                    return@launch
                }
                mutable.value = mutable.value.copy(pending = true)
                val result = create(org, input)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AssignmentResult.Success -> {
                        mutable.value =
                            mutable.value.copy(busy = false, pending = false, saved = result.value)
                        writer.flush(null)
                    }
                    is AssignmentResult.Failure -> {
                        val uncertain =
                            result.reason in
                                setOf(
                                    AssignmentFailure.CONNECTION,
                                    AssignmentFailure.UNAVAILABLE,
                                    AssignmentFailure.INVALID_RESPONSE,
                                    AssignmentFailure.RATE_LIMITED,
                                )
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                pending = uncertain,
                                failure = result.reason,
                                fields = result.fields,
                            )
                        writer.flush(AssignmentDraft(input, uncertain, s.locationName))
                    }
                }
            }
    }

    fun discard(onDone: () -> Unit) {
        if (mutable.value.busy || mutable.value.pending) return
        val writer = autosave ?: return
        mutable.value = mutable.value.copy(busy = true)
        job =
            viewModelScope.launch {
                if (writer.flush(null)) {
                    reset()
                    onDone()
                } else mutable.value = mutable.value.copy(busy = false)
            }
    }
}
