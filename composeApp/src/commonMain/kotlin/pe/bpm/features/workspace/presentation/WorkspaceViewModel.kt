package pe.bpm.features.workspace.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result

class WorkspaceViewModel(
    private val loadOrganizations: LoadOrganizations,
    private val loadWorkspace: LoadWorkspace,
    private val createOrganization: CreateOrganization,
    private val createLocation: CreateLocation,
    private val addMember: AddMember,
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        mutable.value = WorkspaceState()
    }

    fun load() {
        if (mutable.value.saving) return
        job?.cancel()
        job = viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        mutable.value = mutable.value.copy(loading = true, errors = emptyList(), workspace = null)
        val result = loadOrganizations()
        // A cancelled request cannot repopulate a previous session or tenant.
        currentCoroutineContext().ensureActive()
        when (result) {
            is Result.Failure -> mutable.value = mutable.value.copy(loading = false, errors = result.errors)
            is Result.Success -> {
                val selected = mutable.value.selected?.takeIf { id -> result.value.any { it.id == id } }
                    ?: result.value.firstOrNull()?.id
                mutable.value = mutable.value.copy(organizations = result.value, selected = selected, loading = selected != null)
                if (selected != null) read(selected)
            }
        }
    }

    fun select(id: String) {
        if (mutable.value.saving || mutable.value.organizations.none { it.id == id }) return
        job?.cancel()
        mutable.value = mutable.value.copy(selected = id, workspace = null, loading = true, errors = emptyList(), notice = null)
        job = viewModelScope.launch { read(id) }
    }

    private suspend fun read(id: String) {
        val result = loadWorkspace(id)
        // A cancelled request cannot repopulate a previous session or tenant.
        currentCoroutineContext().ensureActive()
        when (result) {
            is Result.Success -> mutable.value = mutable.value.copy(workspace = result.value, loading = false)
            is Result.Failure -> mutable.value = mutable.value.copy(workspace = null, loading = false, errors = result.errors)
        }
    }

    fun clearErrors() { mutable.value = mutable.value.copy(errors = emptyList(), notice = null) }
    fun organization(input: Organization) = save("Organización creada. Agrega su primera sede.", input.id) { createOrganization(input) }

    fun location(input: Location) {
        val id = mutable.value.selected ?: return
        save("La sede ya está disponible.", id) { createLocation(id, input) }
    }

    fun member(email: String, role: String, locations: List<String>) {
        val id = mutable.value.selected ?: return
        save("Persona agregada al equipo.", id) { addMember(id, email, role, locations) }
    }

    private fun save(notice: String, selection: String, action: suspend () -> Result<Unit>) {
        if (mutable.value.saving || mutable.value.loading) return
        job?.cancel()
        mutable.value = mutable.value.copy(saving = true, errors = emptyList(), notice = null)
        job = viewModelScope.launch {
            val result = action()
            // A cancelled request cannot repopulate a previous session or tenant.
            currentCoroutineContext().ensureActive()
            when (result) {
                is Result.Failure -> mutable.value = mutable.value.copy(saving = false, errors = result.errors)
                is Result.Success -> {
                    mutable.value = mutable.value.copy(saving = false, notice = notice, selected = selection, revision = mutable.value.revision + 1)
                    reload()
                }
            }
        }
    }
}
