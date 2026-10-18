package pe.bpm.features.checklists.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.checklists.domain.*

data class ChecklistCatalogState(
    val userId: String? = null,
    val organizationId: String? = null,
    val loading: Boolean = false,
    val templates: List<ChecklistTemplate> = emptyList(),
    val query: String = "",
    val token: String = "",
    val previous: List<String> = emptyList(),
    val nextToken: String = "",
    val failure: ChecklistFailure? = null,
    val detail: ChecklistTemplate? = null,
    val detailLoading: Boolean = false,
    val detailFailure: ChecklistFailure? = null,
) {
    val visibleTemplates: List<ChecklistTemplate> get() = templates
}

class ChecklistCatalogViewModel(private val loadCatalog: LoadChecklistCatalog, private val loadTemplate: LoadChecklistTemplate) : ViewModel() {
    private val mutable = MutableStateFlow(ChecklistCatalogState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var detailJob: Job? = null
    private var detailId: String? = null

    fun open(userId: String, organizationId: String) {
        if (mutable.value.userId == userId && mutable.value.organizationId == organizationId) return
        reset()
        mutable.value = ChecklistCatalogState(userId = userId, organizationId = organizationId)
        refresh()
    }
    fun reset() {
        job?.cancel(); detailJob?.cancel(); detailId = null
        mutable.value = ChecklistCatalogState()
    }
    fun refresh() {
        fetch(mutable.value.copy(token = "", previous = emptyList()))
        detailId?.let(::showTemplate)
    }
    fun search(query: String) = fetch(mutable.value.copy(query = query.take(160), token = "", previous = emptyList()), debounce = true)
    fun next() {
        val p = mutable.value
        if (!p.loading && p.nextToken.isNotEmpty()) fetch(p.copy(token = p.nextToken, previous = p.previous + p.token))
    }
    fun previous() {
        val p = mutable.value
        if (!p.loading && p.previous.isNotEmpty()) fetch(p.copy(token = p.previous.last(), previous = p.previous.dropLast(1)))
    }
    private fun fetch(page: ChecklistCatalogState, debounce: Boolean = false) {
        val organizationId = mutable.value.organizationId ?: return
        job?.cancel()
        mutable.value = mutable.value.copy(loading = true, templates = emptyList(), failure = null,
            query = page.query, token = page.token, previous = page.previous)
        job = viewModelScope.launch {
            if (debounce) delay(300)
            val result = loadCatalog(organizationId, PageRequest(page.token, page.query))
            currentCoroutineContext().ensureActive()
            mutable.value = when (result) {
                is ChecklistCatalogResult.Success -> mutable.value.copy(loading = false, templates = result.templates, nextToken = result.nextToken)
                is ChecklistCatalogResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
            if (result is ChecklistCatalogResult.Failure && result.reason in setOf(ChecklistFailure.ACCESS_DENIED, ChecklistFailure.SESSION_REQUIRED)) {
                detailJob?.cancel()
                mutable.value = mutable.value.copy(detail = null, detailFailure = result.reason, detailLoading = false)
            }
        }
    }
    fun showTemplate(id: String?) {
        detailJob?.cancel(); detailId = id
        mutable.value = mutable.value.copy(detail = null, detailLoading = id != null, detailFailure = null)
        val org = mutable.value.organizationId ?: return
        if (id == null) return
        detailJob = viewModelScope.launch {
            val result = loadTemplate(org, id)
            currentCoroutineContext().ensureActive()
            mutable.value = when (result) {
                is ChecklistTemplateResult.Success -> mutable.value.copy(detail = result.template, detailLoading = false)
                is ChecklistTemplateResult.Failure -> {
                    if (result.reason in setOf(ChecklistFailure.ACCESS_DENIED, ChecklistFailure.SESSION_REQUIRED)) {
                        job?.cancel()
                        mutable.value.copy(templates = emptyList(), loading = false, failure = result.reason,
                            detail = null, detailFailure = result.reason, detailLoading = false)
                    } else mutable.value.copy(detailFailure = result.reason, detailLoading = false)
                }
            }
        }
    }
}
