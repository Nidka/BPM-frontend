package pe.bpm.features.checklists

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.checklists.data.LocalChecklistTemplateDraftRepository
import pe.bpm.features.checklists.domain.*
import pe.bpm.features.checklists.presentation.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TemplateDraftRecoveryTest {
    @Test fun publicationPendingAcrossRestartKeepsTheSameTemplateAndQuestionIds() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        try {
            val storage = TestDraftStorage()
            val drafts = ManageTemplateDraft(LocalChecklistTemplateDraftRepository(storage, "https://api.example.com"))
            val proposals = mutableListOf<ChecklistTemplateDraft>()
            val publisher = PublishChecklistTemplate(object : ChecklistTemplatePublisher {
                override suspend fun publish(organizationId: String, draft: ChecklistTemplateDraft): TemplatePublicationResult {
                    proposals += draft
                    return if (proposals.size == 1) TemplatePublicationResult.Rejected(PublicationFailure.CONNECTION)
                    else TemplatePublicationResult.Published(ChecklistTemplate(draft.id, draft.name, draft.description, draft.area, draft.versionId, 1, draft.items))
                }
            })
            var ids = 0
            fun editor() = ChecklistTemplateEditorViewModel(publisher, drafts) { "00000000-0000-4000-8000-${(++ids).toString().padStart(12, '0')}" }
            val first = editor(); models.put("first", first)
            first.open("user", "org"); advanceUntilIdle()
            first.useSuggestion("opening"); first.review(); first.confirmPublication(); advanceUntilIdle()
            assertTrue(first.state.value.uncertain)
            first.reset(); advanceUntilIdle()
            val restored = editor(); models.put("restored", restored)
            restored.open("user", "org"); advanceUntilIdle()
            assertTrue(restored.state.value.recovered)
            assertTrue(restored.state.value.uncertain)
            assertFalse(restored.state.value.editable)
            assertEquals(1, proposals.size)
            restored.confirmPublication(); advanceUntilIdle()
            assertEquals(proposals.first(), proposals.last())
            assertEquals(TemplateEditorStep.Published, restored.state.value.step)
            assertNull(drafts.read(DraftOwner("user", "org")))
        } finally { models.clear(); Dispatchers.resetMain() }
    }

    @Test fun suggestionsDoNotOverwriteWorkAndExplicitDiscardRemovesOnlyItsOwnerDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val models = ViewModelStore()
        try {
            val drafts = ManageTemplateDraft(LocalChecklistTemplateDraftRepository(TestDraftStorage(), "https://api.example.com"))
            val publisher = PublishChecklistTemplate(object : ChecklistTemplatePublisher {
                override suspend fun publish(organizationId: String, draft: ChecklistTemplateDraft) = TemplatePublicationResult.Rejected(PublicationFailure.UNAVAILABLE)
            })
            var ids = 0
            val vm = ChecklistTemplateEditorViewModel(publisher, drafts) { "id-${++ids}" }; models.put("vm", vm)
            vm.open("user", "org"); advanceUntilIdle(); vm.name("Mi control"); vm.useSuggestion("cleaning"); advanceUntilIdle()
            assertEquals("Mi control", vm.state.value.name)
            assertEquals(1, vm.state.value.questions.size)
            vm.open("other", "org"); advanceUntilIdle()
            assertEquals("", vm.state.value.name)
            vm.name("Otra cuenta"); advanceUntilIdle()
            vm.discard {}; advanceUntilIdle()
            assertNull(drafts.read(DraftOwner("other", "org")))
            vm.open("third", "org"); advanceUntilIdle()
            vm.type(vm.state.value.questions.single().id, ChecklistResponseType.TEMPERATURE)
            vm.useSuggestion("opening"); advanceUntilIdle()
            assertEquals("", vm.state.value.name)
            assertEquals(ChecklistResponseType.TEMPERATURE, vm.state.value.questions.single().type)
            assertEquals("Mi control", drafts.read(DraftOwner("user", "org"))?.name)
        } finally { models.clear(); Dispatchers.resetMain() }
    }
}
