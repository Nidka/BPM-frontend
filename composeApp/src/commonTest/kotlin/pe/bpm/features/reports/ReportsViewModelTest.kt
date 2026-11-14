package pe.bpm.features.reports

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.reports.domain.*
import pe.bpm.features.reports.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {
    private suspend fun <T : androidx.lifecycle.ViewModel> TestScope.with(make: () -> T, block: suspend (T) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = make()
        try { block(model) } finally { model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private fun reports(repo: FakeReportsRepository) = ReportsViewModel(LoadCompliance(repo), LoadSummary(repo), ExportComplianceCsv(repo)) { NOW_MILLIS }
    private fun admin(repo: FakeReportsRepository) = ReportsAdminViewModel(LoadTrainingCourseProgress(repo), LoadTrainingPeopleProgress(repo),
        LoadReportSettings(repo), SaveReportSettings(repo), IssueComplianceCertificate(repo), ListComplianceCertificates(repo)) { NOW_MILLIS }

    // ---- Panel -------------------------------------------------------------------------------

    @Test fun dashboardLoadsKpisAndKeepsTrainingForAdmin() = runTest {
        val repo = FakeReportsRepository()
        with({ DashboardViewModel(LoadKpis(repo)) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            assertNotNull(model.state.value.kpis?.training)
            assertEquals(90.0, model.state.value.kpis?.today?.average)
            assertNull(model.state.value.kpis?.last30Days?.average)
        }
    }

    @Test fun chefWithoutTrainingBlockHidesIt() = runTest {
        val repo = FakeReportsRepository().apply { kpisResult = ReportResult.Success(sampleKpis(training = null)) }
        with({ DashboardViewModel(LoadKpis(repo)) }) { model ->
            model.open("u", "org", "CHEF"); advanceUntilIdle()
            assertNotNull(model.state.value.kpis)
            assertNull(model.state.value.kpis?.training)
        }
    }

    @Test fun workerNeverReachesTheRepository() = runTest {
        val repo = FakeReportsRepository()
        with({ DashboardViewModel(LoadKpis(repo)) }) { model ->
            model.open("u", "org", "WORKER"); advanceUntilIdle()
            assertEquals(ReportFailure.DENIED, model.state.value.failure)
            assertTrue(repo.calls.isEmpty())
        }
    }

    @Test fun dashboardFailureIsRetryableAndResetClearsData() = runTest {
        val repo = FakeReportsRepository().apply { kpisResult = ReportResult.Failure(ReportFailure.CONNECTION) }
        with({ DashboardViewModel(LoadKpis(repo)) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            assertEquals(ReportFailure.CONNECTION, model.state.value.failure)
            repo.kpisResult = ReportResult.Success(sampleKpis()); model.refresh(); advanceUntilIdle()
            assertNull(model.state.value.failure); assertNotNull(model.state.value.kpis)
            model.reset(); assertNull(model.state.value.kpis)
        }
    }

    // ---- Cumplimiento ------------------------------------------------------------------------

    @Test fun openingLoadsLastThirtyDays() = runTest {
        val repo = FakeReportsRepository()
        with({ reports(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            assertEquals("2026-10-12", model.state.value.from)
            assertEquals("2026-11-10", model.state.value.to)
            assertEquals(listOf("compliance:2026-10-12:2026-11-10:null"), repo.calls)
            assertNotNull(model.state.value.compliance)
        }
    }

    @Test fun invalidPeriodIsReportedWithoutRequesting() = runTest {
        val repo = FakeReportsRepository()
        with({ reports(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle(); repo.calls.clear()
            model.editFrom("2026-01-01"); model.editTo("2026-12-31"); model.load(); advanceUntilIdle()
            assertEquals(PeriodError.TOO_LONG, model.state.value.periodError)
            model.editFrom("hoy"); model.load(); advanceUntilIdle()
            assertEquals(PeriodError.INVALID, model.state.value.periodError)
            assertTrue(repo.calls.isEmpty())
        }
    }

    @Test fun summaryTabAllowsUpToOneYearButComplianceDoesNot() = runTest {
        val repo = FakeReportsRepository()
        with({ reports(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle(); repo.calls.clear()
            model.editFrom("2026-01-01"); model.editTo("2026-12-31")
            model.load(); advanceUntilIdle()
            assertEquals(PeriodError.TOO_LONG, model.state.value.periodError)
            model.selectTab(ReportTab.SUMMARY); advanceUntilIdle()
            assertNull(model.state.value.periodError)
            assertEquals(listOf("summary:2026-01-01:2026-12-31:null"), repo.calls)
            assertEquals(ReportFailure.UNAVAILABLE, model.state.value.failure)
        }
    }

    @Test fun presetsAndLocationReloadWithTheFilter() = runTest {
        val repo = FakeReportsRepository()
        with({ reports(repo) }) { model ->
            model.open("u", "org", "CHEF"); advanceUntilIdle(); repo.calls.clear()
            model.selectPreset(7); advanceUntilIdle()
            model.selectLocation("loc-1"); advanceUntilIdle()
            assertEquals(listOf("compliance:2026-11-04:2026-11-10:null", "compliance:2026-11-04:2026-11-10:loc-1"), repo.calls)
        }
    }

    @Test fun staleResponseAfterResetIsDiscarded() = runTest {
        val repo = FakeReportsRepository()
        with({ reports(repo) }) { model ->
            model.open("u", "org", "ADMIN"); model.reset(); advanceUntilIdle()
            assertNull(model.state.value.compliance)
            assertNull(model.state.value.organizationId)
        }
    }

    // ---- Exportación -------------------------------------------------------------------------

    @Test fun exportPublishesTheFileUntilThePlatformHandlesIt() = runTest {
        val repo = FakeReportsRepository()
        with({ reports(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            model.export(); advanceUntilIdle()
            assertNotNull(model.state.value.exportReady)
            model.exportHandled(true)
            assertNull(model.state.value.exportReady)
            assertTrue(model.state.value.exportNotice!!.contains("listo"))
        }
    }

    @Test fun exportTooLargeIsReported() = runTest {
        val repo = FakeReportsRepository().apply { exportResult = ReportResult.Failure(ReportFailure.EXPORT_TOO_LARGE) }
        with({ reports(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            model.export(); advanceUntilIdle()
            assertEquals(ReportFailure.EXPORT_TOO_LARGE, model.state.value.exportFailure)
            assertNull(model.state.value.exportReady)
            assertFalse(model.state.value.exporting)
            assertTrue(reportFailureMessage(ReportFailure.EXPORT_TOO_LARGE).contains("sede"))
        }
    }

    // ---- Administración ----------------------------------------------------------------------

    @Test fun adminLoadsTrainingByCourseAndPaginatesPeople() = runTest {
        val repo = FakeReportsRepository().apply {
            coursesResult = ReportResult.Success(listOf(CourseProgressRow("c1", "M-01", "Higiene", 10, 7, 3, 4, 40.0, 82.5)))
            peopleResult = ReportResult.Success(PersonProgressPage(listOf(PersonProgressRow("p1", "Ana", "WORKER", 1, 1, 0, 90.0, null)), "next"))
        }
        with({ admin(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            assertEquals(1, model.state.value.courses?.size)
            assertEquals(1, model.state.value.people.size)
            repo.peopleResult = ReportResult.Success(PersonProgressPage(listOf(PersonProgressRow("p2", "Luis", "CHEF", 1, 0, 1, null, "2026-11-01T10:00:00Z")), ""))
            model.loadMorePeople(); advanceUntilIdle()
            assertEquals(listOf("p1", "p2"), model.state.value.people.map { it.userId })
            model.filterCourse("c1"); advanceUntilIdle()
            assertEquals(listOf("p2"), model.state.value.people.map { it.userId })
            assertTrue(repo.calls.contains("people:c1:null"))
        }
    }

    @Test fun chefCannotUseTheAdminViewModel() = runTest {
        val repo = FakeReportsRepository()
        with({ admin(repo) }) { model ->
            model.open("u", "org", "CHEF"); advanceUntilIdle()
            assertEquals(ReportFailure.DENIED, model.state.value.coursesFailure)
            assertTrue(repo.calls.isEmpty())
        }
    }

    @Test fun settingsEditAndSave() = runTest {
        val repo = FakeReportsRepository()
        with({ admin(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            model.selectTab(AdminTab.SETTINGS); advanceUntilIdle()
            assertEquals("80", model.state.value.thresholdText)
            assertFalse(model.state.value.settingsDirty)
            model.editThreshold("abc"); model.saveSettingsNow(); advanceUntilIdle()
            assertTrue(model.state.value.thresholdInvalid)
            model.editThreshold("85,5"); model.toggleWeekly(false)
            assertTrue(model.state.value.settingsDirty)
            model.saveSettingsNow(); advanceUntilIdle()
            assertTrue(model.state.value.settingsSaved)
            assertTrue(repo.calls.contains("save:85.5:false"))
        }
    }

    @Test fun certificateBelowThresholdShowsAverageAndThreshold() = runTest {
        val repo = FakeReportsRepository().apply {
            issueResult = ReportResult.Failure(ReportFailure.THRESHOLD_NOT_MET, mapOf("averageCompliance" to "70.00", "threshold" to "80.00"))
        }
        with({ admin(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            model.issue(); advanceUntilIdle()
            assertEquals(ReportFailure.THRESHOLD_NOT_MET, model.state.value.issueFailure)
            assertNull(model.state.value.issued)
            val message = reportFailureMessage(model.state.value.issueFailure!!, model.state.value.issueFields)
            assertTrue(message.contains("70.00") && message.contains("80.00"))
        }
    }

    @Test fun certificateDefaultsToPreviousMonthAndListsTheNewOne() = runTest {
        val repo = FakeReportsRepository().apply { issueResult = ReportResult.Success(IssuedCertificate(sampleCertificate(), created = true)) }
        with({ admin(repo) }) { model ->
            model.open("u", "org", "ADMIN"); advanceUntilIdle()
            assertEquals("2026-10-01", model.state.value.certFrom)
            assertEquals("2026-10-31", model.state.value.certTo)
            model.issue(); advanceUntilIdle()
            assertEquals("cert-1", model.state.value.certificates.single().id)
            assertTrue(model.state.value.issued!!.created)
            model.editCertTo("2026-11-10"); model.issue(); advanceUntilIdle()
            assertEquals(ReportFailure.INVALID_PERIOD, model.state.value.issueFailure)
        }
    }

    @Test fun verificationShowsCertificateOrNotFound() = runTest {
        val repo = FakeReportsRepository()
        with({ CertificateVerificationViewModel(VerifyComplianceCertificate(repo)) }) { model ->
            model.check("ABCDEFGHIJKLMNOPQRSTUVWX"); advanceUntilIdle()
            assertEquals(ReportFailure.CERTIFICATE_NOT_FOUND, model.state.value.failure)
            repo.verifyResult = ReportResult.Success(PublicCertificate("Cocina Sur", "2026-10-01", "2026-10-31", 85.0, 80.0, "2026-11-10T15:00:00Z", "ABCDEFGHIJKLMNOPQRSTUVWX"))
            model.retry(); advanceUntilIdle()
            assertEquals("Cocina Sur", model.state.value.certificate?.organizationName)
            assertNull(model.state.value.failure)
        }
    }
}
