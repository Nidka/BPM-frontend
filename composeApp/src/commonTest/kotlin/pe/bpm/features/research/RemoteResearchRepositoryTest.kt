package pe.bpm.features.research

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.research.data.RemoteResearchRepository
import pe.bpm.features.research.domain.*

class RemoteResearchRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val base = "/api/v1/organizations/org/research"

    private fun repo(engine: MockEngine) = RemoteResearchRepository(createHttpClient(engine), AppConfig("https://api.example.com"), token = { "token" })

    private val participationJson = """{"data":{
        "participant":{"code":"P-7K2M9QXA","status":"ACTIVE"},
        "consentDocument":{"version":"v1","title":"T","text":"X","responsibleInvestigator":"Dra","publishedAt":"$STAMP"},
        "consent":{"documentVersion":"v1","accepted":true,"recordedAt":"$STAMP","responsibleInvestigator":"Dra"},"consentValid":true,
        "survey":{"instrumentCode":"CAP-BPM","name":"CAP","instrumentVersionId":"$VERSION_ID","versionNumber":1,"questions":[
          {"id":"$Q1","order":1,"dimension":"KNOWLEDGE","type":"MULTIPLE_CHOICE","text":"P","options":["a","b"],"correctOption":1}]},
        "submissions":[{"moment":"PRETEST","instrumentVersionId":"$VERSION_ID","submittedAt":"$STAMP"}]}}"""

    @Test fun participationIsTranslatedWithoutAnyCorrectAnswer() = runTest {
        val repository = repo(MockEngine { request ->
            assertEquals("$base/participation", request.url.encodedPath)
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            respond(participationJson, HttpStatusCode.OK, json)
        })
        val value = (repository.participation("org") as ResearchResult.Success).value
        assertTrue(value.consentValid)
        assertEquals(setOf(SurveyMoment.PRETEST), value.completedMoments())
        assertFalse(value.survey.toString().contains("correct", ignoreCase = true))
    }

    @Test fun inconsistentParticipationIsRejected() = runTest {
        val noConsent = participationJson.replace("\"consentValid\":true", "\"consentValid\":false")
        val badScale = participationJson.replace("\"type\":\"MULTIPLE_CHOICE\"", "\"type\":\"LIKERT\"").replace("KNOWLEDGE", "ATTITUDE")
        for (body in listOf(noConsent, badScale, "{}", "no json")) {
            val repository = repo(MockEngine { respond(body, HttpStatusCode.OK, json) })
            assertEquals(ResearchResult.Failure(ResearchFailure.INVALID_RESPONSE), repository.participation("org"))
        }
    }

    @Test fun submissionSendsTheStableBodyAndChecksTheReceipt() = runTest {
        var sent = ""
        val repository = repo(MockEngine { request ->
            assertEquals("$base/submissions", request.url.encodedPath)
            sent = (request.body as io.ktor.http.content.TextContent).text
            respond("""{"data":{"id":"$Q2","moment":"PRETEST","instrumentVersionId":"$VERSION_ID","submittedAt":"$STAMP","scores":{"knowledgePercent":90}}}""", HttpStatusCode.Created, json)
        })
        val submission = SurveySubmission(Q2, Q3, VERSION_ID, SurveyMoment.PRETEST, STAMP, listOf(SurveyAnswer(Q1, 1)))
        val receipt = (repository.submit("org", submission) as ResearchResult.Success).value
        assertEquals(Q2, receipt.id)
        val body = Json.parseToJsonElement(sent).jsonObject
        assertEquals(Q3, body["clientOperationId"]?.jsonPrimitive?.content)
        assertEquals("PRETEST", body["moment"]?.jsonPrimitive?.content)
        // Un recibo de otra entrega no se acepta.
        val other = repo(MockEngine { respond("""{"data":{"id":"$Q1","moment":"PRETEST","instrumentVersionId":"$VERSION_ID","submittedAt":"$STAMP"}}""", HttpStatusCode.OK, json) })
        assertEquals(ResearchResult.Failure(ResearchFailure.INVALID_RESPONSE), other.submit("org", submission))
    }

    @Test fun errorCodesMapToPublicReasons() = runTest {
        suspend fun reason(status: Int, code: String): ResearchFailure {
            val repository = repo(MockEngine { respond("""{"errors":[{"code":"$code","message":"SQL private"}]}""", HttpStatusCode.fromValue(status), json) })
            return (repository.participation("org") as ResearchResult.Failure).reason
        }
        assertEquals(ResearchFailure.CONSENT_REQUIRED, reason(403, "RESEARCH_CONSENT_REQUIRED"))
        assertEquals(ResearchFailure.RESEARCH_DENIED, reason(403, "RESEARCH_ACCESS_DENIED"))
        assertEquals(ResearchFailure.DENIED, reason(403, "ACCESS_DENIED"))
        assertEquals(ResearchFailure.ALREADY_SUBMITTED, reason(409, "RESEARCH_SUBMISSION_EXISTS"))
        assertEquals(ResearchFailure.CONFLICT, reason(409, "IDEMPOTENCY_CONFLICT"))
        assertEquals(ResearchFailure.VERSION_OUTDATED, reason(409, "RESEARCH_VERSION_OUTDATED"))
        assertEquals(ResearchFailure.ANSWERS_INVALID, reason(422, "RESEARCH_ANSWERS_INVALID"))
        assertEquals(ResearchFailure.INSTRUMENT_INCOMPLETE, reason(422, "RESEARCH_INSTRUMENT_INCOMPLETE"))
        assertEquals(ResearchFailure.SESSION, reason(401, "X"))
        assertEquals(ResearchFailure.UNAVAILABLE, reason(503, "X"))
    }

    @Test fun resultsAndPaginationAreTranslated() = runTest {
        val stats = """{"n":2,"knowledgeMean":50.5,"attitudeMean":null,"practiceMean":3}"""
        val repository = repo(MockEngine { request ->
            assertEquals("/api/v1/research/results", request.url.encodedPath)
            assertEquals("20", request.url.parameters["pageSize"])
            respond("""{"data":[{"organizationId":"$Q1","organizationName":"E","participants":3,"pretest":$stats,"posttest":$stats,
                "paired":{"n":1,"knowledgeDelta":1.5,"attitudeDelta":null,"practiceDelta":0}}],"version":{"id":"$VERSION_ID","versionNumber":2},"pagination":{"nextPageToken":"abc_1"}}""", HttpStatusCode.OK, json)
        })
        val results = (repository.results(null, PageRequest("", size = 20)) as ResearchResult.Success).value
        assertEquals(2, results.version.versionNumber)
        assertEquals("abc_1", results.page.nextToken)
        assertNull(results.page.items.single().pretest.attitudeMean)
    }

    @Test fun csvExportChecksTypeAndNameAndSize() = runTest {
        val ok = repo(MockEngine { respond("a,b\n1,2\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("text/csv; charset=utf-8"), HttpHeaders.ContentDisposition to listOf("attachment; filename=\"cap-v1-respuestas.csv\""))) })
        val file = (ok.export(null, null) as ResearchResult.Success).value
        assertEquals("cap-v1-respuestas.csv", file.fileName)
        assertEquals("a,b\n1,2\n", file.bytes.decodeToString())
        val html = repo(MockEngine { respond("<html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html")) })
        assertTrue(html.export(null, null) is ResearchResult.Failure)
        val unsafe = repo(MockEngine { respond("a", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("text/csv"), HttpHeaders.ContentDisposition to listOf("attachment; filename=\"../x.csv\""))) })
        assertEquals("respuestas-cap.csv", (unsafe.export(null, null) as ResearchResult.Success).value.fileName)
    }
}
