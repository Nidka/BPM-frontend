package pe.bpm.features.incidents.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pe.bpm.core.domain.*
import pe.bpm.core.presentation.DataFreshness
import pe.bpm.features.incidents.domain.*

/** Consultas y navegación; la edición durable vive en IncidentEditor. */
class IncidentsViewModel(
    private val manage: ManageIncidents,
    private val newId: () -> String,
    private val now: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(IncidentsState())
    val state = mutable.asStateFlow()
    val editor =
        IncidentEditor(
            manage,
            viewModelScope,
            newId,
            now,
            { result ->
                when (result) {
                    is IncidentSaved.Detail ->
                        mutable.value = mutable.value.copy(detail = result.value)
                    is IncidentSaved.Catalog -> refresh()
                }
            },
            ::reject,
        )
    private var job: Job? = null
    private var pickerJob: Job? = null
    private var historyJob: Job? = null
    private var generation = 0L
    private val freshness = DataFreshness()

    fun reset() {
        generation++
        job?.cancel()
        pickerJob?.cancel()
        historyJob?.cancel()
        editor.reset()
        freshness.reset()
        mutable.value = IncidentsState()
    }

    fun open(
        userId: String,
        organizationId: String,
        role: String,
        siteId: String? = null,
        timeZone: String = "",
    ) {
        val s = mutable.value
        if (
            s.userId == userId &&
                s.organizationId == organizationId &&
                s.siteId == siteId &&
                s.role == role &&
                s.timeZone == timeZone
        )
            return
        reset()
        mutable.value = IncidentsState(userId, organizationId, siteId, role, timeZone)
        if (siteId == null) return
        val expected = generation
        mutable.value = mutable.value.copy(loading = true)
        job =
            viewModelScope.launch {
                when (val result = manage.authorize(organizationId, siteId, role)) {
                    is IncidentsResult.Success -> {
                        if (expected != generation) return@launch
                        mutable.value = mutable.value.copy(authorized = true, loading = false)
                        editor.restore(DraftOwner(userId, organizationId), siteId, role, timeZone)
                        if (expected == generation && currentCoroutineContext().isActive) loadList()
                    }
                    is IncidentsResult.Failure -> fail(result.reason, expected)
                }
            }
    }

    private fun reject(reason: IncidentsFailure) {
        generation++
        job?.cancel()
        pickerJob?.cancel()
        historyJob?.cancel()
        editor.reset()
        mutable.value =
            mutable.value.copy(
                authorized = false,
                loading = false,
                failure = reason,
                incidents = IncidentPage(),
                catalog = IncidentPage(),
                detail = null,
                picker = null,
                choices = IncidentPage(),
                catalogChoices = IncidentPage(),
                history = IncidentPage(),
            )
    }

    private suspend fun fail(reason: IncidentsFailure, expected: Long) {
        currentCoroutineContext().ensureActive()
        if (expected != generation) return
        if (reason in setOf(IncidentsFailure.SESSION, IncidentsFailure.DENIED)) reject(reason)
        else mutable.value = mutable.value.copy(loading = false, failure = reason)
    }

    fun refresh() {
        val s = mutable.value
        if (!s.authorized) {
            if (s.siteId != null && s.userId != null && s.organizationId != null) {
                val user = s.userId
                val org = s.organizationId
                val site = s.siteId
                val role = s.role
                val zone = s.timeZone
                reset()
                open(user, org, role, site, zone)
            }
        } else if (s.detail != null) detail(s.detail.summary.id) else loadList()
    }

    fun refreshIfStale() {
        if (freshness.stale && !mutable.value.loading && editor.state.value.form == null) refresh()
    }

    fun catalogMode(value: Boolean) {
        if (
            editor.state.value.form != null ||
                (value && !IncidentsAccess.canAuthor(mutable.value.role))
        )
            return
        mutable.value = mutable.value.copy(catalogMode = value, detail = null, failure = null)
        loadList()
    }

    fun filter(value: IncidentStatus?) {
        if (editor.state.value.form != null) return
        mutable.value = mutable.value.copy(status = value, incidents = IncidentPage())
        loadList()
    }

    fun query(value: String) {
        val s = mutable.value
        mutable.value =
            if (s.catalogMode) s.copy(catalog = IncidentPage(query = value.take(200)))
            else s.copy(incidents = IncidentPage(query = value.take(200)))
        job?.cancel()
        job =
            viewModelScope.launch {
                delay(300)
                loadList()
            }
    }

    fun next() = page(true)

    fun previous() = page(false)

    private fun <T> moved(p: IncidentPage<T>, forward: Boolean): IncidentPage<T> =
        when {
            forward && p.next.isNotEmpty() ->
                p.copy(token = p.next, previous = p.previous + p.token)
            !forward && p.previous.isNotEmpty() ->
                p.copy(token = p.previous.last(), previous = p.previous.dropLast(1))
            else -> p
        }

    private fun page(forward: Boolean) {
        val s = mutable.value
        if (s.loading) return
        mutable.value =
            if (s.catalogMode) s.copy(catalog = moved(s.catalog, forward))
            else s.copy(incidents = moved(s.incidents, forward))
        loadList()
    }

    private fun loadList() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val site = s.siteId ?: return
        if (!s.authorized) return
        job?.cancel()
        val expected = generation
        mutable.value = s.copy(loading = true, failure = null)
        job =
            viewModelScope.launch {
                if (s.catalogMode) {
                    when (
                        val r = manage.catalog(org, PageRequest(s.catalog.token, s.catalog.query))
                    ) {
                        is IncidentsResult.Success ->
                            if (expected == generation && currentCoroutineContext().isActive)
                                freshness.loaded().let {
                                    mutable.value =
                                        mutable.value.copy(
                                            loading = false,
                                            catalog =
                                                s.catalog.copy(
                                                    rows = r.value.items,
                                                    next = r.value.nextToken,
                                                ),
                                        )
                                }
                        is IncidentsResult.Failure -> failList(r.reason, expected)
                    }
                } else {
                    when (
                        val r =
                            manage.incidents(
                                org,
                                site,
                                s.status,
                                PageRequest(s.incidents.token, s.incidents.query),
                            )
                    ) {
                        is IncidentsResult.Success ->
                            if (expected == generation && currentCoroutineContext().isActive)
                                freshness.loaded().let {
                                    mutable.value =
                                        mutable.value.copy(
                                            loading = false,
                                            incidents =
                                                s.incidents.copy(
                                                    rows = r.value.items,
                                                    next = r.value.nextToken,
                                                ),
                                        )
                                }
                        is IncidentsResult.Failure -> failList(r.reason, expected)
                    }
                }
            }
    }

    private suspend fun failList(reason: IncidentsFailure, expected: Long) {
        if (reason == IncidentsFailure.INVALID_PAGE && expected == generation)
            mutable.value =
                mutable.value.copy(
                    incidents = IncidentPage(query = mutable.value.incidents.query),
                    catalog = IncidentPage(query = mutable.value.catalog.query),
                )
        fail(reason, expected)
    }

    fun detail(id: String) {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (!s.authorized || editor.state.value.form != null) return
        job?.cancel()
        val expected = generation
        mutable.value = s.copy(loading = true, failure = null, showHistory = false)
        job =
            viewModelScope.launch {
                when (val r = manage.detail(org, id)) {
                    is IncidentsResult.Success ->
                        if (expected == generation && currentCoroutineContext().isActive) {
                            if (r.value.summary.locationId != s.siteId)
                                fail(IncidentsFailure.INVALID_RESPONSE, expected)
                            else {
                                freshness.loaded()
                                mutable.value =
                                    mutable.value.copy(
                                        loading = false,
                                        detail = r.value,
                                        history = IncidentPage(),
                                    )
                            }
                        }
                    is IncidentsResult.Failure -> fail(r.reason, expected)
                }
            }
    }

    fun closeDetail() {
        historyJob?.cancel()
        mutable.value = mutable.value.copy(detail = null, showHistory = false)
        loadList()
    }

    fun newReport() = editor.new("REPORT")

    fun newCatalog() = editor.new("CATALOG")

    fun newVersion(value: IncidentCatalog) = editor.new("CATALOG", catalog = value)

    fun action(type: IncidentActionType) = editor.new(type.name, mutable.value.detail)

    fun picker(value: IncidentPicker?) {
        pickerJob?.cancel()
        mutable.value =
            mutable.value.copy(
                picker = value,
                choices = IncidentPage(),
                catalogChoices = IncidentPage(),
            )
        if (value != null) loadPicker()
    }

    fun pickerQuery(value: String) {
        mutable.value =
            mutable.value.copy(
                choices = IncidentPage(query = value.take(200)),
                catalogChoices = IncidentPage(query = value.take(200)),
            )
        pickerJob?.cancel()
        pickerJob =
            viewModelScope.launch {
                delay(300)
                loadPicker()
            }
    }

    fun pickerNext() = pickerPage(true)

    fun pickerPrevious() = pickerPage(false)

    private fun pickerPage(forward: Boolean) {
        mutable.value =
            mutable.value.copy(
                choices = moved(mutable.value.choices, forward),
                catalogChoices = moved(mutable.value.catalogChoices, forward),
            )
        loadPicker()
    }

    private fun loadPicker() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val type = s.picker ?: return
        pickerJob?.cancel()
        val expected = generation
        mutable.value =
            s.copy(
                choices = s.choices.copy(loading = true, failure = null),
                catalogChoices = s.catalogChoices.copy(loading = true, failure = null),
            )
        pickerJob =
            viewModelScope.launch {
                if (type == IncidentPicker.CATALOG)
                    when (
                        val r =
                            manage.catalog(
                                org,
                                PageRequest(s.catalogChoices.token, s.catalogChoices.query),
                            )
                    ) {
                        is IncidentsResult.Success ->
                            if (expected == generation && currentCoroutineContext().isActive)
                                mutable.value =
                                    mutable.value.copy(
                                        catalogChoices =
                                            s.catalogChoices.copy(
                                                rows = r.value.items,
                                                next = r.value.nextToken,
                                                loading = false,
                                            )
                                    )
                        is IncidentsResult.Failure -> pickerFailure(r.reason, expected)
                    }
                else {
                    val id = editor.state.value.form?.incidentId ?: return@launch
                    when (
                        val r =
                            manage.assignees(org, id, PageRequest(s.choices.token, s.choices.query))
                    ) {
                        is IncidentsResult.Success ->
                            if (expected == generation && currentCoroutineContext().isActive)
                                mutable.value =
                                    mutable.value.copy(
                                        choices =
                                            s.choices.copy(
                                                rows = r.value.items,
                                                next = r.value.nextToken,
                                                loading = false,
                                            )
                                    )
                        is IncidentsResult.Failure -> pickerFailure(r.reason, expected)
                    }
                }
            }
    }

    private suspend fun pickerFailure(reason: IncidentsFailure, expected: Long) {
        currentCoroutineContext().ensureActive()
        if (expected != generation) return
        if (reason in setOf(IncidentsFailure.SESSION, IncidentsFailure.DENIED)) {
            reject(reason)
            return
        }
        val s = mutable.value
        mutable.value =
            s.copy(
                choices =
                    (if (reason == IncidentsFailure.INVALID_PAGE) IncidentPage() else s.choices)
                        .copy(loading = false, failure = reason),
                catalogChoices =
                    (if (reason == IncidentsFailure.INVALID_PAGE) IncidentPage()
                        else s.catalogChoices)
                        .copy(loading = false, failure = reason),
            )
    }

    fun select(value: IncidentCatalog) {
        editor.selectCatalog(value)
        picker(null)
    }

    fun select(value: IncidentAssignee) {
        editor.selectResponsible(value)
        picker(null)
    }

    fun history() {
        mutable.value = mutable.value.copy(showHistory = true)
        loadHistory()
    }

    fun historyNext() {
        mutable.value = mutable.value.copy(history = moved(mutable.value.history, true))
        loadHistory()
    }

    fun historyPrevious() {
        mutable.value = mutable.value.copy(history = moved(mutable.value.history, false))
        loadHistory()
    }

    private fun loadHistory() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val id = s.detail?.summary?.id ?: return
        historyJob?.cancel()
        val expected = generation
        mutable.value = s.copy(history = s.history.copy(loading = true, failure = null))
        historyJob =
            viewModelScope.launch {
                when (val r = manage.history(org, id, PageRequest(s.history.token))) {
                    is IncidentsResult.Success ->
                        if (expected == generation && currentCoroutineContext().isActive)
                            mutable.value =
                                mutable.value.copy(
                                    history =
                                        s.history.copy(
                                            rows = r.value.items,
                                            next = r.value.nextToken,
                                            loading = false,
                                        )
                                )
                    is IncidentsResult.Failure ->
                        if (expected == generation && currentCoroutineContext().isActive) {
                            if (
                                r.reason in setOf(IncidentsFailure.SESSION, IncidentsFailure.DENIED)
                            )
                                reject(r.reason)
                            else
                                mutable.value =
                                    mutable.value.copy(
                                        history =
                                            (if (r.reason == IncidentsFailure.INVALID_PAGE)
                                                    IncidentPage()
                                                else s.history)
                                                .copy(loading = false, failure = r.reason)
                                    )
                        }
                }
            }
    }

    fun closeHistory() {
        historyJob?.cancel()
        mutable.value = mutable.value.copy(showHistory = false)
    }

    fun prepareBack(onLeave: () -> Unit) {
        val s = mutable.value
        when {
            s.picker != null -> picker(null)
            editor.state.value.form != null -> {
                if (editor.state.value.pending != null) prepareLeave(onLeave) else editor.close()
            }
            s.showHistory -> closeHistory()
            s.detail != null -> closeDetail()
            else -> prepareLeave(onLeave)
        }
    }

    fun prepareLeave(onReady: () -> Unit) {
        viewModelScope.launch { if (editor.flush()) onReady() }
    }

    suspend fun flush(): Boolean = editor.flush()

    override fun onCleared() {
        reset()
        super.onCleared()
    }
}
