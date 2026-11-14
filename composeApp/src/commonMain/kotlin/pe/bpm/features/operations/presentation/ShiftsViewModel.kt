package pe.bpm.features.operations.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.operations.domain.*

/** Formulario de turno; `key` identifica el intento y se conserva si el resultado es incierto. */
data class ShiftEditor(
    val id: String,
    val editing: Shift?,
    val name: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val key: String,
    val uncertain: Boolean = false,
)

data class ShiftsState(
    val userId: String? = null,
    val organizationId: String? = null,
    val role: String = "",
    val siteId: String? = null,
    val shifts: List<Shift> = emptyList(),
    val showInactive: Boolean = false,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val failure: OperationsFailure? = null,
    val fields: Map<String, String> = emptyMap(),
    val editor: ShiftEditor? = null,
    val deactivating: Shift? = null,
    val deactivationKey: String = "",
    val notice: String? = null,
) {
    val canManage get() = ShiftAccess.canManage(role)
}

/** Lista, crea, edita y desactiva turnos de una sede; nunca toca transporte directamente. */
class ShiftsViewModel(private val manage: ManageShifts, private val newId: () -> String) :
    ViewModel() {
    private val mutable = MutableStateFlow(ShiftsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        mutable.value = ShiftsState()
    }

    fun open(userId: String, organizationId: String, role: String, siteId: String?) {
        val s = mutable.value
        if (
            s.userId == userId &&
                s.organizationId == organizationId &&
                s.role == role &&
                s.siteId == siteId
        )
            return
        reset()
        mutable.value = ShiftsState(userId, organizationId, role, siteId)
        refresh()
    }

    fun refresh() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val site = s.siteId ?: return
        job?.cancel()
        mutable.value = s.copy(loading = true, failure = null)
        job =
            viewModelScope.launch {
                val result = manage.list(org, site, s.role, s.showInactive && s.canManage)
                currentCoroutineContext().ensureActive()
                mutable.value =
                    when (result) {
                        is OperationsResult.Success ->
                            mutable.value.copy(loading = false, shifts = result.value)
                        is OperationsResult.Failure ->
                            mutable.value.copy(
                                loading = false,
                                shifts = emptyList(),
                                failure = result.reason,
                            )
                    }
            }
    }

    fun toggleInactive() {
        val s = mutable.value
        if (s.loading || s.busy || !s.canManage) return
        mutable.value = s.copy(showInactive = !s.showInactive)
        refresh()
    }

    fun newShift() {
        val s = mutable.value
        if (!s.canManage || s.busy || s.siteId == null) return
        mutable.value =
            s.copy(
                editor = ShiftEditor(newId(), null, key = newId()),
                fields = emptyMap(),
                failure = null,
                notice = null,
            )
    }

    fun edit(shift: Shift) {
        val s = mutable.value
        if (!s.canManage || s.busy || !shift.active) return
        mutable.value =
            s.copy(
                editor =
                    ShiftEditor(
                        shift.id,
                        shift,
                        shift.name,
                        shift.startTime,
                        shift.endTime,
                        newId(),
                    ),
                fields = emptyMap(),
                failure = null,
                notice = null,
            )
    }

    fun change(name: String? = null, start: String? = null, end: String? = null) {
        val s = mutable.value
        val e = s.editor ?: return
        if (s.busy || e.uncertain) return
        mutable.value =
            s.copy(
                editor =
                    e.copy(
                        name = name ?: e.name,
                        startTime = start ?: e.startTime,
                        endTime = end ?: e.endTime,
                    ),
                fields = s.fields - setOfNotNull(
                    "name".takeIf { name != null },
                    "startTime".takeIf { start != null },
                    "endTime".takeIf { end != null },
                ),
            )
    }

    fun cancelEdit() {
        val s = mutable.value
        if (!s.busy && s.editor?.uncertain != true) mutable.value = s.copy(editor = null, fields = emptyMap(), failure = null)
    }

    fun save() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val site = s.siteId ?: return
        val editor = s.editor ?: return
        if (s.busy) return
        mutable.value = s.copy(busy = true, failure = null, fields = emptyMap())
        job =
            viewModelScope.launch {
                val result =
                    if (editor.editing == null)
                        manage.create(
                            org,
                            s.role,
                            ShiftInput(editor.id, site, editor.name, editor.startTime, editor.endTime),
                            s.shifts.filter { it.active },
                            editor.key,
                        )
                    else
                        manage.update(
                            org,
                            s.role,
                            editor.id,
                            ShiftChange(editor.name, editor.startTime, editor.endTime, editor.editing.rowVersion),
                            s.shifts.filter { it.active },
                            editor.key,
                        )
                currentCoroutineContext().ensureActive()
                when (result) {
                    is OperationsResult.Success -> {
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                editor = null,
                                notice = "Turno guardado.",
                            )
                        refresh()
                    }
                    is OperationsResult.Failure -> {
                        val definitive = result.reason.definitive
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                failure = result.reason,
                                fields = result.fields,
                                // Un rechazo definitivo permite corregir con una operación nueva.
                                editor =
                                    mutable.value.editor?.copy(
                                        key = if (definitive) newId() else editor.key,
                                        uncertain = !definitive,
                                    ),
                            )
                        // Si otra persona cambió el turno, se recarga para mostrar la versión vigente.
                        if (result.reason == OperationsFailure.CONFLICT) refresh()
                    }
                }
            }
    }

    fun requestDeactivate(shift: Shift) {
        val s = mutable.value
        if (!s.canManage || s.busy || !shift.active) return
        mutable.value = s.copy(deactivating = shift, deactivationKey = newId(), failure = null, notice = null)
    }

    fun cancelDeactivate() {
        val s = mutable.value
        if (!s.busy) mutable.value = s.copy(deactivating = null)
    }

    fun confirmDeactivate() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val shift = s.deactivating ?: return
        if (s.busy) return
        mutable.value = s.copy(busy = true, failure = null)
        job =
            viewModelScope.launch {
                val result = manage.deactivate(org, s.role, shift, s.deactivationKey)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is OperationsResult.Success -> {
                        mutable.value =
                            mutable.value.copy(busy = false, deactivating = null, notice = "Turno desactivado.")
                        refresh()
                    }
                    is OperationsResult.Failure -> {
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                failure = result.reason,
                                // Con un resultado incierto se conserva la clave para repetir la misma operación.
                                deactivating = if (result.reason.definitive) null else shift,
                            )
                        if (result.reason == OperationsFailure.CONFLICT) refresh()
                    }
                }
            }
    }
}
