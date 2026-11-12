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

enum class AdminTab(val label: String) { TRAINING("Capacitación"), CERTIFICATES("Constancias"), SETTINGS("Configuración") }

data class AdminState(
    val userId: String? = null,
    val organizationId: String? = null,
    val role: String? = null,
    val tab: AdminTab = AdminTab.TRAINING,
    // Capacitación (RF-09)
    val courses: List<CourseProgressRow>? = null,
    val coursesLoading: Boolean = false,
    val coursesFailure: ReportFailure? = null,
    val courseFilter: String? = null,
    val people: List<PersonProgressRow> = emptyList(),
    val peopleToken: String = "",
    val peopleLoading: Boolean = false,
    val peopleFailure: ReportFailure? = null,
    val peopleLoaded: Boolean = false,
    // Configuración (RF-29)
    val settings: ReportSettings? = null,
    val settingsLoading: Boolean = false,
    val settingsSaving: Boolean = false,
    val settingsFailure: ReportFailure? = null,
    val thresholdText: String = "",
    val weeklyEnabled: Boolean = true,
    val thresholdInvalid: Boolean = false,
    val settingsSaved: Boolean = false,
    // Constancias (RF-39)
    val certificates: List<ComplianceCertificate> = emptyList(),
    val certificatesToken: String = "",
    val certificatesLoading: Boolean = false,
    val certificatesFailure: ReportFailure? = null,
    val certificatesLoaded: Boolean = false,
    val certFrom: String = "",
    val certTo: String = "",
    val certPeriodError: PeriodError? = null,
    val issuing: Boolean = false,
    val issueFailure: ReportFailure? = null,
    /** Promedio y umbral con 2 decimales cuando el período no alcanza el umbral. */
    val issueFields: Map<String, String> = emptyMap(),
    val issued: IssuedCertificate? = null,
) {
    val settingsDirty: Boolean
        get() = settings != null && (weeklyEnabled != settings.weeklyEmailEnabled || ReportSettingsRules.parseThreshold(thresholdText) != settings.complianceThreshold)
}

