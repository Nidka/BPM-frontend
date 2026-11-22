package pe.bpm.operations

import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.operations.data.LocalOperationsDraftRepository
import pe.bpm.features.operations.domain.ManageOperations
import pe.bpm.features.operations.domain.ManageOperationsDraft
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.operations.presentation.shiftProgress
import pe.bpm.screenshots.WorkerFixtures as F

/**
 * El registro rápido del personal reutiliza el registro de limpieza de siempre: abre el formulario con el plan, guarda,
 * confirma con `taskDone` y encadena la siguiente tarea sin pasar por la lista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkerQuickTaskFlowTest {
    private var counter = 0

    private fun viewModel() = OperationsViewModel(
        ManageOperations(F.OperationsFixtureRepository(), F.NoopTransport),
        ManageOperationsDraft(LocalOperationsDraftRepository(TestDraftStorage(), "https://example.test")),
        { "00000000-0000-4000-8000-%012d".format(++counter) }, { "2026-11-20T15:42:00Z" },
    ).also {
        it.open(F.USER, F.ORG, "WORKER", F.SITE)
        it.refreshToday()
    }

    private fun withMain(block: () -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { block() } finally { Dispatchers.resetMain() }
    }

    @Test fun tappingATaskOpensTheFormWithThatPlanAndGoodAsTheResult() = withMain {
        val vm = viewModel()
        vm.newCleaningFor(F.todayPlans[1])
        val state = vm.state.value
        assertNotNull(state.form)
        assertEquals(F.todayPlans[1].id, state.form!!.values["cleaningPlanId"])
        assertEquals(F.todayPlans[1].id, state.selectedPlan?.id)
        assertNull(state.form!!.values["result"]) // sin elegir: el formulario trata «Bien» (COMPLETED) como valor por defecto
    }

    @Test fun savingConfirmsTheTaskAndTheShiftProgressCountsIt() = withMain {
        val vm = viewModel()
        vm.newCleaningFor(F.todayPlans[1])
        vm.save()
        val state = vm.state.value
        assertNotNull(state.saved)
        val done = assertNotNull(state.taskDone)
        assertEquals(F.todayPlans[1].id, done.planId)
        assertEquals(F.todayPlans[1].task, done.planName)
        assertFalse(done.queued)
        val progress = shiftProgress(state.todayPlans, done.planId)
        assertEquals(F.todayPlans.count { it.today?.done == true } + 1, progress.done)
        assertNotEquals(done.planId, progress.next?.id)
    }

    @Test fun anObservedResultNeedsANoteBeforeItCanBeSaved() = withMain {
        val vm = viewModel()
        vm.newCleaningFor(F.todayPlans[1])
        vm.field("result", "OBSERVED")
        vm.save()
        assertNull(vm.state.value.taskDone)
        assertTrue(vm.state.value.fields.containsKey("observation"))
        vm.field("observation", "Falta detergente.")
        vm.save()
        assertNotNull(vm.state.value.taskDone)
    }

    @Test fun nextTaskOpensTheFollowingFormAndClearsTheConfirmation() = withMain {
        val vm = viewModel()
        vm.newCleaningFor(F.todayPlans[1])
        vm.save()
        val next = shiftProgress(vm.state.value.todayPlans, F.todayPlans[1].id).next
        assertNotNull(next)
        vm.finishTask(next)
        val state = vm.state.value
        assertNull(state.taskDone)
        assertNull(state.saved)
        assertEquals(next.id, state.form?.values?.get("cleaningPlanId"))
    }

    @Test fun backToTodayClosesTheFormAndTheConfirmation() = withMain {
        val vm = viewModel()
        vm.newCleaningFor(F.todayPlans[1])
        vm.save()
        vm.finishTask(null)
        val state = vm.state.value
        assertNull(state.form)
        assertNull(state.taskDone)
    }

    @Test fun theCloseButtonDiscardsTheDraft() = withMain {
        val vm = viewModel()
        vm.newCleaningFor(F.todayPlans[1])
        vm.field("result", "NOT_COMPLETED")
        vm.cancelTask()
        assertNull(vm.state.value.form)
        // Al abrir otra vez no queda el resultado anterior.
        vm.newCleaningFor(F.todayPlans[1])
        assertNull(vm.state.value.form?.values?.get("result"))
    }
}
