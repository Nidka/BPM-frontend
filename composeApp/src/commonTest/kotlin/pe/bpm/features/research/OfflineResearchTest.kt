package pe.bpm.features.research

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.offline.*
import pe.bpm.features.research.data.RemoteResearchRepository
import pe.bpm.features.research.domain.*
import kotlin.test.*

private const val API = "https://api.example.com"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private const val SUBMISSION_ID = "50000000-0000-4000-8000-000000000001"
private const val OPERATION_ID = "60000000-0000-4000-8000-000000000001"

/** Encuesta CAP sin conexión: respuestas por la Outbox con UUID estable; el consentimiento exige servidor. */
class OfflineResearchTest {
    private val submission = SurveySubmission(SUBMISSION_ID, OPERATION_ID, VERSION_ID, SurveyMoment.PRETEST, STAMP,
        listOf(SurveyAnswer(Q1, 1), SurveyAnswer(Q2, 4), SurveyAnswer(Q3, 5)))

    private class Rig(online: Boolean) {
        val h = OfflineHarness(connectivity = ManualConnectivityMonitor(online))
        var networkDown = !online
        val engine = MockEngine { request ->
            if (networkDown) throw IOException("sin red")
            respond("""{"data":{"id":"$SUBMISSION_ID","moment":"PRETEST","instrumentVersionId":"$VERSION_ID","submittedAt":"$STAMP"}}""", HttpStatusCode.Created, jsonHeaders)
        }
        val repository = RemoteResearchRepository(createHttpClient(OfflineHttpEngine(engine, h.runtime.http, API)), AppConfig(API), token = { "token" })
    }

    @Test fun answersGivenOfflineAreQueuedWithTheirStableUuid() = runTest {
        val rig = Rig(online = false); rig.h.login()
        assertEquals(ResearchResult.Failure(ResearchFailure.QUEUED), rig.repository.submit("org", submission))
        val op = rig.h.runtime.outbox.snapshot().single()
        assertEquals(OPERATION_ID, op.idempotencyKey)
        assertEquals(SUBMISSION_ID, op.entityId)
        assertTrue(op.payload.contains("/research/submissions"))
        // Reintentar la misma entrega no crea otra operación.
        rig.repository.submit("org", submission)
        assertEquals(1, rig.h.runtime.outbox.snapshot().size)
    }

    @Test fun consentNeedsTheServerBecauseItIsALegalActRecordedThere() = runTest {
        val rig = Rig(online = false); rig.h.login()
        val result = rig.repository.recordConsent("org", "v1", true)
        assertEquals(ResearchResult.Failure(ResearchFailure.CONNECTION), result)
        assertTrue(rig.h.runtime.outbox.snapshot().isEmpty())
    }