/** Avance de capacitación, configuración de reportes y constancias; solo ADMIN/SUPER_ADMIN. */
class ReportsAdminViewModel(
    private val loadCourses: LoadTrainingCourseProgress,
    private val loadPeople: LoadTrainingPeopleProgress,
    private val loadSettings: LoadReportSettings,
    private val saveSettings: SaveReportSettings,
    private val issueCertificate: IssueComplianceCertificate,
    private val listCertificates: ListComplianceCertificates,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
    private val mutable = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = mutable.asStateFlow()
    private var generation = 0L
    private val jobs = HashMap<String, Job>()

    private fun launchJob(key: String, block: suspend (Long) -> Unit) {
        jobs[key]?.cancel()
        val expected = generation
        jobs[key] = viewModelScope.launch { block(expected) }
    }

    private fun cancelAll() { generation++; jobs.values.forEach { it.cancel() }; jobs.clear() }

    fun reset() { cancelAll(); mutable.value = AdminState() }

    fun open(userId: String, organizationId: String, role: String?) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.role == role) return
        cancelAll()
        // Por defecto el mes anterior completo, que es el período típico de una constancia.
        val today = ReportDates.today(nowMillis())
        val thisMonth = ReportDates.monthStart(today)
        val lastMonth = ReportDates.monthStart(thisMonth - 1)
        mutable.value = AdminState(userId, organizationId, role, certFrom = ReportDates.format(lastMonth), certTo = ReportDates.format(thisMonth - 1))
        selectTab(AdminTab.TRAINING)
    }

    fun selectTab(tab: AdminTab) {
        mutable.value = mutable.value.copy(tab = tab)
        val s = mutable.value
        when (tab) {
            AdminTab.TRAINING -> if (s.courses == null && !s.coursesLoading) { loadCourseRows(); loadFirstPeople() }
            AdminTab.CERTIFICATES -> if (!s.certificatesLoaded && !s.certificatesLoading) loadCertificates(false)
            AdminTab.SETTINGS -> if (s.settings == null && !s.settingsLoading) loadSettingsNow()
        }
    }

    // ---- Capacitación ------------------------------------------------------------------------

    fun loadCourseRows() {
        val s = mutable.value
        val org = s.organizationId ?: return
        mutable.value = s.copy(coursesLoading = true, coursesFailure = null)
        launchJob("courses") { expected ->
            val result = loadCourses(org, s.role)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is ReportResult.Success -> mutable.value.copy(coursesLoading = false, courses = result.value)
                is ReportResult.Failure -> mutable.value.copy(coursesLoading = false, coursesFailure = result.reason)
            }
        }
    }

    fun filterCourse(courseId: String?) {
        if (mutable.value.courseFilter == courseId && mutable.value.peopleLoaded) return
        mutable.value = mutable.value.copy(courseFilter = courseId, people = emptyList(), peopleToken = "", peopleLoaded = false)
        loadFirstPeople()
    }

    fun loadFirstPeople() = loadPeoplePage(append = false)

    fun loadMorePeople() { if (mutable.value.peopleToken.isNotEmpty() && !mutable.value.peopleLoading) loadPeoplePage(append = true) }

    private fun loadPeoplePage(append: Boolean) {
        val s = mutable.value
        val org = s.organizationId ?: return
        mutable.value = s.copy(peopleLoading = true, peopleFailure = null)
        launchJob("people") { expected ->
            val result = loadPeople(org, s.role, s.courseFilter, if (append) s.peopleToken else null)
            if (expected != generation || mutable.value.courseFilter != s.courseFilter) return@launchJob
            mutable.value = when (result) {
                is ReportResult.Success -> mutable.value.copy(peopleLoading = false, peopleLoaded = true,
                    people = if (append) mutable.value.people + result.value.items else result.value.items, peopleToken = result.value.nextPageToken)
                is ReportResult.Failure -> mutable.value.copy(peopleLoading = false, peopleFailure = result.reason)
            }
        }
    }

    // ---- Configuración -----------------------------------------------------------------------

    fun loadSettingsNow() {
        val s = mutable.value
        val org = s.organizationId ?: return
        mutable.value = s.copy(settingsLoading = true, settingsFailure = null)
        launchJob("settings") { expected ->
            val result = loadSettings(org, s.role)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is ReportResult.Success -> mutable.value.copy(settingsLoading = false, settings = result.value,
                    weeklyEnabled = result.value.weeklyEmailEnabled, thresholdText = formatThreshold(result.value.complianceThreshold), thresholdInvalid = false)
                is ReportResult.Failure -> mutable.value.copy(settingsLoading = false, settingsFailure = result.reason)
            }
        }
    }

    fun editThreshold(text: String) { mutable.value = mutable.value.copy(thresholdText = text.take(6), thresholdInvalid = false, settingsSaved = false) }
    fun toggleWeekly(enabled: Boolean) { mutable.value = mutable.value.copy(weeklyEnabled = enabled, settingsSaved = false) }

    fun saveSettingsNow() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (s.settingsSaving) return
        val threshold = ReportSettingsRules.parseThreshold(s.thresholdText)
        if (threshold == null) { mutable.value = s.copy(thresholdInvalid = true); return }
        mutable.value = s.copy(settingsSaving = true, settingsFailure = null, settingsSaved = false)
        launchJob("saveSettings") { expected ->
            val result = saveSettings(org, s.role, ReportSettings(s.weeklyEnabled, threshold))
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is ReportResult.Success -> mutable.value.copy(settingsSaving = false, settings = result.value, settingsSaved = true,
                    weeklyEnabled = result.value.weeklyEmailEnabled, thresholdText = formatThreshold(result.value.complianceThreshold))
                is ReportResult.Failure -> mutable.value.copy(settingsSaving = false, settingsFailure = result.reason)
            }
        }
    }

    // ---- Constancias -------------------------------------------------------------------------

    fun editCertFrom(value: String) { mutable.value = mutable.value.copy(certFrom = value.take(10), certPeriodError = null, issued = null, issueFailure = null) }
    fun editCertTo(value: String) { mutable.value = mutable.value.copy(certTo = value.take(10), certPeriodError = null, issued = null, issueFailure = null) }

    fun loadCertificates(append: Boolean) {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (append && s.certificatesToken.isEmpty()) return
        mutable.value = s.copy(certificatesLoading = true, certificatesFailure = null)
        launchJob("certificates") { expected ->
            val result = listCertificates(org, s.role, if (append) s.certificatesToken else null)
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is ReportResult.Success -> mutable.value.copy(certificatesLoading = false, certificatesLoaded = true,
                    certificates = if (append) mutable.value.certificates + result.value.items else result.value.items, certificatesToken = result.value.nextPageToken)
                is ReportResult.Failure -> mutable.value.copy(certificatesLoading = false, certificatesFailure = result.reason)
            }
        }
    }

    /** Solicita la constancia del período; si no alcanza el umbral el servidor informa ambos valores. */
    fun issue() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (s.issuing) return
        val error = ReportPeriodRules.validate(s.certFrom, s.certTo, ReportPeriodRules.SUMMARY_MAX_DAYS)
        if (error != null) { mutable.value = s.copy(certPeriodError = error); return }
        mutable.value = s.copy(issuing = true, issueFailure = null, issueFields = emptyMap(), issued = null, certPeriodError = null)
        launchJob("issue") { expected ->
            val result = issueCertificate(org, s.role, s.certFrom, s.certTo, ReportDates.today(nowMillis()))
            if (expected != generation) return@launchJob
            mutable.value = when (result) {
                is ReportResult.Success -> {
                    val current = mutable.value
                    current.copy(issuing = false, issued = result.value,
                        certificates = if (current.certificates.any { it.id == result.value.certificate.id }) current.certificates else listOf(result.value.certificate) + current.certificates)
                }
                is ReportResult.Failure -> mutable.value.copy(issuing = false, issueFailure = result.reason, issueFields = result.fields)
            }
        }
    }

    private fun formatThreshold(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toInt().toString() else value.toString()
}
