package pe.bpm.features.reports.data

import kotlinx.serialization.Serializable
import pe.bpm.features.reports.domain.*

@Serializable internal data class Envelope<T>(val data: T)
@Serializable internal data class PagedEnvelope<T>(val data: List<T> = emptyList(), val pagination: PaginationDto = PaginationDto())
@Serializable internal data class PaginationDto(val nextPageToken: String = "")
@Serializable internal data class ErrorsEnvelope(val errors: List<ErrorDto> = emptyList())
@Serializable internal data class ErrorDto(val code: String = "", val fields: Map<String, String> = emptyMap())

private fun percent(value: Double?): Double? {
    require(value == null || (value in 0.0..100.0)) { "porcentaje fuera de rango" }
    return value
}

private fun count(value: Int): Int { require(value >= 0); return value }

@Serializable internal data class DayDto(val date: String, val runs: Int = 0, val averageCompliance: Double? = null) {
    fun domain(): DayCompliance {
        require(pe.bpm.features.reports.domain.ReportDates.parse(date) != null)
        return DayCompliance(date, count(runs), percent(averageCompliance))
    }
}

@Serializable internal data class LocationComplianceDto(
    val locationId: String, val locationName: String = "", val runs: Int = 0, val averageCompliance: Double? = null,
    val days: List<DayDto> = emptyList(),
) {
    fun domain() = LocationCompliance(locationId, locationName, count(runs), percent(averageCompliance), days.map { it.domain() })
}

@Serializable internal data class ComplianceDto(
    val from: String, val to: String, val runs: Int = 0, val averageCompliance: Double? = null,
    val days: List<DayDto> = emptyList(), val locations: List<LocationComplianceDto> = emptyList(),
) {
    fun domain(): ComplianceReport {
        require(days.map { it.date }.distinct().size == days.size)
        return ComplianceReport(from, to, count(runs), percent(averageCompliance), days.map { it.domain() }, locations.map { it.domain() })
    }
}

@Serializable internal data class LocationSummaryDto(
    val locationId: String, val locationName: String = "", val runs: Int = 0, val averageCompliance: Double? = null,
    val incidentsReported: Int = 0, val incidentsClosed: Int = 0, val incidentsOpenNow: Int = 0,
    val temperatureMeasurements: Int = 0, val temperatureOutOfRange: Int = 0,
) {
    fun domain() = LocationSummary(locationId, locationName, count(runs), percent(averageCompliance), count(incidentsReported),
        count(incidentsClosed), count(incidentsOpenNow), count(temperatureMeasurements), count(temperatureOutOfRange))
}

@Serializable internal data class AreaDto(val area: String = "", val runs: Int = 0, val averageCompliance: Double? = null)
@Serializable internal data class TemplateDto(val templateId: String, val name: String = "", val area: String = "", val runs: Int = 0, val averageCompliance: Double? = null)
@Serializable internal data class IncidentTotalsDto(val reported: Int = 0, val closed: Int = 0, val openNow: Int = 0)
@Serializable internal data class TemperatureTotalsDto(val measurements: Int = 0, val outOfRange: Int = 0)

@Serializable internal data class SummaryDto(
    val from: String, val to: String, val runs: Int = 0, val averageCompliance: Double? = null,
    val locations: List<LocationSummaryDto> = emptyList(), val areas: List<AreaDto> = emptyList(),
    val templates: List<TemplateDto> = emptyList(), val incidents: IncidentTotalsDto = IncidentTotalsDto(),
    val temperatures: TemperatureTotalsDto = TemperatureTotalsDto(),
) {
    fun domain() = SummaryReport(from, to, count(runs), percent(averageCompliance), locations.map { it.domain() },
        areas.map { AreaSummary(it.area, count(it.runs), percent(it.averageCompliance)) },
        templates.map { TemplateSummary(it.templateId, it.name, it.area, count(it.runs), percent(it.averageCompliance)) },
        IncidentTotals(count(incidents.reported), count(incidents.closed), count(incidents.openNow)),
        TemperatureTotals(count(temperatures.measurements), count(temperatures.outOfRange)))
}

