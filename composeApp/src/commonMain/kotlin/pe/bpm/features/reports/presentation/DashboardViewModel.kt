package pe.bpm.features.reports.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.reports.domain.*

data class DashboardState(
    val userId: String? = null,
    val organizationId: String? = null,
    val role: String? = null,
    val loading: Boolean = false,
    val failure: ReportFailure? = null,
    val kpis: Kpis? = null,
)

/** Panel de indicadores (RF-30). El bloque de capacitación llega nulo para CHEF y la UI debe ocultarlo. */
class DashboardViewModel(private val loadKpis: LoadKpis) : ViewModel() {
    private val mutable = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    fun reset() {
        generation++
        job?.cancel()
        mutable.value = DashboardState()
    }

    fun open(userId: String, organizationId: String, role: String?) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.role == role) return
        generation++
        job?.cancel()
        mutable.value = DashboardState(userId, organizationId, role)
        refresh()
    }

    fun refresh() {
        val s = mutable.value
        val org = s.organizationId ?: return
        job?.cancel()
        val expected = ++generation
        mutable.value = s.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            val result = loadKpis(org, s.role)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is ReportResult.Success -> mutable.value.copy(loading = false, kpis = result.value)
                is ReportResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }
}
