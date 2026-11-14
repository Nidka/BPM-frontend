package pe.bpm.features.reports.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.reports.domain.*

enum class ReportTab(val label: String) { COMPLIANCE("Cumplimiento"), SUMMARY("Resumen") }

data class ReportsState(
    val userId: String? = null,
    val organizationId: String? = null,
    val role: String? = null,
    val tab: ReportTab = ReportTab.COMPLIANCE,
    val from: String = "",
    val to: String = "",
    /** Días del atajo elegido; nulo cuando las fechas se escribieron a mano. */
    val presetDays: Int? = 30,
    val locationId: String? = null,
    val periodError: PeriodError? = null,
    val loading: Boolean = false,
    val failure: ReportFailure? = null,
    val compliance: ComplianceReport? = null,
    val summary: SummaryReport? = null,
    val exporting: Boolean = false,
    val exportFailure: ReportFailure? = null,
    /** Archivo listo para que la plataforma lo guarde; la pantalla lo consume con [ReportsViewModel.exportHandled]. */
    val exportReady: CsvExport? = null,
    val exportNotice: String? = null,
) {
    val maxDays: Int get() = if (tab == ReportTab.COMPLIANCE) ReportPeriodRules.COMPLIANCE_MAX_DAYS else ReportPeriodRules.SUMMARY_MAX_DAYS
}

/** Cumplimiento por día/sede, resumen por período (RF-16/28) y exportación CSV (RF-17). */
class ReportsViewModel(
    private val loadCompliance: LoadCompliance,
    private val loadSummary: LoadSummary,
    private val exportCsv: ExportComplianceCsv,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
    private val mutable = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var exportJob: Job? = null

    private fun cancelAll() { generation++; job?.cancel(); exportJob?.cancel() }

    fun reset() { cancelAll(); mutable.value = ReportsState() }

    fun open(userId: String, organizationId: String, role: String?) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.role == role) return
        cancelAll()
        val (from, to) = ReportPeriodRules.lastDays(ReportDates.today(nowMillis()), 30)
        mutable.value = ReportsState(userId, organizationId, role, from = from, to = to)
        load()
    }

    fun selectTab(tab: ReportTab) {
        if (mutable.value.tab == tab) return
        mutable.value = mutable.value.copy(tab = tab, failure = null, periodError = null)
        load()
    }

    /** Atajos: últimos [days] días terminando hoy (Lima). */
    fun selectPreset(days: Int) {
        val (from, to) = ReportPeriodRules.lastDays(ReportDates.today(nowMillis()), days)
        mutable.value = mutable.value.copy(from = from, to = to, presetDays = days, periodError = null)
        load()
    }

    fun selectCurrentMonth() {
        val today = ReportDates.today(nowMillis())
        mutable.value = mutable.value.copy(from = ReportDates.format(ReportDates.monthStart(today)), to = ReportDates.format(today), presetDays = null, periodError = null)
        load()
    }

    fun editFrom(value: String) { mutable.value = mutable.value.copy(from = value.take(10), presetDays = null, periodError = null) }
    fun editTo(value: String) { mutable.value = mutable.value.copy(to = value.take(10), presetDays = null, periodError = null) }

    fun selectLocation(locationId: String?) {
        if (mutable.value.locationId == locationId) return
        mutable.value = mutable.value.copy(locationId = locationId)
        load()
    }

    fun load() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val error = ReportPeriodRules.validate(s.from, s.to, s.maxDays)
        if (error != null) {
            job?.cancel(); generation++
            mutable.value = s.copy(periodError = error, loading = false, failure = null)
            return
        }
        job?.cancel()
        val expected = ++generation
        mutable.value = s.copy(loading = true, failure = null, periodError = null)
        job = viewModelScope.launch {
            val state = mutable.value
            if (state.tab == ReportTab.COMPLIANCE) {
                val result = loadCompliance(org, s.role, s.from, s.to, s.locationId)
                if (expected != generation) return@launch
                mutable.value = when (result) {
                    is ReportResult.Success -> mutable.value.copy(loading = false, compliance = result.value)
                    is ReportResult.Failure -> mutable.value.copy(loading = false, failure = result.reason, compliance = null)
                }
            } else {
                val result = loadSummary(org, s.role, s.from, s.to, s.locationId)
                if (expected != generation) return@launch
                mutable.value = when (result) {
                    is ReportResult.Success -> mutable.value.copy(loading = false, summary = result.value)
                    is ReportResult.Failure -> mutable.value.copy(loading = false, failure = result.reason, summary = null)
                }
            }
        }
    }

    /** Pide el CSV del período; EXPORT_TOO_LARGE se informa para que se acorte el período o se filtre por sede. */
    fun export() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (s.exporting) return
        val error = ReportPeriodRules.validate(s.from, s.to, ReportPeriodRules.SUMMARY_MAX_DAYS)
        if (error != null) { mutable.value = s.copy(periodError = error); return }
        val expected = generation
        mutable.value = s.copy(exporting = true, exportFailure = null, exportNotice = null, exportReady = null)
        exportJob = viewModelScope.launch {
            val result = exportCsv(org, s.role, s.from, s.to, s.locationId)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is ReportResult.Success -> mutable.value.copy(exporting = false, exportReady = result.value)
                is ReportResult.Failure -> mutable.value.copy(exporting = false, exportFailure = result.reason)
            }
        }
    }

    /** La plataforma terminó de guardar o compartir el archivo (o el usuario canceló). */
    fun exportHandled(saved: Boolean) {
        val file = mutable.value.exportReady ?: return
        mutable.value = mutable.value.copy(exportReady = null,
            exportNotice = if (saved) "Archivo ${file.fileName} listo." else "No se guardó el archivo. Puedes volver a exportar.")
    }

    fun dismissExportMessage() { mutable.value = mutable.value.copy(exportFailure = null, exportNotice = null) }
}
