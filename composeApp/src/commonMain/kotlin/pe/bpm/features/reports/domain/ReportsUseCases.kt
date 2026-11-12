package pe.bpm.features.reports.domain

/** Política de presentación por rol; el backend autoriza cada operación. */
object ReportAccess {
    fun canView(role: String?) = role in setOf("ADMIN", "CHEF", "SUPER_ADMIN")
    fun canAdminister(role: String?) = role in setOf("ADMIN", "SUPER_ADMIN")
}

private val denied = ReportResult.Failure(ReportFailure.DENIED)
private val invalidPeriod = ReportResult.Failure(ReportFailure.INVALID_PERIOD)

class LoadKpis(private val repository: ReportsReadRepository) {
    suspend operator fun invoke(org: String, role: String?): ReportResult<Kpis> {
        if (!ReportAccess.canView(role)) return denied
        return repository.kpis(org)
    }
}

class LoadCompliance(private val repository: ReportsReadRepository) {
    suspend operator fun invoke(org: String, role: String?, from: String, to: String, locationId: String?): ReportResult<ComplianceReport> {
        if (!ReportAccess.canView(role)) return denied
        if (ReportPeriodRules.validate(from, to, ReportPeriodRules.COMPLIANCE_MAX_DAYS) != null) return invalidPeriod
        return repository.compliance(org, from.trim(), to.trim(), locationId)
    }
}

class LoadSummary(private val repository: ReportsReadRepository) {
    suspend operator fun invoke(org: String, role: String?, from: String, to: String, locationId: String?): ReportResult<SummaryReport> {
        if (!ReportAccess.canView(role)) return denied
        if (ReportPeriodRules.validate(from, to, ReportPeriodRules.SUMMARY_MAX_DAYS) != null) return invalidPeriod
        return repository.summary(org, from.trim(), to.trim(), locationId)
    }
}

/** Exporta el historial; no incluye personas y se limita a 366 días. */
class ExportComplianceCsv(private val repository: ReportsReadRepository) {
    suspend operator fun invoke(org: String, role: String?, from: String, to: String, locationId: String?): ReportResult<CsvExport> {
        if (!ReportAccess.canView(role)) return denied
        if (ReportPeriodRules.validate(from, to, ReportPeriodRules.SUMMARY_MAX_DAYS) != null) return invalidPeriod
        return repository.exportCsv(org, from.trim(), to.trim(), locationId)
    }
}

class LoadTrainingCourseProgress(private val repository: ReportsAdminRepository) {
    suspend operator fun invoke(org: String, role: String?): ReportResult<List<CourseProgressRow>> {
        if (!ReportAccess.canAdminister(role)) return denied
        return repository.trainingCourses(org)
    }
}

class LoadTrainingPeopleProgress(private val repository: ReportsAdminRepository) {
    suspend operator fun invoke(org: String, role: String?, courseId: String?, pageToken: String?): ReportResult<PersonProgressPage> {
        if (!ReportAccess.canAdminister(role)) return denied
        return repository.trainingPeople(org, courseId, pageToken)
    }
}

class LoadReportSettings(private val repository: ReportsAdminRepository) {
    suspend operator fun invoke(org: String, role: String?): ReportResult<ReportSettings> {
        if (!ReportAccess.canAdminister(role)) return denied
        return repository.settings(org)
    }
}

object ReportSettingsRules {
    /** Umbral de 1 a 100 con hasta 2 decimales. */
    fun validThreshold(value: Double): Boolean {
        if (value.isNaN() || value < 1.0 || value > 100.0) return false
        val scaled = value * 100
        return kotlin.math.abs(scaled - kotlin.math.round(scaled)) < 1e-6
    }

    /** Interpreta el texto escrito por la persona (acepta coma decimal). */
    fun parseThreshold(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { validThreshold(it) }
}

class SaveReportSettings(private val repository: ReportsAdminRepository) {
    suspend operator fun invoke(org: String, role: String?, settings: ReportSettings): ReportResult<ReportSettings> {
        if (!ReportAccess.canAdminister(role)) return denied
        if (!ReportSettingsRules.validThreshold(settings.complianceThreshold)) return ReportResult.Failure(ReportFailure.INVALID)
        return repository.saveSettings(org, settings)
    }
}

/** Solicita la constancia de un período ya terminado (`to` anterior a [today], día local de Lima). */
class IssueComplianceCertificate(private val repository: ReportsAdminRepository) {
    suspend operator fun invoke(org: String, role: String?, from: String, to: String, today: Long): ReportResult<IssuedCertificate> {
        if (!ReportAccess.canAdminister(role)) return denied
        if (ReportPeriodRules.validate(from, to, ReportPeriodRules.SUMMARY_MAX_DAYS) != null) return invalidPeriod
        val end = ReportDates.parse(to.trim())!!
        if (end >= today) return invalidPeriod
        return repository.issueCertificate(org, from.trim(), to.trim())
    }
}

class ListComplianceCertificates(private val repository: ReportsAdminRepository) {
    suspend operator fun invoke(org: String, role: String?, pageToken: String?): ReportResult<CertificatePage> {
        if (!ReportAccess.canAdminister(role)) return denied
        return repository.certificates(org, pageToken)
    }
}

class VerifyComplianceCertificate(private val repository: CertificateVerificationRepository) {
    suspend operator fun invoke(rawCode: String): ReportResult<PublicCertificate> {
        val code = rawCode.trim().uppercase()
        if (!Regex("[A-Z2-7]{24}").matches(code)) return ReportResult.Failure(ReportFailure.CERTIFICATE_NOT_FOUND)
        return repository.verify(code)
    }
}
