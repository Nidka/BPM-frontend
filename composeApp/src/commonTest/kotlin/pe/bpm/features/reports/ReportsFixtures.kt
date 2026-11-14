package pe.bpm.features.reports

import pe.bpm.features.reports.domain.*

/** Dobles de repositorio: guardan las llamadas y devuelven resultados configurables. */
class FakeReportsRepository : ReportsReadRepository, ReportsAdminRepository, CertificateVerificationRepository {
    var kpisResult: ReportResult<Kpis> = ReportResult.Success(sampleKpis())
    var complianceResult: ReportResult<ComplianceReport> = ReportResult.Success(sampleCompliance())
    var summaryResult: ReportResult<SummaryReport> = ReportResult.Failure(ReportFailure.UNAVAILABLE)
    var exportResult: ReportResult<CsvExport> = ReportResult.Success(CsvExport("checklists_a_b.csv", "runId\n"))
    var coursesResult: ReportResult<List<CourseProgressRow>> = ReportResult.Success(emptyList())
    var peopleResult: ReportResult<PersonProgressPage> = ReportResult.Success(PersonProgressPage(emptyList(), ""))
    var settingsResult: ReportResult<ReportSettings> = ReportResult.Success(ReportSettings(true, 80.0))
    var issueResult: ReportResult<IssuedCertificate> = ReportResult.Failure(ReportFailure.NO_DATA)
    var certificatesResult: ReportResult<CertificatePage> = ReportResult.Success(CertificatePage(emptyList(), ""))
    var verifyResult: ReportResult<PublicCertificate> = ReportResult.Failure(ReportFailure.CERTIFICATE_NOT_FOUND)

    val calls = mutableListOf<String>()

    override suspend fun kpis(org: String) = kpisResult.also { calls += "kpis:$org" }
    override suspend fun compliance(org: String, from: String, to: String, locationId: String?) = complianceResult.also { calls += "compliance:$from:$to:$locationId" }
    override suspend fun summary(org: String, from: String, to: String, locationId: String?) = summaryResult.also { calls += "summary:$from:$to:$locationId" }
    override suspend fun exportCsv(org: String, from: String, to: String, locationId: String?) = exportResult.also { calls += "export:$from:$to:$locationId" }
    override suspend fun trainingCourses(org: String) = coursesResult.also { calls += "courses" }
    override suspend fun trainingPeople(org: String, courseId: String?, pageToken: String?) = peopleResult.also { calls += "people:$courseId:$pageToken" }
    override suspend fun settings(org: String) = settingsResult.also { calls += "settings" }
    override suspend fun saveSettings(org: String, settings: ReportSettings): ReportResult<ReportSettings> { calls += "save:${settings.complianceThreshold}:${settings.weeklyEmailEnabled}"; return ReportResult.Success(settings) }
    override suspend fun issueCertificate(org: String, from: String, to: String) = issueResult.also { calls += "issue:$from:$to" }
    override suspend fun certificates(org: String, pageToken: String?) = certificatesResult.also { calls += "certificates:$pageToken" }
    override suspend fun verify(code: String) = verifyResult.also { calls += "verify:$code" }
}

fun sampleKpis(training: TrainingKpis? = TrainingKpis(2, 10, 6, 3, 30.0, 82.5)) = Kpis(
    "2026-11-10T15:00:00Z", ComplianceWindow(90.0, 2), ComplianceWindow(88.5, 10), ComplianceWindow(null, 0),
    PendingChecks(4, 1, 1), 3, 2, training,
)

fun sampleCompliance() = ComplianceReport("2026-10-12", "2026-11-10", 12, 86.67,
    listOf(DayCompliance("2026-11-02", 2, 90.0)),
    listOf(LocationCompliance("loc-1", "Sede Centro", 7, 88.1, listOf(DayCompliance("2026-11-02", 1, 90.0)))))

fun sampleCertificate(id: String = "cert-1") = ComplianceCertificate(id, "2026-10-01", "2026-10-31", 85.0, 80.0, 120, "ABCDEFGHIJKLMNOPQRSTUVWX", "2026-11-10T15:00:00Z")

/** 2026-11-10 15:00 UTC (10:00 en Lima). */
const val NOW_MILLIS = 1_794_322_800_000L