@Serializable internal data class WindowDto(val averageCompliance: Double? = null, val runs: Int = 0) {
    fun domain() = ComplianceWindow(percent(averageCompliance), count(runs))
}
@Serializable internal data class KpiComplianceDto(val today: WindowDto = WindowDto(), val last7Days: WindowDto = WindowDto(), val last30Days: WindowDto = WindowDto())
@Serializable internal data class PendingDto(val assigned: Int = 0, val pending: Int = 0, val overdue: Int = 0)
@Serializable internal data class TrainingKpisDto(
    val publishedCourses: Int = 0, val learners: Int = 0, val completedEnrollments: Int = 0, val inProgressEnrollments: Int = 0,
    val completionPercent: Double? = null, val averageBestScore: Double? = null,
)

@Serializable internal data class KpisDto(
    val generatedAt: String = "", val compliance: KpiComplianceDto = KpiComplianceDto(), val pendingChecks: PendingDto = PendingDto(),
    val openAlerts: Int = 0, val openIncidents: Int = 0, val training: TrainingKpisDto? = null,
) {
    fun domain() = Kpis(generatedAt, compliance.today.domain(), compliance.last7Days.domain(), compliance.last30Days.domain(),
        PendingChecks(count(pendingChecks.assigned), count(pendingChecks.pending), count(pendingChecks.overdue)),
        count(openAlerts), count(openIncidents),
        training?.let { TrainingKpis(count(it.publishedCourses), count(it.learners), count(it.completedEnrollments), count(it.inProgressEnrollments),
            percent(it.completionPercent), percent(it.averageBestScore)) })
}

@Serializable internal data class CourseProgressDto(
    val courseId: String, val code: String = "", val title: String = "", val assignable: Int = 0, val enrolled: Int = 0,
    val inProgress: Int = 0, val completed: Int = 0, val completionPercent: Double? = null, val averageBestScore: Double? = null,
) {
    fun domain() = CourseProgressRow(courseId, code, title, count(assignable), count(enrolled), count(inProgress), count(completed),
        percent(completionPercent), percent(averageBestScore))
}

@Serializable internal data class PersonProgressDto(
    val userId: String, val displayName: String = "", val role: String = "", val publishedCourses: Int = 0,
    val completed: Int = 0, val inProgress: Int = 0, val averageBestScore: Double? = null, val lastActivityAt: String? = null,
) {
    fun domain() = PersonProgressRow(userId, displayName, role, count(publishedCourses), count(completed), count(inProgress),
        percent(averageBestScore), lastActivityAt)
}

@Serializable internal data class SettingsDto(val weeklyEmailEnabled: Boolean, val complianceThreshold: Double) {
    fun domain(): ReportSettings {
        require(ReportSettingsRules.validThreshold(complianceThreshold))
        return ReportSettings(weeklyEmailEnabled, complianceThreshold)
    }
}

@Serializable internal data class CertificateDto(
    val id: String, val periodStart: String, val periodEnd: String, val averageCompliance: Double, val threshold: Double,
    val runs: Int = 0, val verificationCode: String, val issuedAt: String,
) {
    fun domain(): ComplianceCertificate {
        require(ReportDates.parse(periodStart) != null && ReportDates.parse(periodEnd) != null)
        require(Regex("[A-Z2-7]{24}").matches(verificationCode))
        return ComplianceCertificate(id, periodStart, periodEnd, percent(averageCompliance)!!, percent(threshold)!!, count(runs), verificationCode, issuedAt)
    }
}

@Serializable internal data class PublicCertificateDto(
    val valid: Boolean = false, val organizationName: String = "", val periodStart: String, val periodEnd: String,
    val averageCompliance: Double, val threshold: Double, val issuedAt: String, val verificationCode: String,
) {
    fun domain(): PublicCertificate {
        require(valid && ReportDates.parse(periodStart) != null && ReportDates.parse(periodEnd) != null)
        return PublicCertificate(organizationName, periodStart, periodEnd, percent(averageCompliance)!!, percent(threshold)!!, issuedAt, verificationCode)
    }
}
