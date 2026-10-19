package pe.bpm.features.workspace.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import pe.bpm.core.domain.PageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.domain.Result
import pe.bpm.core.presentation.DataFreshness

class WorkspaceViewModel(
    private val loadOrganizations: LoadOrganizations,
    private val loadWorkspace: LoadWorkspace,
    private val createOrganization: CreateOrganization,
    private val createLocation: CreateLocation,
    private val addMember: AddMember,
    private val loadPage: LoadWorkspacePage,
    private val freshness: DataFreshness = DataFreshness(),
) : ViewModel() {
    private val mutable = MutableStateFlow(WorkspaceState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var organizationJob: Job? = null
    private val pageJobs = mutableMapOf<WorkspaceResource, Job>()

    private fun cancelPages() {
        organizationJob?.cancel(); pageJobs.values.forEach { it.cancel() }; pageJobs.clear()
        mutable.value = mutable.value.copy(organizationPage = mutable.value.organizationPage.copy(loading = false),
            pages = mutable.value.pages.mapValues { it.value.copy(loading = false) })
    }

    fun reset() {
        freshness.reset()
        job?.cancel()
        cancelPages()
        mutable.value = WorkspaceState()
    }

    fun load() {
        if (mutable.value.saving) return
        job?.cancel()
        cancelPages()
        job = viewModelScope.launch { reload() }
    }

    fun refreshIfStale() {
        val current = mutable.value
        if (freshness.stale && !current.loading && !current.saving &&
            current.errors.isEmpty() && current.pages.values.none { it.loading }) load()
    }

    private suspend fun reload() {
        mutable.value = mutable.value.copy(loading = true, errors = emptyList(), workspace = null, organizations = emptyList(), pages = emptyMap())
        val result = loadOrganizations()
        // Una solicitud cancelada no puede restaurar una sesión o empresa anterior.
        currentCoroutineContext().ensureActive()
        when (result) {
            is Result.Failure -> mutable.value = mutable.value.copy(loading = false, errors = result.errors)
            is Result.Success -> {
                freshness.loaded()
                val selected = mutable.value.selected ?: result.value.items.firstOrNull()?.id
                mutable.value = mutable.value.copy(organizations = result.value.items, canChooseOrganization = result.value.items.size > 1 || result.value.nextToken.isNotEmpty(), organizationPage = WorkspacePageState(nextToken = result.value.nextToken), selected = selected, loading = selected != null)
                if (selected != null) read(selected)
            }
        }
    }

    fun select(id: String) {
        if (mutable.value.saving || mutable.value.organizations.none { it.id == id }) return
        job?.cancel()
        cancelPages()
        mutable.value = mutable.value.copy(pages = emptyMap(), selected = id, workspace = null, loading = true, errors = emptyList(), notice = null)
        job = viewModelScope.launch { read(id) }
    }

    private suspend fun read(id: String) {
        val result = loadWorkspace(id)
        // Una solicitud cancelada no puede restaurar una sesión o empresa anterior.
        currentCoroutineContext().ensureActive()
        when (result) {
            is Result.Success -> {
                freshness.loaded()
                mutable.value = mutable.value.copy(workspace = result.value, loading = false, pages = WorkspaceResource.entries.associateWith { WorkspacePageState(nextToken = result.value.nextPageTokens[it.path].orEmpty()) })
            }
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
        cancelPages()
        mutable.value = mutable.value.copy(saving = true, errors = emptyList(), notice = null)
        job = viewModelScope.launch {
            val result = action()
            // Una solicitud cancelada no puede restaurar una sesión o empresa anterior.
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
    fun organizationRetry() {
        val p = mutable.value.organizationPage
        organizationsPage(if (p.errors.any { it.code == "INVALID_PAGINATION" }) WorkspacePageState(request = p.request.copy(token = "")) else p)
    }
    fun organizationSearch(query: String) = organizationsPage(WorkspacePageState(request = PageRequest(query = query.take(160))), debounce = true)
    fun organizationNext() {
        val p = mutable.value.organizationPage
        if (!p.loading && p.nextToken.isNotEmpty()) organizationsPage(p.copy(request = p.request.copy(token = p.nextToken), previous = p.previous + p.request.token))
    }
    fun organizationPrevious() {
        val p = mutable.value.organizationPage
        if (!p.loading && p.previous.isNotEmpty()) organizationsPage(p.copy(request = p.request.copy(token = p.previous.last()), previous = p.previous.dropLast(1)))
    }
    private fun organizationsPage(page: WorkspacePageState, debounce: Boolean = false) {
        if (mutable.value.saving || mutable.value.loading) return
        organizationJob?.cancel()
        mutable.value = mutable.value.copy(organizationPage = page.copy(loading = true, errors = emptyList()), organizations = emptyList())
        organizationJob = viewModelScope.launch {
            if (debounce) delay(300)
            val result = loadOrganizations(page.request)
            currentCoroutineContext().ensureActive()
            mutable.value = when (result) {
                is Result.Success -> mutable.value.copy(organizations = result.value.items, canChooseOrganization = result.value.items.size > 1 || result.value.nextToken.isNotEmpty(), organizationPage = page.copy(loading = false, nextToken = result.value.nextToken, errors = emptyList()))
                is Result.Failure -> if (result.errors.any { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") })
                    mutable.value.copy(workspace = null, organizations = emptyList(), pages = emptyMap(), errors = result.errors, organizationPage = page.copy(loading = false, errors = result.errors))
                    else mutable.value.copy(organizationPage = page.copy(loading = false, errors = result.errors))
            }
        }
    }
    fun pageSearch(resource: WorkspaceResource, query: String) = fetchPage(resource, WorkspacePageState(request = PageRequest(query = query.take(160))), debounce = true)
    fun pageNext(resource: WorkspaceResource) {
        val p = mutable.value.pages[resource] ?: return
        if (!p.loading && p.nextToken.isNotEmpty()) fetchPage(resource, p.copy(request = p.request.copy(token = p.nextToken), previous = p.previous + p.request.token))
    }
    fun pagePrevious(resource: WorkspaceResource) {
        val p = mutable.value.pages[resource] ?: return
        if (!p.loading && p.previous.isNotEmpty()) fetchPage(resource, p.copy(request = p.request.copy(token = p.previous.last()), previous = p.previous.dropLast(1)))
    }
    fun pageRetry(resource: WorkspaceResource) {
        val p = mutable.value.pages[resource] ?: return
        fetchPage(resource, if (p.errors.any { it.code == "INVALID_PAGINATION" }) WorkspacePageState(request = p.request.copy(token = "")) else p)
    }
    private fun fetchPage(resource: WorkspaceResource, page: WorkspacePageState, debounce: Boolean = false) {
        val org = mutable.value.workspace?.organization?.id ?: return
        if (mutable.value.saving || mutable.value.loading) return
        pageJobs[resource]?.cancel()
        mutable.value = mutable.value.copy(pages = mutable.value.pages + (resource to page.copy(loading = true, errors = emptyList())))
        pageJobs[resource] = viewModelScope.launch {
            if (debounce) delay(300)
            val result = loadPage(org, resource, page.request)
            currentCoroutineContext().ensureActive()
            val current = mutable.value.workspace?.takeIf { it.organization.id == org } ?: return@launch
            when (result) {
                is Result.Success -> {
                    val (updated, next) = when (val items = result.value) {
                        is WorkspaceItems.Locations -> current.copy(locations = items.page.items) to items.page.nextToken
                        is WorkspaceItems.Members -> current.copy(members = items.page.items) to items.page.nextToken
                        is WorkspaceItems.Entries -> (if (resource == WorkspaceResource.Courses) current.copy(courses = items.page.items) else current.copy(incidents = items.page.items)) to items.page.nextToken
                    }
                    mutable.value = mutable.value.copy(workspace = updated, pages = mutable.value.pages + (resource to page.copy(loading = false, nextToken = next, errors = emptyList())))
                }
                is Result.Failure -> {
                    if (result.errors.any { it.code in setOf("ACCESS_DENIED", "UNAUTHENTICATED") }) {
                        cancelPages()
                        mutable.value = mutable.value.copy(workspace = null, pages = emptyMap(), errors = result.errors)
                    } else mutable.value = mutable.value.copy(pages = mutable.value.pages + (resource to page.copy(loading = false, errors = result.errors)))
                }
            }
        }
    }

}
