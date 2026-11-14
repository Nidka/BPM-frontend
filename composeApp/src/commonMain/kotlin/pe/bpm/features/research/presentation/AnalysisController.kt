package pe.bpm.features.research.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.research.domain.*

/** Alta de una necesidad detectada (RF-50). */
data class NeedForm(
    val organizationId: String? = null,
    val category: NeedCategory = NeedCategory.TRAINING,
    val description: String = "",
    val busy: Boolean = false,
    val showIssues: Boolean = false,
    val failure: ResearchFailure? = null,
)

data class AnalysisState(
    val organizations: List<ResearchOrganization> = emptyList(),
    val organizationsFailure: ResearchFailure? = null,
    /** Filtro común de necesidades y exportación; null significa todas las empresas. */
    val organizationId: String? = null,
    val results: ResearchResults? = null,
    val resultsLoading: Boolean = false,
    val resultsFailure: ResearchFailure? = null,
    /** Cursores recorridos; el último es el de la página visible. */
    val resultTokens: List<String> = listOf(""),
    val needs: Page<DetectedNeed>? = null,
    val needsLoading: Boolean = false,
    val needsFailure: ResearchFailure? = null,
    val needTokens: List<String> = listOf(""),
    val needForm: NeedForm? = null,
    val exporting: Boolean = false,
    val exportFailure: ResearchFailure? = null,
    /** Archivo listo para guardar; la pantalla lo entrega a la plataforma y llama a [AnalysisController.exportDelivered]. */
    val pendingExport: CsvExport? = null,
    val notice: String? = null,
) {
    val hasPreviousResults: Boolean get() = resultTokens.size > 1
    val hasPreviousNeeds: Boolean get() = needTokens.size > 1
}