    @Test fun theSavedParticipationCanBeReadWithoutNetwork() = runTest {
        val rig = Rig(online = true); rig.h.login()
        val body = """{"data":{"participant":{"code":"P-7K2M9QXA","status":"ACTIVE"},
            "consentDocument":{"version":"v1","title":"T","text":"X","responsibleInvestigator":"Dra","publishedAt":"$STAMP"},
            "consent":{"documentVersion":"v1","accepted":true,"recordedAt":"$STAMP","responsibleInvestigator":"Dra"},"consentValid":true,
            "survey":{"instrumentCode":"CAP-BPM","name":"CAP","instrumentVersionId":"$VERSION_ID","versionNumber":1,"questions":[
              {"id":"$Q1","order":1,"dimension":"KNOWLEDGE","type":"MULTIPLE_CHOICE","text":"P","options":["a","b"]}]},"submissions":[]}}"""
        val online = pe.bpm.core.network.createHttpClient(OfflineHttpEngine(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }, rig.h.runtime.http, API))
        val repository = RemoteResearchRepository(online, AppConfig(API), token = { "token" })
        assertTrue(repository.participation("org") is ResearchResult.Success)
        rig.h.connectivity.set(false)
        val offline = (repository.participation("org") as ResearchResult.Success).value
        assertTrue(offline.consentValid)
        assertEquals("CAP-BPM", offline.survey?.instrumentCode)
    }

    // ---- Envío desde la Outbox: duplicados cuentan como enviados ------------------------------------

    private suspend fun queued(h: OfflineHarness): OutboxOperation {
        h.connectivity.set(false)
        val offline = RemoteResearchRepository(createHttpClient(OfflineHttpEngine(MockEngine { throw IOException("sin red") }, h.runtime.http, API)), AppConfig(API), token = { "token" })
        offline.submit("org", submission)
        return h.runtime.outbox.snapshot().single()
    }

    private fun handler(status: HttpStatusCode, body: String) =
        HttpOutboxHandler(createHttpClient(MockEngine { respond(body, status, jsonHeaders) }), API, { "token" })

    @Test fun aDuplicateSubmissionConflictCountsAsSent() = runTest {
        val h = OfflineHarness(); h.login()
        val op = queued(h)
        val outcome = handler(HttpStatusCode.Conflict, """{"errors":[{"code":"RESEARCH_SUBMISSION_EXISTS"}]}""").send(op)
        assertEquals(SendOutcome.Sent(409), outcome)
    }

    @Test fun otherConflictsStillNeedTheUsersDecision() = runTest {
        val h = OfflineHarness(); h.login()
        val op = queued(h)
        assertTrue(handler(HttpStatusCode.Conflict, """{"errors":[{"code":"RESEARCH_VERSION_OUTDATED"}]}""").send(op) is SendOutcome.Conflict)
    }

    @Test fun theQueuedSubmissionIsSentOnceWhenTheConnectionReturns() = runTest {
        val h = OfflineHarness(); h.login()
        val op = queued(h)
        assertEquals(SendOutcome.Sent(201), handler(HttpStatusCode.Created, """{"data":{}}""").send(op))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun localFirstShowsTheSavedParticipationBeforeTheServerAnswers() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val stale = sampleParticipation(valid = true)
        val fresh = sampleParticipation(valid = true, submissions = listOf(SubmissionRecord(SurveyMoment.PRETEST, VERSION_ID, STAMP)))
        var calls = 0
        var duringRefresh: Participation? = null
        lateinit var model: pe.bpm.features.research.presentation.SurveyViewModel
        val repo = object : ParticipationRepository {
            override suspend fun participation(org: String): ResearchResult<Participation> {
                calls++
                if (calls == 1) return ResearchResult.Success(stale)
                // La segunda lectura es la del servidor: la copia local ya estaba en pantalla.
                duringRefresh = model.state.value.participation
                return ResearchResult.Success(fresh)
            }
            override suspend fun recordConsent(org: String, documentVersion: String, accepted: Boolean): ResearchResult<Participation> = ResearchResult.Success(stale)
            override suspend fun submit(org: String, submission: SurveySubmission): ResearchResult<SubmissionReceipt> = ResearchResult.Failure(ResearchFailure.CONNECTION)
        }
        model = pe.bpm.features.research.presentation.SurveyViewModel(LoadParticipation(repo), RecordConsent(repo), SubmitSurvey(repo),
            { "id" }, { STAMP }, LocalFirstReader.Cached)
        try {
            model.open("user", "org"); testScheduler.advanceUntilIdle()
            assertEquals(2, calls)
            assertEquals(stale, duringRefresh)
            assertEquals(fresh, model.state.value.participation)
            assertFalse(model.state.value.loading)
        } finally {
            model.reset()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aSurveyQueuedOfflineShowsASavedLocallyConfirmationInsteadOfAnError() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val repo = FakeParticipationRepository().apply { submitResult = { ResearchResult.Failure(ResearchFailure.QUEUED) } }
        var n = 0
        val model = pe.bpm.features.research.presentation.SurveyViewModel(LoadParticipation(repo), RecordConsent(repo), SubmitSurvey(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP })
        try {
            model.open("user", "org"); testScheduler.advanceUntilIdle()
            model.start(SurveyMoment.PRETEST)
            validAnswers.forEach { (q, v) -> model.answer(q, v) }
            model.submit(); testScheduler.advanceUntilIdle()
            val state = model.state.value
            assertEquals(pe.bpm.features.research.presentation.SurveyPane.DONE, state.screen)
            assertTrue(state.completedQueued)
            assertNull(state.failure)
            assertNull(state.pending)
            assertEquals(1, repo.submissions.size)
        } finally {
            model.reset()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }
}
