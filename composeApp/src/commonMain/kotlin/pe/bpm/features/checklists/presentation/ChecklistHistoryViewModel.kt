package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.presentation.DataFreshness
import pe.bpm.features.checklists.domain.*

data class ChecklistHistoryState(val userId: String? = null, val organizationId: String? = null, val scope: HistoryScope = HistoryScope.Mine,
    val items: List<ChecklistRunSummary> = emptyList(), val status: String = "", val query: String = "", val token: String = "",
    val previous: List<String> = emptyList(), val nextToken: String = "", val loading: Boolean = false, val failure: ChecklistFailure? = null,
    val detail: ChecklistRun? = null, val detailLoading: Boolean = false, val detailFailure: RunFailure? = null)

/** Consulta de revisiones independiente de la edición: supervisar nunca recupera ni envía un borrador. */
class ChecklistHistoryViewModel(private val load: LoadChecklistHistory, private val detail: LoadChecklistRun,
    private val freshness: DataFreshness = DataFreshness()) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistHistoryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var detailJob: Job? = null
    fun reset() { job?.cancel(); detailJob?.cancel(); freshness.reset(); mutable.value = ChecklistHistoryState() }
    fun open(user: String, org: String, scope: HistoryScope) {
        val current = mutable.value
        if (current.userId == user && current.organizationId == org && current.scope == scope) return
        reset(); mutable.value = ChecklistHistoryState(userId = user, organizationId = org, scope = scope); refresh()
    }
    fun refresh() = fetch(mutable.value.copy(token = "", previous = emptyList()))
    fun refreshIfStale() { if (freshness.stale && !mutable.value.loading && mutable.value.failure == null) refresh() }
    fun search(value: String) = fetch(mutable.value.copy(query = value.take(160), token = "", previous = emptyList()), debounce = true)
    fun filter(status: String) { if (status in setOf("", "DRAFT", "SUBMITTED")) fetch(mutable.value.copy(status = status, token = "", previous = emptyList())) }
    fun next() { val s = mutable.value; if (!s.loading && s.nextToken.isNotEmpty()) fetch(s.copy(token = s.nextToken, previous = s.previous + s.token)) }
    fun previous() { val s = mutable.value; if (!s.loading && s.previous.isNotEmpty()) fetch(s.copy(token = s.previous.last(), previous = s.previous.dropLast(1))) }
    private fun fetch(page: ChecklistHistoryState, debounce: Boolean = false) {
        val org = page.organizationId ?: return
        job?.cancel(); mutable.value = page.copy(loading = true, items = emptyList(), failure = null, nextToken = "")
        job = viewModelScope.launch {
            if (debounce) delay(300)
            val result = load(org, PageRequest(page.token, page.query), page.scope, page.status)
            currentCoroutineContext().ensureActive()
            mutable.value = when (result) {
                is ChecklistHistoryResult.Success -> { freshness.loaded(); mutable.value.copy(loading = false, items = result.items, nextToken = result.nextToken) }
                is ChecklistHistoryResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
            if (result is ChecklistHistoryResult.Failure && result.reason in setOf(ChecklistFailure.ACCESS_DENIED, ChecklistFailure.SESSION_REQUIRED)) {
                detailJob?.cancel(); mutable.value = mutable.value.copy(detail = null, detailLoading = false)
            }
        }
    }
    fun select(id: String?) {
        detailJob?.cancel(); mutable.value = mutable.value.copy(detail = null, detailLoading = id != null, detailFailure = null)
        val org = mutable.value.organizationId ?: return
        if (id == null) return
        detailJob = viewModelScope.launch {
            val result = detail(org, id); currentCoroutineContext().ensureActive()
            mutable.value = when (result) {
                is RunResult.Success -> mutable.value.copy(detail = result.run, detailLoading = false)
                is RunResult.Failure -> mutable.value.copy(detailFailure = result.reason, detailLoading = false)
            }
        }
    }
}