/** Resultados consolidados, exportación anonimizada y necesidades detectadas. */
class AnalysisController(
    private val analyze: AnalyzeResearch,
    private val now: () -> String,
    private val scope: CoroutineScope,
    private val pageSize: Int = 20,
) {
    private val mutable = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = mutable.asStateFlow()
    private var generation = 0L
    private var resultsJob: Job? = null
    private var needsJob: Job? = null
    private var exportJob: Job? = null
    private var organizationsJob: Job? = null
    private var formJob: Job? = null

    fun reset() {
        generation++
        listOf(resultsJob, needsJob, exportJob, organizationsJob, formJob).forEach { it?.cancel() }
        mutable.value.pendingExport?.bytes?.fill(0)
        mutable.value = AnalysisState()
    }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    fun loadOrganizations() {
        if (mutable.value.organizations.isNotEmpty() || organizationsJob?.isActive == true) return
        val expected = generation
        organizationsJob = scope.launch {
            val result = analyze.organizations()
            ensureActive(); if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(organizations = result.value.sortedBy { it.name.lowercase() }, organizationsFailure = null)
                is ResearchResult.Failure -> mutable.value.copy(organizationsFailure = result.reason)
            }
        }
    }

    // ---- Resultados --------------------------------------------------------------------------

    fun loadResults() {
        val s = mutable.value
        if (s.results == null || s.resultsFailure != null) fetchResults(s.resultTokens)
    }

    fun refreshResults() = fetchResults(listOf(""))

    fun nextResults() {
        val next = mutable.value.results?.page?.nextToken?.takeIf { it.isNotEmpty() } ?: return
        if (!mutable.value.resultsLoading) fetchResults(mutable.value.resultTokens + next)
    }

    fun previousResults() {
        val tokens = mutable.value.resultTokens
        if (tokens.size > 1 && !mutable.value.resultsLoading) fetchResults(tokens.dropLast(1))
    }

    private fun fetchResults(tokens: List<String>) {
        resultsJob?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(resultsLoading = true, resultsFailure = null)
        resultsJob = scope.launch {
            val result = analyze.results(null, PageRequest(tokens.last(), size = pageSize))
            ensureActive(); if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(resultsLoading = false, results = result.value, resultTokens = tokens)
                is ResearchResult.Failure -> mutable.value.copy(resultsLoading = false, resultsFailure = result.reason)
            }
        }
    }

    // ---- Exportación -------------------------------------------------------------------------

    fun selectOrganization(id: String?) {
        if (mutable.value.organizationId == id) return
        mutable.value = mutable.value.copy(organizationId = id, needTokens = listOf(""), needs = null)
        fetchNeeds(listOf(""))
    }

    /** Descarga el CSV anonimizado (con el filtro de empresa elegido) y lo deja listo para guardar. */
    fun export() {
        val s = mutable.value
        if (s.exporting || s.pendingExport != null) return
        exportJob?.cancel()
        val expected = generation
        mutable.value = s.copy(exporting = true, exportFailure = null, notice = null)
        exportJob = scope.launch {
            val result = analyze.export(s.results?.version?.id, s.organizationId)
            ensureActive(); if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(exporting = false, pendingExport = result.value)
                is ResearchResult.Failure -> mutable.value.copy(exporting = false, exportFailure = result.reason)
            }
        }
    }

    /** La plataforma terminó de guardar/compartir el archivo: se descartan los bytes de memoria. */
    fun exportDelivered(saved: Boolean, failed: Boolean = false) {
        val s = mutable.value
        s.pendingExport?.bytes?.fill(0)
        mutable.value = s.copy(pendingExport = null, notice = when {
            failed -> "No pudimos guardar el archivo. Inténtalo de nuevo."
            saved -> "Archivo CSV guardado."
            else -> null
        })
    }

    // ---- Necesidades -------------------------------------------------------------------------

    fun loadNeeds() {
        val s = mutable.value
        if (s.needs == null || s.needsFailure != null) fetchNeeds(s.needTokens)
    }

    fun refreshNeeds() = fetchNeeds(listOf(""))

    fun nextNeeds() {
        val next = mutable.value.needs?.nextToken?.takeIf { it.isNotEmpty() } ?: return
        if (!mutable.value.needsLoading) fetchNeeds(mutable.value.needTokens + next)
    }

    fun previousNeeds() {
        val tokens = mutable.value.needTokens
        if (tokens.size > 1 && !mutable.value.needsLoading) fetchNeeds(tokens.dropLast(1))
    }

    private fun fetchNeeds(tokens: List<String>) {
        needsJob?.cancel()
        val expected = generation
        val organization = mutable.value.organizationId
        mutable.value = mutable.value.copy(needsLoading = true, needsFailure = null)
        needsJob = scope.launch {
            val result = analyze.needs(organization, PageRequest(tokens.last(), size = pageSize))
            ensureActive(); if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(needsLoading = false, needs = result.value, needTokens = tokens)
                is ResearchResult.Failure -> mutable.value.copy(needsLoading = false, needsFailure = result.reason)
            }
        }
    }

    fun startNeed() { mutable.value = mutable.value.copy(needForm = NeedForm(organizationId = mutable.value.organizationId), notice = null) }

    fun editNeed(change: (NeedForm) -> NeedForm) {
        val form = mutable.value.needForm ?: return
        if (!form.busy) mutable.value = mutable.value.copy(needForm = change(form).copy(failure = null))
    }

    fun cancelNeed() { if (mutable.value.needForm?.busy != true) mutable.value = mutable.value.copy(needForm = null) }

    fun saveNeed() {
        val form = mutable.value.needForm ?: return
        if (form.busy) return
        val organization = form.organizationId
        val description = form.description.trim()
        if (organization == null || description.isEmpty() || description.length > ResearchLimits.NEED_DESCRIPTION_MAX) {
            mutable.value = mutable.value.copy(needForm = form.copy(showIssues = true))
            return
        }
        formJob?.cancel()
        val expected = generation
        mutable.value = mutable.value.copy(needForm = form.copy(busy = true, showIssues = false, failure = null))
        formJob = scope.launch {
            val result = analyze.createNeed(NewNeed(organization, form.category, description, now()))
            ensureActive(); if (expected != generation) return@launch
            when (result) {
                is ResearchResult.Success -> {
                    mutable.value = mutable.value.copy(needForm = null, notice = "Registramos la necesidad detectada.")
                    fetchNeeds(listOf(""))
                }
                is ResearchResult.Failure -> mutable.value = mutable.value.copy(needForm = form.copy(busy = false, failure = result.reason))
            }
        }
    }
}
