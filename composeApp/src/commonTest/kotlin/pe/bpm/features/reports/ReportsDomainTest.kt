package pe.bpm.features.reports

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pe.bpm.features.reports.domain.*

class ReportsDomainTest {
    @Test fun datesRoundTripAndRejectImpossibleDays() {
        assertEquals("2026-11-10", ReportDates.format(ReportDates.parse("2026-11-10")!!))
        assertEquals("2024-02-29", ReportDates.format(ReportDates.parse("2024-02-29")!!))
        assertNull(ReportDates.parse("2026-02-29"))
        assertNull(ReportDates.parse("2026-13-01"))
        assertNull(ReportDates.parse("2026-1-1"))
        assertEquals(1L, ReportDates.parse("1970-01-02"))
    }

    @Test fun todayUsesLimaOffset() {
        // 02:00 UTC del 11 aún es 21:00 del 10 en Lima.
        val utcNight = ReportDates.parse("2026-11-11")!! * 86_400_000L + 2 * 3_600_000L
        assertEquals("2026-11-10", ReportDates.format(ReportDates.today(utcNight)))
        assertEquals("2026-11-10", ReportDates.format(ReportDates.today(NOW_MILLIS)))
    }

    @Test fun periodRulesEnforceLimits() {
        val c = ReportPeriodRules.COMPLIANCE_MAX_DAYS
        assertNull(ReportPeriodRules.validate("2026-11-01", "2026-11-30", c))
        assertNull(ReportPeriodRules.validate("2026-09-01", "2026-12-01", c)) // 92 días exactos
        assertEquals(PeriodError.TOO_LONG, ReportPeriodRules.validate("2026-09-01", "2026-12-02", c))
        assertEquals(PeriodError.ORDER, ReportPeriodRules.validate("2026-11-30", "2026-11-01", c))
        assertEquals(PeriodError.INVALID, ReportPeriodRules.validate("30/11/2026", "2026-11-01", c))
        assertEquals(PeriodError.MISSING, ReportPeriodRules.validate("", "2026-11-01", c))
        assertNull(ReportPeriodRules.validate("2026-01-01", "2026-12-31", ReportPeriodRules.SUMMARY_MAX_DAYS)) // 365 días
        assertEquals(PeriodError.TOO_LONG, ReportPeriodRules.validate("2025-01-01", "2026-01-02", ReportPeriodRules.SUMMARY_MAX_DAYS))
    }

    @Test fun lastDaysIsInclusive() {
        val today = ReportDates.parse("2026-11-10")!!
        assertEquals("2026-11-04" to "2026-11-10", ReportPeriodRules.lastDays(today, 7))
    }

    @Test fun complianceUseCaseRejectsInvalidPeriodAndWorkerWithoutCallingTheRepository() = runTest {
        val repo = FakeReportsRepository()
        val load = LoadCompliance(repo)
        assertEquals(ReportFailure.INVALID_PERIOD, (load("org", "ADMIN", "2026-01-01", "2026-12-31", null) as ReportResult.Failure).reason)
        assertEquals(ReportFailure.DENIED, (load("org", "WORKER", "2026-11-01", "2026-11-30", null) as ReportResult.Failure).reason)
        assertTrue(repo.calls.isEmpty())
        assertIs<ReportResult.Success<*>>(load("org", "CHEF", " 2026-11-01", "2026-11-30 ", "loc"))
        assertEquals(listOf("compliance:2026-11-01:2026-11-30:loc"), repo.calls)
    }

    @Test fun summaryAndExportAllowUpToOneYear() = runTest {
        val repo = FakeReportsRepository()
        ExportComplianceCsv(repo)("org", "ADMIN", "2026-01-01", "2026-12-31", null)
        assertEquals(ReportFailure.INVALID_PERIOD, (LoadSummary(repo)("org", "ADMIN", "2025-01-01", "2026-01-02", null) as ReportResult.Failure).reason)
        assertEquals(listOf("export:2026-01-01:2026-12-31:null"), repo.calls)
    }

