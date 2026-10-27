package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.presentation.DataFreshness
import pe.bpm.features.checklists.domain.*

data class ChecklistAssignmentsState(
    val userId: String? = null,
    val organizationId: String? = null,
    val items: List<ChecklistAssignment> = emptyList(),
    val query: String = "",
    val token: String = "",
    val previous: List<String> = emptyList(),
    val nextToken: String = "",
    val loading: Boolean = false,
    val failure: AssignmentFailure? = null,
    val detail: ChecklistAssignment? = null,
    val detailLoading: Boolean = false,
    val detailFailure: AssignmentFailure? = null,
    val confirmDeactivate: String? = null,
    val actionBusy: Boolean = false,
    val actionFailure: AssignmentFailure? = null,
)

class ChecklistAssignmentsViewModel(
    private val load: LoadChecklistAssignments,
    private val detail: LoadChecklistAssignment,
    private val deactivate: DeactivateChecklistAssignment,
    private val freshness: DataFreshness = DataFreshness(),
    private val now: () -> Instant = { Clock.System.now() },
) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistAssignmentsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var detailJob: Job? = null
    private var actionJob: Job? = null

    fun reset() {
        job?.cancel()
        detailJob?.cancel()
        actionJob?.cancel()
        freshness.reset()
        mutable.value = ChecklistAssignmentsState()
    }

    fun open(user: String, org: String) {
        if (mutable.value.userId == user && mutable.value.organizationId == org) return
        reset()
        mutable.value = ChecklistAssignmentsState(userId = user, organizationId = org)
        refresh()
    }

    fun refresh() = fetch(mutable.value.copy(token = "", previous = emptyList()))

    fun refreshIfStale() {
        val s = mutable.value
        val periodEnded =
            s.items.any { value -> value.periodEndsAt?.let { Instant.parse(it) <= now() } == true }
        if ((freshness.stale || periodEnded) && !s.loading && s.failure == null) refresh()
    }

    fun search(value: String) =
        fetch(mutable.value.copy(query = value.take(160), token = "", previous = emptyList()), true)

    fun next() {
        val s = mutable.value
        if (!s.loading && s.nextToken.isNotEmpty())
            fetch(s.copy(token = s.nextToken, previous = s.previous + s.token))
    }

    fun previous() {
        val s = mutable.value
        if (!s.loading && s.previous.isNotEmpty())
            fetch(s.copy(token = s.previous.last(), previous = s.previous.dropLast(1)))
    }

    private fun fetch(page: ChecklistAssignmentsState, debounce: Boolean = false) {
        val org = page.organizationId ?: return
        job?.cancel()
        mutable.value =
            page.copy(loading = true, items = emptyList(), nextToken = "", failure = null)
        job =
            viewModelScope.launch {
                if (debounce) delay(300)
                val result = load(org, PageRequest(page.token, page.query))
                currentCoroutineContext().ensureActive()
                mutable.value =
                    when (result) {
                        is AssignmentPageResult.Success -> {
                            freshness.loaded()
                            mutable.value.copy(
                                loading = false,
                                items = result.items,
                                nextToken = result.nextToken,
                            )
                        }
                        is AssignmentPageResult.Failure ->
                            mutable.value.copy(loading = false, failure = result.reason)
                    }
                if (
                    result is AssignmentPageResult.Failure &&
                        result.reason in setOf(AssignmentFailure.SESSION, AssignmentFailure.DENIED)
                ) {
                    detailJob?.cancel()
                    actionJob?.cancel()
                    mutable.value =
                        mutable.value.copy(
                            detail = null,
                            detailLoading = false,
                            confirmDeactivate = null,
                            actionBusy = false,
                        )
                }
            }
    }

    fun select(id: String?) {
        detailJob?.cancel()
        mutable.value =
            mutable.value.copy(detail = null, detailLoading = id != null, detailFailure = null)
        val org = mutable.value.organizationId ?: return
        if (id == null) return
        detailJob =
            viewModelScope.launch {
                val result = detail(org, id)
                currentCoroutineContext().ensureActive()
                mutable.value =
                    when (result) {
                        is AssignmentResult.Success ->
                            mutable.value.copy(detail = result.value, detailLoading = false)
                        is AssignmentResult.Failure ->
                            mutable.value.copy(detailLoading = false, detailFailure = result.reason)
                    }
            }
    }

    fun requestDeactivate(id: String?) {
        if (!mutable.value.actionBusy)
            mutable.value = mutable.value.copy(confirmDeactivate = id, actionFailure = null)
    }

    fun confirmDeactivate() {
        val s = mutable.value
        val id = s.confirmDeactivate ?: return
        val org = s.organizationId ?: return
        if (s.actionBusy) return
        mutable.value = s.copy(actionBusy = true, actionFailure = null)
        actionJob =
            viewModelScope.launch {
                val result = deactivate(org, id)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is AssignmentResult.Success -> {
                        mutable.value =
                            mutable.value.copy(actionBusy = false, confirmDeactivate = null)
                        refresh()
                    }
                    is AssignmentResult.Failure ->
                        mutable.value =
                            mutable.value.copy(actionBusy = false, actionFailure = result.reason)
                }
            }
    }
}
