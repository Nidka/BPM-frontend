package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.checklists.data.RemoteChecklistCatalogRepository
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.ChecklistCatalogViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistCatalogTest {
    private val headers = headersOf("Content-Type", "application/json")
    private val config = AppConfig("https://api.example.com")
    private val payload = """{"data":[{"id":"template","name":"Control de apertura","description":"Antes del turno","area":"Cocina","versionId":"version","versionNumber":2,"items":[
      {"id":"temperature","sectionName":"Conservación","label":"Temperatura del equipo","responseType":"TEMPERATURE","unit":"°C","displayOrder":3,"required":true,"minimumValue":-18.5,"maximumValue":-15},
      {"id":"compliance","sectionName":"Higiene","label":"Superficies limpias","responseType":"COMPLIANCE","displayOrder":1,"required":true},
      {"id":"text","sectionName":"Turno","label":"Observación inicial","responseType":"TEXT","displayOrder":4,"required":false},
      {"id":"number","sectionName":"Recepción","label":"Cantidad revisada","responseType":"NUMBER","displayOrder":2,"required":false,"minimumValue":1,"maximumValue":10}
    ]}]}"""

    @Test fun requiresSessionAndMapsRealCatalogWithPublishedCriteria() = runTest {
        var calls = 0
        var accessToken: String? = null
        var sessionResponses = 0
        val client = createHttpClient(MockEngine { request ->
            calls++
            assertEquals("/api/v1/organizations/organization/checklists", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("Bearer test.token", request.headers["Authorization"])
            assertNull(request.headers["X-Organization-Id"])
            respond(payload, HttpStatusCode.OK, headers)
        })
        try {
            val repository = RemoteChecklistCatalogRepository(client, config, onSessionResponse = { sessionResponses++ }) { accessToken }
            assertEquals(ChecklistFailure.SESSION_REQUIRED, assertIs<ChecklistCatalogResult.Failure>(repository.catalog("organization")).reason)
            assertEquals(0, calls)
            accessToken = "test.token"
            val template = assertIs<ChecklistCatalogResult.Success>(LoadChecklistCatalog(repository)("organization")).templates.single()
            assertEquals(2, template.versionNumber)
            assertEquals(listOf("compliance", "number", "temperature", "text"), template.items.map { it.id })
            assertEquals(-18.5, template.items[2].minimumValue)
            assertEquals("°C", template.items[2].unit)
            assertEquals(1, calls)
            assertEquals(1, sessionResponses)
            assertIs<ChecklistCatalogResult.Failure>(repository.catalog("../other?token=private"))
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun rejectedSessionsInvalidateTheUsedTokenWithoutLeakingProviderErrors() = runTest {
        var rejected: String? = null
        val client = createHttpClient(MockEngine {
            respond("""{"errors":[{"code":"SESSION_EXPIRED","message":"private SQL token"}]}""", HttpStatusCode.Unauthorized, headers)
        })
        try {
            val result = RemoteChecklistCatalogRepository(client, config, onSessionRejected = { rejected = it }) { "test.token" }.catalog("organization")
            assertEquals(ChecklistFailure.SESSION_REQUIRED, assertIs<ChecklistCatalogResult.Failure>(result).reason)
            assertEquals("test.token", rejected)
            assertFalse(result.toString().contains("private"))
        } finally { client.close() }
    }

    @Test fun emptyCatalogIsDifferentFromDenialAndMalformedResponses() = runTest {
        var body = """{"data":[]}"""
        var status = HttpStatusCode.OK
        val client = createHttpClient(MockEngine { respond(body, status, headers) })
        try {
            val repository = RemoteChecklistCatalogRepository(client, config) { "test.token" }
            assertTrue(assertIs<ChecklistCatalogResult.Success>(repository.catalog("organization")).templates.isEmpty())
            status = HttpStatusCode.Forbidden
            body = "private provider message"
            assertEquals(ChecklistFailure.ACCESS_DENIED, assertIs<ChecklistCatalogResult.Failure>(repository.catalog("organization")).reason)
            status = HttpStatusCode.OK
            for (invalid in listOf(payload.replace("TEMPERATURE", "UNKNOWN"),
                payload.replace("\"maximumValue\":-15", "\"maximumValue\":-30"),
                payload.replace("\"id\":\"text\"", "\"id\":\"compliance\""),
                "{\"data\":null}", "not JSON")) {
                body = invalid
                assertEquals(ChecklistFailure.INVALID_RESPONSE, assertIs<ChecklistCatalogResult.Failure>(repository.catalog("organization")).reason)
            }
        } finally { client.close() }
    }

    @Test fun cancellationIsPropagatedAndNetworkErrorsAreSanitized() = runTest {
        var cancel = true
        val client = createHttpClient(MockEngine {
            if (cancel) throw CancellationException("cancelled")
            else throw IllegalStateException("private token")
        })
        try {
            val repository = RemoteChecklistCatalogRepository(client, config) { "test.token" }
            assertFailsWith<CancellationException> { repository.catalog("organization") }
            cancel = false
            assertEquals(ChecklistFailure.CONNECTION, assertIs<ChecklistCatalogResult.Failure>(repository.catalog("organization")).reason)
        } finally { client.close() }
    }

    @Test fun searchRetryAndContextChangesFollowTheObservableState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val template = ChecklistTemplate("template", "Apertura", "Antes del turno", "Cocina", "version", 1, emptyList())
        var fail = false
        val repository = object : ChecklistCatalogRepository {
            override suspend fun template(organizationId: String, templateId: String): ChecklistTemplateResult = ChecklistTemplateResult.Failure(ChecklistFailure.NOT_FOUND)
            override suspend fun catalog(organizationId: String, request: PageRequest) = if (fail)
                ChecklistCatalogResult.Failure(ChecklistFailure.CONNECTION) else ChecklistCatalogResult.Success(listOf(template).filter { request.query.trim().isEmpty() || it.area.contains(request.query.trim(), ignoreCase = true) })
        }
        try {
            val model = ChecklistCatalogViewModel(LoadChecklistCatalog(repository), LoadChecklistTemplate(repository))
            store.put("catalog", model)
            model.open("user", "first")
            assertTrue(model.state.value.loading)
            advanceUntilIdle()
            model.search("  COCINA ")
            advanceUntilIdle()
            assertEquals(listOf(template), model.state.value.visibleTemplates)
            model.search("no coincide")
            advanceUntilIdle()
            assertTrue(model.state.value.visibleTemplates.isEmpty())
            fail = true
            model.refresh(); advanceUntilIdle()
            assertEquals(ChecklistFailure.CONNECTION, model.state.value.failure)
            assertTrue(model.state.value.templates.isEmpty())
            fail = false
            model.refresh(); advanceUntilIdle()
            assertNull(model.state.value.failure)
            model.open("other-user", "first")
            assertEquals("", model.state.value.query)
            assertTrue(model.state.value.templates.isEmpty())
            advanceUntilIdle()
            model.open("other-user", "second")
            assertEquals("second", model.state.value.organizationId)
            model.reset(); advanceUntilIdle()
            assertNull(model.state.value.userId)
            assertTrue(model.state.value.templates.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun lateResponsesCannotRestoreAnotherTenantOrClosedSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        var response = CompletableDeferred<Unit>()
        val stale = ChecklistTemplate("old", "Anterior", "", "Cocina", "version", 1, emptyList())
        val repository = object : ChecklistCatalogRepository {
            override suspend fun template(organizationId: String, templateId: String): ChecklistTemplateResult = ChecklistTemplateResult.Failure(ChecklistFailure.NOT_FOUND)
            override suspend fun catalog(organizationId: String, request: PageRequest): ChecklistCatalogResult {
                if (organizationId == "first") withContext(NonCancellable) { response.await() }
                return ChecklistCatalogResult.Success(if (organizationId == "first") listOf(stale) else emptyList())
            }
        }
        try {
            val model = ChecklistCatalogViewModel(LoadChecklistCatalog(repository), LoadChecklistTemplate(repository))
            store.put("catalog", model)
            model.open("user", "first"); runCurrent()
            model.open("user", "second"); runCurrent()
            response.complete(Unit); advanceUntilIdle()
            assertEquals("second", model.state.value.organizationId)
            assertTrue(model.state.value.templates.isEmpty())
            response = CompletableDeferred()
            model.open("user", "first"); runCurrent()
            model.reset()
            response.complete(Unit); advanceUntilIdle()
            assertNull(model.state.value.organizationId)
            assertTrue(model.state.value.templates.isEmpty())
        } finally { response.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }
}