    @Test fun peopleAndSettingsAreAdminOnly() = runTest {
        val repo = FakeReportsRepository()
        for (role in listOf("CHEF", "WORKER", null)) {
            assertEquals(ReportFailure.DENIED, (LoadTrainingPeopleProgress(repo)("org", role, null, null) as ReportResult.Failure).reason)
            assertEquals(ReportFailure.DENIED, (LoadTrainingCourseProgress(repo)("org", role) as ReportResult.Failure).reason)
            assertEquals(ReportFailure.DENIED, (LoadReportSettings(repo)("org", role) as ReportResult.Failure).reason)
            assertEquals(ReportFailure.DENIED, (ListComplianceCertificates(repo)("org", role, null) as ReportResult.Failure).reason)
        }
        assertTrue(repo.calls.isEmpty())
        assertIs<ReportResult.Success<*>>(LoadTrainingPeopleProgress(repo)("org", "SUPER_ADMIN", null, null))
    }

    @Test fun thresholdRulesAcceptOneToHundredWithTwoDecimals() {
        assertTrue(ReportSettingsRules.validThreshold(1.0))
        assertTrue(ReportSettingsRules.validThreshold(85.5))
        assertTrue(ReportSettingsRules.validThreshold(99.99))
        assertFalse(ReportSettingsRules.validThreshold(0.99))
        assertFalse(ReportSettingsRules.validThreshold(100.01))
        assertFalse(ReportSettingsRules.validThreshold(85.123))
        assertFalse(ReportSettingsRules.validThreshold(Double.NaN))
        assertEquals(85.5, ReportSettingsRules.parseThreshold(" 85,5 "))
        assertNull(ReportSettingsRules.parseThreshold("abc"))
    }

    @Test fun saveSettingsValidatesBeforeCalling() = runTest {
        val repo = FakeReportsRepository()
        assertEquals(ReportFailure.INVALID, (SaveReportSettings(repo)("org", "ADMIN", ReportSettings(true, 0.0)) as ReportResult.Failure).reason)
        assertTrue(repo.calls.isEmpty())
        SaveReportSettings(repo)("org", "ADMIN", ReportSettings(false, 85.5))
        assertEquals(listOf("save:85.5:false"), repo.calls)
    }

    @Test fun certificateRequiresAFinishedPeriod() = runTest {
        val repo = FakeReportsRepository()
        val today = ReportDates.parse("2026-11-10")!!
        val issue = IssueComplianceCertificate(repo)
        assertEquals(ReportFailure.INVALID_PERIOD, (issue("org", "ADMIN", "2026-11-01", "2026-11-10", today) as ReportResult.Failure).reason)
        assertEquals(ReportFailure.DENIED, (issue("org", "CHEF", "2026-10-01", "2026-10-31", today) as ReportResult.Failure).reason)
        assertTrue(repo.calls.isEmpty())
        issue("org", "ADMIN", "2026-10-01", "2026-10-31", today)
        assertEquals(listOf("issue:2026-10-01:2026-10-31"), repo.calls)
    }

    @Test fun publicVerificationNormalizesAndRejectsMalformedCodes() = runTest {
        val repo = FakeReportsRepository()
        val verify = VerifyComplianceCertificate(repo)
        assertEquals(ReportFailure.CERTIFICATE_NOT_FOUND, (verify("abc") as ReportResult.Failure).reason)
        assertEquals(ReportFailure.CERTIFICATE_NOT_FOUND, (verify("ABCDEFGHIJKLMNOPQRSTUVW1") as ReportResult.Failure).reason)
        assertTrue(repo.calls.isEmpty())
        verify(" abcdefghijklmnopqrstuvwx ")
        assertEquals(listOf("verify:ABCDEFGHIJKLMNOPQRSTUVWX"), repo.calls)
    }
}
