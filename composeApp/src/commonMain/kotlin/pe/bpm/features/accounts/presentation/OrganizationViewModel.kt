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

/** Copia editable; conserva el `rowVersion` leído dentro de [value]. */
data class OrganizationForm(val value: AccountOrganization, val fields: Map<String, String> = emptyMap())
data class LocationForm(val value: AccountLocation, val fields: Map<String, String> = emptyMap())

data class OrganizationState(
    val organizationId: String? = null,
    val allowed: Boolean = false,
    val loading: Boolean = false,
    val organization: AccountOrganization? = null,
    val organizationForm: OrganizationForm? = null,
    val locationStatus: RecordStatus = RecordStatus.ACTIVE,
    val locations: AccountPage<AccountLocation> = AccountPage(),
    val locationForm: LocationForm? = null,
    val busy: Boolean = false,
    val failure: AccountFailure? = null,
    val notice: String? = null,
)

/** Datos de la organización (incluido el tipo de establecimiento) y de cada sede, con filtro ACTIVE/INACTIVE. */
class OrganizationViewModel(
    private val loadOrganization: LoadOrganizationAccount,
    private val updateOrganization: UpdateOrganizationAccount,
    private val listLocations: ListAccountLocations,
    private val updateLocation: UpdateAccountLocation,
) : ViewModel() {
    private val mutable = MutableStateFlow(OrganizationState())
    val state = mutable.asStateFlow()
    private var listJob: Job? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null

    fun open(organizationId: String, allowed: Boolean) {
        reset()
        mutable.value = OrganizationState(organizationId = organizationId, allowed = allowed, loading = allowed)
        if (allowed) { readOrganization(organizationId); fetch(mutable.value.locations, RecordStatus.ACTIVE) }
    }

    fun reset() { listJob?.cancel(); loadJob?.cancel(); saveJob?.cancel(); mutable.value = OrganizationState() }
    fun clearMessages() = mutable.update { it.copy(failure = null, notice = null) }
    fun refresh() {
        val s = mutable.value
        val organization = s.organizationId ?: return
        if (!s.allowed) return
        readOrganization(organization); fetch(s.locations.restart(), s.locationStatus)
    }

    private fun readOrganization(organization: String) {
        loadJob?.cancel()
        mutable.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            val result = loadOrganization(organization)
            currentCoroutineContext().ensureActive()
            if (mutable.value.organizationId != organization) return@launch
            mutable.update {
                when (result) {
                    is AccountsResult.Success -> it.copy(loading = false, organization = result.value, failure = null)
                    is AccountsResult.Failure -> it.copy(loading = false, failure = result.reason)
                }
            }
        }
    }

    // ---- Organización ------------------------------------------------------------------------

    fun editOrganization() { val s = mutable.value; if (!s.busy) s.organization?.let { o -> mutable.update { it.copy(organizationForm = OrganizationForm(o), locationForm = null, failure = null, notice = null) } } }
    fun cancelOrganization() { if (!mutable.value.busy) mutable.update { it.copy(organizationForm = null) } }
    fun changeOrganization(change: (AccountOrganization) -> AccountOrganization) = mutable.update { s ->
        s.organizationForm?.let { s.copy(organizationForm = it.copy(value = change(it.value), fields = emptyMap())) } ?: s
    }
    fun saveOrganization() {
        val s = mutable.value
        val form = s.organizationForm ?: return
        val organization = s.organizationId ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, failure = null, notice = null) }
        saveJob = viewModelScope.launch {
            val result = updateOrganization(organization, form.value)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AccountsResult.Success -> mutable.update { it.copy(busy = false, organization = result.value, organizationForm = null, notice = "Datos de la organización guardados.") }
                is AccountsResult.Failure -> failed(result) { f -> mutable.update { it.copy(organizationForm = it.organizationForm?.copy(fields = f)) } }
            }
        }
    }

    // ---- Sedes -------------------------------------------------------------------------------

    fun selectLocationStatus(status: RecordStatus) {
        val s = mutable.value
        if (!s.allowed || s.busy || status == s.locationStatus) return
        mutable.update { it.copy(locationStatus = status, notice = null) }
        fetch(s.locations.search(s.locations.request.query), status)
    }
    fun searchLocations(query: String) { val s = mutable.value; if (s.allowed) fetch(s.locations.search(query), s.locationStatus, debounce = true) }
    fun nextLocations() { val s = mutable.value; s.locations.forward()?.let { fetch(it, s.locationStatus) } }
    fun previousLocations() { val s = mutable.value; s.locations.backward()?.let { fetch(it, s.locationStatus) } }
    fun retryLocations() { val s = mutable.value; fetch(s.locations.retry(), s.locationStatus) }

    private fun fetch(page: AccountPage<AccountLocation>, status: RecordStatus, debounce: Boolean = false) {
        val organization = mutable.value.organizationId ?: return
        listJob?.cancel()
        mutable.update { it.copy(locations = page.copy(loading = true, failure = null)) }
        listJob = viewModelScope.launch {
            if (debounce) delay(300)
            val result = listLocations(organization, status, page.request)
            currentCoroutineContext().ensureActive()
            if (mutable.value.organizationId != organization || mutable.value.locationStatus != status) return@launch
            mutable.update { s ->
                when (result) {
                    is AccountsResult.Success -> s.copy(locations = page.copy(items = result.value.items, nextToken = result.value.nextToken, loading = false, failure = null))
                    is AccountsResult.Failure -> s.copy(locations = page.copy(items = emptyList(), nextToken = "", loading = false, failure = result.reason))
                }
            }
        }
    }

    fun editLocation(location: AccountLocation) { if (!mutable.value.busy) mutable.update { it.copy(locationForm = LocationForm(location), organizationForm = null, failure = null, notice = null) } }
    fun cancelLocation() { if (!mutable.value.busy) mutable.update { it.copy(locationForm = null) } }
    fun changeLocation(change: (AccountLocation) -> AccountLocation) = mutable.update { s ->
        s.locationForm?.let { s.copy(locationForm = it.copy(value = change(it.value), fields = emptyMap())) } ?: s
    }
    fun saveLocation() {
        val s = mutable.value
        val form = s.locationForm ?: return
        val organization = s.organizationId ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, failure = null, notice = null) }
        saveJob = viewModelScope.launch {
            val result = updateLocation(organization, form.value)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AccountsResult.Success -> {
                    mutable.update { it.copy(busy = false, locationForm = null, notice = "Sede guardada.") }
                    // Cambiar el estado la saca de este filtro; se vuelve a leer la página vigente.
                    fetch(mutable.value.locations.restart(), mutable.value.locationStatus)
                }
                is AccountsResult.Failure -> failed(result) { f -> mutable.update { it.copy(locationForm = it.locationForm?.copy(fields = f)) } }
            }
        }
    }

    /** Conflicto de versión: se cierra el formulario y se recarga lo vigente; nada se reenvía solo. */
    private fun failed(result: AccountsResult.Failure, fields: (Map<String, String>) -> Unit) {
        when {
            result.reason == AccountFailure.VALIDATION && result.fields.isNotEmpty() -> { mutable.update { it.copy(busy = false) }; fields(result.fields) }
            result.reason == AccountFailure.VERSION_CONFLICT || result.reason == AccountFailure.NOT_FOUND -> {
                mutable.update { it.copy(busy = false, organizationForm = null, locationForm = null, failure = result.reason) }
                val s = mutable.value
                s.organizationId?.let { readOrganization(it) }
                fetch(s.locations.restart(), s.locationStatus)
            }
            else -> mutable.update { it.copy(busy = false, failure = result.reason) }
        }
    }
}
