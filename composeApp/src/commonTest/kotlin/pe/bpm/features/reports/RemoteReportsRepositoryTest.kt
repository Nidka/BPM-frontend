package pe.bpm.features.reports

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.reports.data.RemoteReportsRepository
import pe.bpm.features.reports.domain.*

class RemoteReportsRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val csv = headersOf(HttpHeaders.ContentType, "text/csv; charset=utf-8")
    private val config = AppConfig("https://api.example.com")
    private val base = "/api/v1/organizations/org"
    private val header = "ID de ejecución,Fecha local,Enviado el,ID de sede,Sede,Control,Área,Versión de la plantilla,Cumplimiento (%)"

    private fun repo(engine: MockEngine, token: String? = "token") = RemoteReportsRepository(createHttpClient(engine), config, token = { token })

    @Test fun kpisTranslateNullTrainingForChefAndNullAverages() = runTest {
        val body = """{"data":{"generatedAt":"2026-11-10T15:00:00Z",
            "compliance":{"today":{"averageCompliance":90.0,"runs":2},"last7Days":{"averageCompliance":88.5,"runs":10},"last30Days":{"averageCompliance":null,"runs":0}},
            "pendingChecks":{"assigned":4,"pending":1,"overdue":1},"openAlerts":3,"openIncidents":2,"training":null,"futuro":1}}"""
        val result = repo(MockEngine { request ->
            assertEquals("$base/reports/kpis", request.url.encodedPath)
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            respond(body, HttpStatusCode.OK, json)
        }).kpis("org")
        val kpis = (result as ReportResult.Success).value
        assertNull(kpis.training)
        assertNull(kpis.last30Days.average)
        assertEquals(1, kpis.pendingChecks.overdue)
    }

    @Test fun kpisKeepTrainingForAdmin() = runTest {
        val body = """{"data":{"generatedAt":"x","compliance":{},"pendingChecks":{},"openAlerts":0,"openIncidents":0,
            "training":{"publishedCourses":2,"learners":10,"completedEnrollments":6,"inProgressEnrollments":3,"completionPercent":30.0,"averageBestScore":82.5}}}"""
        val kpis = (repo(MockEngine { respond(body, HttpStatusCode.OK, json) }).kpis("org") as ReportResult.Success).value
        assertEquals(30.0, kpis.training?.completionPercent)
    }

    @Test fun complianceSendsStrictQueryAndTranslates() = runTest {
        val body = """{"data":{"from":"2026-11-01","to":"2026-11-30","runs":12,"averageCompliance":86.67,
            "days":[{"date":"2026-11-02","runs":2,"averageCompliance":90.00}],
            "locations":[{"locationId":"l1","locationName":"Sede Centro","runs":7,"averageCompliance":88.1,"days":[{"date":"2026-11-02","runs":1,"averageCompliance":90.0}]}]}}"""
        val result = repo(MockEngine { request ->
            assertEquals("$base/reports/compliance", request.url.encodedPath)
            assertEquals("2026-11-01", request.url.parameters["from"])
            assertEquals("l1", request.url.parameters["locationId"])
            respond(body, HttpStatusCode.OK, json)
        }).compliance("org", "2026-11-01", "2026-11-30", "l1")
        val report = (result as ReportResult.Success).value
        assertEquals(86.67, report.average)
        assertEquals("Sede Centro", report.locations.single().locationName)
    }

    @Test fun outOfRangeOrMismatchedResponsesAreRejected() = runTest {
        for (body in listOf(
            """{"data":{"from":"2026-11-01","to":"2026-11-30","runs":1,"averageCompliance":140.0}}""",
            """{"data":{"from":"2026-10-01","to":"2026-11-30","runs":1}}""",
            """{"data":{"from":"2026-11-01","to":"2026-11-30","runs":-1}}""",
            "no json",
        )) {
            val result = repo(MockEngine { respond(body, HttpStatusCode.OK, json) }).compliance("org", "2026-11-01", "2026-11-30", null)
            assertEquals(ReportFailure.INVALID_RESPONSE, (result as ReportResult.Failure).reason, body)
        }
    }

    @Test fun errorCodesAreMappedAndServerMessagesDropped() = runTest {
        suspend fun fail(status: HttpStatusCode, code: String): ReportFailure {
            val body = """{"errors":[{"code":"$code","message":"detalle interno secreto"}]}"""
            val result = repo(MockEngine { respond(body, status, json) }).compliance("org", "2026-11-01", "2026-11-30", null)
            return (result as ReportResult.Failure).reason
        }
        assertEquals(ReportFailure.DENIED, fail(HttpStatusCode.Forbidden, "ACCESS_DENIED"))
        assertEquals(ReportFailure.NOT_FOUND, fail(HttpStatusCode.NotFound, "REPORT_LOCATION_NOT_FOUND"))
        assertEquals(ReportFailure.INVALID_PERIOD, fail(HttpStatusCode.UnprocessableEntity, "VALIDATION_ERROR"))
        assertEquals(ReportFailure.EXPORT_TOO_LARGE, fail(HttpStatusCode.UnprocessableEntity, "EXPORT_TOO_LARGE"))
        assertEquals(ReportFailure.INVALID, fail(HttpStatusCode.BadRequest, "INVALID_REQUEST"))
        assertEquals(ReportFailure.UNAVAILABLE, fail(HttpStatusCode.ServiceUnavailable, ""))
    }

    @Test fun missingSessionNeverCallsTheServer() = runTest {
        var called = false
        val result = repo(MockEngine { called = true; respond("{}", HttpStatusCode.OK, json) }, token = null).kpis("org")
        assertEquals(ReportFailure.SESSION, (result as ReportResult.Failure).reason)
        assertFalse(called)
    }

    @Test fun csvIsValidatedAndNamedLocally() = runTest {
        val text = "$header\r\nr1,01/11/2026,01/11/2026 05:00,l1,Sede,Higiene,COCINA,1,90.00\r\n".replace("\r\n", "\n")
        val result = repo(MockEngine { request ->
            assertEquals("$base/reports/compliance/export.csv", request.url.encodedPath)
            respond(text, HttpStatusCode.OK, csv)
        }).exportCsv("org", "2026-11-01", "2026-11-30", null)
        val export = (result as ReportResult.Success).value
        assertEquals("checklists_2026-11-01_2026-11-30.csv", export.fileName)
        // Se antepone la marca BOM para que Excel lea bien los acentos.
        assertEquals("\uFEFF" + text, export.text)
    }

    @Test fun pdfIsValidatedAndNamedLocally() = runTest {
        val pdf = "%PDF-1.4\n%%EOF\n"
        val ok = repo(MockEngine { request ->
            assertEquals("$base/reports/compliance/export.pdf", request.url.encodedPath)
            respond(pdf, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
        }).exportPdf("org", "2026-11-01", "2026-11-30", null)
        assertEquals("checklists_2026-11-01_2026-11-30.pdf", (ok as ReportResult.Success).value.fileName)
        val notPdf = repo(MockEngine { respond("<html></html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf")) }).exportPdf("org", "2026-11-01", "2026-11-30", null)
        assertEquals(ReportFailure.INCOMPLETE_DOWNLOAD, (notPdf as ReportResult.Failure).reason)
    }

    @Test fun truncatedOrWrongCsvIsRejected() = runTest {
        // Sin cierre de fila (cortado a mitad) y con otro contenido.
        for (body in listOf("$header\nr1,2026-11-0", "<html></html>\n")) {
            val result = repo(MockEngine { respond(body, HttpStatusCode.OK, csv) }).exportCsv("org", "2026-11-01", "2026-11-30", null)
            assertEquals(ReportFailure.INCOMPLETE_DOWNLOAD, (result as ReportResult.Failure).reason, body)
        }
        val notCsv = repo(MockEngine { respond("$header\n", HttpStatusCode.OK, json) }).exportCsv("org", "2026-11-01", "2026-11-30", null)
        assertEquals(ReportFailure.INVALID_RESPONSE, (notCsv as ReportResult.Failure).reason)
    }

    @Test fun exportTooLargeKeepsItsCode() = runTest {
        val result = repo(MockEngine { respond("""{"errors":[{"code":"EXPORT_TOO_LARGE"}]}""", HttpStatusCode.UnprocessableEntity, json) })
            .exportCsv("org", "2026-01-01", "2026-12-31", null)
        assertEquals(ReportFailure.EXPORT_TOO_LARGE, (result as ReportResult.Failure).reason)
    }

    @Test fun trainingPeoplePaginatesWithTheCursor() = runTest {
        val body = """{"data":[{"userId":"p1","displayName":"Ana","role":"WORKER","publishedCourses":2,"completed":1,"inProgress":1,"averageBestScore":90.0,"lastActivityAt":null}],
            "pagination":{"nextPageToken":"abc"}}"""
        val page = (repo(MockEngine { request ->
            assertEquals("$base/reports/training/people", request.url.encodedPath)
            assertEquals("25", request.url.parameters["pageSize"]); assertEquals("tok", request.url.parameters["pageToken"]); assertEquals("c1", request.url.parameters["courseId"])
            respond(body, HttpStatusCode.OK, json)
        }).trainingPeople("org", "c1", "tok") as ReportResult.Success).value
        assertEquals("abc", page.nextPageToken)
        assertNull(page.items.single().lastActivityAt)
    }

    @Test fun settingsRoundTripAndRejectInvalidThresholds() = runTest {
        val saved = (repo(MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            val sent = (request.body as io.ktor.http.content.TextContent).text
            assertTrue(sent.contains("\"weeklyEmailEnabled\":false") && sent.contains("85.5"))
            respond("""{"data":{"weeklyEmailEnabled":false,"complianceThreshold":85.5}}""", HttpStatusCode.OK, json)
        }).saveSettings("org", ReportSettings(false, 85.5)) as ReportResult.Success).value
        assertEquals(85.5, saved.complianceThreshold)
        val bad = repo(MockEngine { respond("""{"data":{"weeklyEmailEnabled":true,"complianceThreshold":150}}""", HttpStatusCode.OK, json) }).settings("org")
        assertEquals(ReportFailure.INVALID_RESPONSE, (bad as ReportResult.Failure).reason)
    }

    private val certificateJson = """{"id":"c1","periodStart":"2026-10-01","periodEnd":"2026-10-31","averageCompliance":85.0,"threshold":80.0,"runs":120,
        "verificationCode":"ABCDEFGHIJKLMNOPQRSTUVWX","issuedAt":"2026-11-10T15:00:00Z"}"""

    @Test fun certificateCreatedVersusAlreadyIssued() = runTest {
        val created = repo(MockEngine { respond("""{"data":$certificateJson}""", HttpStatusCode.Created, json) }).issueCertificate("org", "2026-10-01", "2026-10-31")
        assertTrue((created as ReportResult.Success).value.created)
        val existing = repo(MockEngine { respond("""{"data":$certificateJson}""", HttpStatusCode.OK, json) }).issueCertificate("org", "2026-10-01", "2026-10-31")
        assertFalse((existing as ReportResult.Success).value.created)
    }

    @Test fun thresholdNotMetExposesOnlyPublicFigures() = runTest {
        val body = """{"errors":[{"code":"REPORT_THRESHOLD_NOT_MET","message":"x","fields":{"averageCompliance":"70.00","threshold":"80.00","otro":"secreto"}}]}"""
        val result = repo(MockEngine { respond(body, HttpStatusCode.UnprocessableEntity, json) }).issueCertificate("org", "2026-10-01", "2026-10-31")
        val failure = result as ReportResult.Failure
        assertEquals(ReportFailure.THRESHOLD_NOT_MET, failure.reason)
        assertEquals(mapOf("averageCompliance" to "70.00", "threshold" to "80.00"), failure.fields)
        val none = repo(MockEngine { respond("""{"errors":[{"code":"REPORT_NO_DATA"}]}""", HttpStatusCode.UnprocessableEntity, json) }).issueCertificate("org", "2026-10-01", "2026-10-31")
        assertEquals(ReportFailure.NO_DATA, (none as ReportResult.Failure).reason)
    }

    @Test fun publicVerificationSendsNoCredentials() = runTest {
        val body = """{"data":{"valid":true,"organizationName":"Cocina Sur","periodStart":"2026-10-01","periodEnd":"2026-10-31","averageCompliance":85.0,"threshold":80.0,
            "issuedAt":"2026-11-10T15:00:00Z","verificationCode":"ABCDEFGHIJKLMNOPQRSTUVWX"}}"""
        val result = repo(MockEngine { request ->
            assertEquals("/api/v1/compliance-certificates/ABCDEFGHIJKLMNOPQRSTUVWX", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond(body, HttpStatusCode.OK, json)
        }, token = null).verify("ABCDEFGHIJKLMNOPQRSTUVWX")
        assertEquals("Cocina Sur", (result as ReportResult.Success).value.organizationName)
        val missing = repo(MockEngine { respond("""{"errors":[{"code":"CERTIFICATE_NOT_FOUND"}]}""", HttpStatusCode.NotFound, json) }, token = null).verify("ABCDEFGHIJKLMNOPQRSTUVWX")
        assertEquals(ReportFailure.CERTIFICATE_NOT_FOUND, (missing as ReportResult.Failure).reason)
    }
}
