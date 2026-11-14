package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.checklists.data.RemoteChecklistVersionRepository
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChecklistVersionsTest {
    private val item = ChecklistItem("q-old", "Higiene", "Superficie limpia", ChecklistResponseType.COMPLIANCE, 1, true, "", null, null, false)
    private val v1 = ChecklistTemplate("tpl", "Apertura", "", "Cocina", "ver-1", 1, listOf(item))
    private val v2 = v1.copy(versionId = "ver-2", versionNumber = 2)
    private val newVersionId = "11111111-1111-4111-8111-111111111111"
    private val newItemId = "22222222-2222-4222-8222-222222222222"
    private val edited = item.copy(id = newItemId, label = "Superficie y utensilios limpios")
    private val draft = ChecklistVersionDraft(newVersionId, 1, "Más detalle", listOf(edited))

    @Test
    fun identicalQuestionsAreNotAnotherVersion() = runTest {
        val repo = FakeVersions()
        val publish = PublishChecklistVersion(repo)
        val result = publish("org", "tpl", v1, draft.copy(items = listOf(item.copy(id = newItemId)))) as VersionResult.Failure
        assertEquals(VersionFailure.VALIDATION, result.reason)
        assertTrue("items" in result.fields)
        assertEquals(0, repo.publishes.size)
    }

    @Test
    fun everyQuestionNeedsANewIdAndTheBaseMustBeTheCurrentVersion() = runTest {
        val repo = FakeVersions()
        val publish = PublishChecklistVersion(repo)
        val reused = publish("org", "tpl", v1, draft.copy(items = listOf(edited.copy(id = "q-old")))) as VersionResult.Failure
        assertTrue("items" in reused.fields)
        val staleBase = publish("org", "tpl", v2, draft) as VersionResult.Failure
        assertTrue("baseVersionNumber" in staleBase.fields)
        val longNote = publish("org", "tpl", v1, draft.copy(changeNote = "x".repeat(501))) as VersionResult.Failure
        assertTrue("changeNote" in longNote.fields)
        assertEquals(0, repo.publishes.size)
        repo.publishResults += VersionResult.Success(v2)
        assertIs<VersionResult.Success<ChecklistTemplate>>(publish("org", "tpl", v1, draft))
        assertEquals(1, repo.publishes.size)
    }

    @Test
    fun conflictForcesReloadInsteadOfSilentlyRetrying() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo = FakeVersions()
            val vm = viewModel(repo)
            models.put("vm", vm)
            vm.open("admin", "org", "tpl")
            advanceUntilIdle()
            assertEquals(1, vm.state.value.current?.versionNumber)
            assertEquals(1, vm.state.value.history.size)
            vm.startEdit()
            val editedId = vm.state.value.questions.single().id
            assertNotEquals("q-old", editedId)
            vm.question(editedId) { it.copy(label = "Superficie y utensilios limpios") }
            vm.review()
            assertEquals(VersionMode.Review, vm.state.value.mode)
            repo.publishResults += VersionResult.Failure(VersionFailure.VERSION_CONFLICT)
            vm.confirmPublication()
            advanceUntilIdle()
            assertTrue(vm.state.value.conflict)
            assertEquals(VersionFailure.VERSION_CONFLICT, vm.state.value.failure)
            assertNull(vm.state.value.published)
            // Alguien publicó antes: se recarga la versión más nueva y se parte de nuevo.
            repo.current = v2
            vm.reload()
            advanceUntilIdle()
            assertEquals(2, vm.state.value.current?.versionNumber)
            assertFalse(vm.state.value.conflict)
            assertEquals(VersionMode.History, vm.state.value.mode)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun uncertainPublicationRetriesTheSameVersionId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo = FakeVersions()
            val vm = viewModel(repo)
            models.put("vm", vm)
            vm.open("admin", "org", "tpl")
            advanceUntilIdle()
            vm.startEdit()
            val id = vm.state.value.questions.single().id
            vm.question(id) { it.copy(label = "Superficie y utensilios limpios") }
            vm.changeNote("Más detalle")
            vm.review()
            repo.publishResults += VersionResult.Failure(VersionFailure.CONNECTION)
            repo.publishResults += VersionResult.Success(v2)
            vm.confirmPublication()
            advanceUntilIdle()
            assertTrue(vm.state.value.uncertain)
            vm.changeNote("Otra nota")
            assertEquals("Más detalle", vm.state.value.changeNote)
            vm.confirmPublication()
            advanceUntilIdle()
            assertEquals(2, repo.publishes.size)
            assertEquals(repo.publishes[0], repo.publishes[1])
            assertEquals(2, vm.state.value.current?.versionNumber)
            assertEquals(2, vm.state.value.published?.versionNumber)
            assertEquals(VersionMode.History, vm.state.value.mode)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun archivedVersionsOpenReadOnlyWithTheirOwnQuestions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val models = ViewModelStore()
        try {
            val repo = FakeVersions().apply { archived = v1.copy(items = listOf(item.copy(label = "Pregunta antigua"))) }
            val vm = viewModel(repo)
            models.put("vm", vm)
            vm.open("admin", "org", "tpl")
            advanceUntilIdle()
            vm.view("ver-1")
            advanceUntilIdle()
            assertEquals("Pregunta antigua", vm.state.value.viewing?.items?.single()?.label)
            vm.closeViewing()
            assertNull(vm.state.value.viewing)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun remoteRepositoryMapsVersionConflictAndConfirmsThePublishedVersion() = runTest {
        var status = HttpStatusCode.OK
        var payload = ""
        var body: JsonObject? = null
        val client =
            createHttpClient(
                MockEngine { request ->
                    if (request.method == HttpMethod.Post) body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    respond(payload, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            )
        val repo = RemoteChecklistVersionRepository(client, AppConfig("https://example.test")) { "token" }
        fun template(number: Int, label: String) =
            """{"data":{"id":"tpl","name":"Apertura","description":"","area":"Cocina","versionId":"$newVersionId","versionNumber":$number,"items":[{"id":"$newItemId","sectionName":"Higiene","label":"$label","responseType":"COMPLIANCE","displayOrder":1,"required":true}]}}"""
        payload = template(2, "Superficie y utensilios limpios")
        val published = repo.publish("org", "tpl", draft) as VersionResult.Success
        assertEquals(2, published.value.versionNumber)
        assertEquals(setOf("versionId", "baseVersionNumber", "changeNote", "items"), body!!.keys)
        // La respuesta con otro contenido no confirma la publicación.
        payload = template(2, "Otra pregunta")
        assertEquals(VersionFailure.INVALID_RESPONSE, (repo.publish("org", "tpl", draft) as VersionResult.Failure).reason)
        payload = template(3, "Superficie y utensilios limpios")
        assertEquals(VersionFailure.INVALID_RESPONSE, (repo.publish("org", "tpl", draft) as VersionResult.Failure).reason)
        status = HttpStatusCode.Conflict
        payload = """{"errors":[{"code":"VERSION_CONFLICT","message":"interno"}]}"""
        assertEquals(VersionFailure.VERSION_CONFLICT, (repo.publish("org", "tpl", draft) as VersionResult.Failure).reason)
        payload = """{"errors":[{"code":"RESOURCE_CONFLICT"}]}"""
        assertEquals(VersionFailure.CONFLICT, (repo.publish("org", "tpl", draft) as VersionResult.Failure).reason)
        status = HttpStatusCode.UnprocessableEntity
        payload = """{"errors":[{"code":"VALIDATION_ERROR","fields":{"changeNote":"x","secreto":"x"}}]}"""
        val invalid = repo.publish("org", "tpl", draft) as VersionResult.Failure
        assertEquals(setOf("changeNote"), invalid.fields.keys)
        status = HttpStatusCode.OK
        payload = """{"data":[{"id":"ver-2","templateId":"tpl","versionNumber":2,"status":"PUBLISHED","changeNote":"Más detalle","itemCount":1,"publishedAt":"2026-11-10T15:00:00Z"},{"id":"ver-1","templateId":"tpl","versionNumber":1,"status":"ARCHIVED","changeNote":"","itemCount":1,"publishedAt":"2026-11-01T15:00:00Z"}],"pagination":{"nextPageToken":""}}"""
        val history = repo.versions("org", "tpl", PageRequest()) as VersionResult.Success
        assertEquals(listOf(VersionStatus.PUBLISHED, VersionStatus.ARCHIVED), history.value.items.map { it.status })
        payload = payload.replace("ARCHIVED", "DELETED")
        assertEquals(VersionFailure.INVALID_RESPONSE, (repo.versions("org", "tpl", PageRequest()) as VersionResult.Failure).reason)
        client.close()
    }

    private fun viewModel(repo: FakeVersions): ChecklistVersionsViewModel {
        var counter = 0
        return ChecklistVersionsViewModel(
            LoadChecklistTemplate(repo),
            LoadChecklistVersions(repo),
            LoadChecklistVersion(repo),
            PublishChecklistVersion(repo),
        ) {
            "00000000-0000-4000-8000-" + (counter++).toString().padStart(12, '0')
        }
    }

    private inner class FakeVersions : ChecklistVersionRepository, PublishedChecklistReader {
        var current = v1
        var archived: ChecklistTemplate = v1
        val publishResults = ArrayDeque<VersionResult<ChecklistTemplate>>()
        val publishes = mutableListOf<ChecklistVersionDraft>()

        override suspend fun template(organizationId: String, templateId: String) =
            ChecklistTemplateResult.Success(current)

        override suspend fun versions(organizationId: String, templateId: String, page: PageRequest) =
            VersionResult.Success(
                Page(
                    listOf(ChecklistVersionSummary("ver-1", "tpl", 1, VersionStatus.PUBLISHED, "", 1, "2026-11-01T15:00:00Z")),
                    "",
                )
            )

        override suspend fun version(organizationId: String, templateId: String, versionId: String) =
            VersionResult.Success(archived)

        override suspend fun publish(organizationId: String, templateId: String, draft: ChecklistVersionDraft): VersionResult<ChecklistTemplate> {
            publishes += draft
            return publishResults.removeFirstOrNull() ?: VersionResult.Success(v2)
        }
    }
}
