package pe.bpm.features.incidents

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.*
import pe.bpm.features.incidents.data.prepareIncident
import pe.bpm.features.incidents.domain.*
import pe.bpm.features.incidents.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class IncidentsViewModelTest {
    private val site = "49d000f4-e487-41cf-a166-741c98cd7e04"

    private fun vm(fake: Fake) =
        IncidentsViewModel(ManageIncidents(fake, fake, fake), { site }, { "2026-11-01T00:00:00Z" })

    @Test
    fun deniedAuthorizationNeverReadsDraftAndClearsOtherAccountContext() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            vm.open("alice", "org", "WORKER", site, "America/Lima")
            advanceUntilIdle()
            vm.newReport()
            vm.editor.edit("description", "Dato privado")
            advanceUntilIdle()
            f.allow = false
            vm.open("bob", "org", "WORKER", site, "America/Lima")
            advanceUntilIdle()
            assertFalse(vm.state.value.authorized)
            assertEquals(IncidentsFailure.DENIED, vm.state.value.failure)
            assertNull(vm.editor.state.value.form)
            assertEquals(listOf("alice"), f.reads)
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun pendingIsDurableBeforeHttpAndRecoveryRetriesSameBody() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            vm.open("alice", "org", "WORKER", site, "America/Lima")
            advanceUntilIdle()
            vm.newReport()
            vm.editor.edit("catalogId", site)
            vm.editor.edit("description", "Se observó un problema")
            vm.editor.review()
            vm.editor.submit()
            advanceUntilIdle()
            val first = f.sent.single()
            assertEquals(first, f.docs.getValue("alice")!!.pending)
            assertTrue(f.persistedBeforeSend)
            vm.editor.edit("description", "No debe cambiar")
            assertEquals(
                "Se observó un problema",
                vm.editor.state.value.form!!.values["description"],
            )
            vm.reset()
            vm.open("alice", "org", "WORKER", site, "America/Lima")
            advanceUntilIdle()
            assertEquals(first, vm.editor.state.value.pending)
            vm.editor.submit()
            advanceUntilIdle()
            assertEquals(listOf(first, first), f.sent)
            assertEquals(
                listOf(
                    "authorize",
                    "read",
                    "authorize",
                    "submit",
                    "authorize",
                    "read",
                    "authorize",
                    "submit",
                ),
                f.events.filter { it in setOf("authorize", "read", "submit") },
            )
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedLocalWriteBlocksHttpAndLeave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            vm.open("alice", "org", "CHEF", site, "America/Lima")
            advanceUntilIdle()
            f.failWrites = true
            vm.newReport()
            vm.editor.edit("catalogId", site)
            vm.editor.edit("description", "Problema")
            vm.editor.review()
            vm.editor.submit()
            advanceUntilIdle()
            assertTrue(f.sent.isEmpty())
            assertEquals(IncidentsFailure.LOCAL, vm.editor.state.value.failure)
            var left = false
            vm.prepareLeave { left = true }
            advanceUntilIdle()
            assertFalse(left)
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun flushPreservesSuspendedDraftAndContinueRestoresIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            vm.open("alice", "org", "CHEF", site, "America/Lima")
            advanceUntilIdle()
            vm.newReport()
            vm.editor.edit("description", "Continuar después")
            vm.editor.close()
            advanceUntilIdle()
            assertTrue(vm.editor.state.value.hasDraft)
            assertNull(vm.editor.state.value.form)
            assertTrue(vm.flush())
            assertEquals("Continuar después", f.docs.getValue("alice")!!.form.values["description"])
            vm.editor.resume()
            assertEquals("Continuar después", vm.editor.state.value.form!!.values["description"])
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun ownCorrectionCannotBeVerifiedEvenIfServerOffersAction() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            vm.open("alice", "org", "CHEF", site, "America/Lima")
            advanceUntilIdle()
            vm.detail(site)
            advanceUntilIdle()
            vm.action(IncidentActionType.VERIFY)
            assertNull(vm.editor.state.value.form)
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun canceledSearchCannotReplaceTheNewQuery() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            vm.open("alice", "org", "CHEF", site, "America/Lima")
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            f.listGate = gate
            vm.query("primera")
            advanceTimeBy(300)
            runCurrent()
            f.listGate = null
            vm.query("segunda")
            advanceTimeBy(300)
            runCurrent()
            assertEquals("segunda", vm.state.value.incidents.query)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals("segunda", vm.state.value.incidents.query)
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun restoredActionRechecksIncidentVisibilityBeforeShowingLocalContent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            f.docs["alice"] =
                IncidentDraft(
                    site,
                    IncidentForm(
                        site,
                        "2026-11-01T00:00:00Z",
                        "ASSIGN",
                        mapOf("description" to "Dato restringido"),
                        incidentId = site,
                    ),
                )
            f.denyDetail = true
            vm.open("alice", "org", "CHEF", site, "America/Lima")
            advanceUntilIdle()
            assertNull(vm.editor.state.value.form)
            assertEquals(IncidentsFailure.DENIED, vm.state.value.failure)
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun definitiveRejectionUnlocksFormAndPersistsNoPendingBody() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            for (reason in
                listOf(
                    IncidentsFailure.CONFLICT,
                    IncidentsFailure.INVALID,
                    IncidentsFailure.INVALID_PAGE,
                    IncidentsFailure.NOT_FOUND,
                )) {
                vm.reset()
                f.docs.clear()
                f.submitFailure = reason
                vm.open("alice", "org", "CHEF", site, "America/Lima")
                advanceUntilIdle()
                vm.newReport()
                vm.editor.edit("catalogId", site)
                vm.editor.edit("description", "Problema")
                vm.editor.review()
                vm.editor.submit()
                advanceUntilIdle()
                assertNull(vm.editor.state.value.pending, reason.name)
                assertFalse(vm.editor.state.value.reviewing)
                assertNull(f.docs.getValue("alice")!!.pending)
                vm.editor.edit("description", "Corregido")
                assertEquals("Corregido", vm.editor.state.value.form!!.values["description"])
                vm.editor.discard()
                advanceUntilIdle()
                assertNull(vm.editor.state.value.form)
            }
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun administrativeRolesCannotOpenReportForm() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = Fake()
        val vm = vm(f)
        try {
            for (role in listOf("ADMIN", "SUPER_ADMIN")) {
                vm.reset()
                vm.open("alice", "org", role, site, "America/Lima")
                advanceUntilIdle()
                vm.newReport()
                assertNull(vm.editor.state.value.form)
            }
            assertTrue(f.sent.isEmpty())
        } finally {
            vm.reset()
            vm.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private inner class Fake : IncidentsRepository, IncidentTransport, IncidentDraftRepository {
        var listGate: CompletableDeferred<Unit>? = null
        var denyDetail = false
        var submitFailure = IncidentsFailure.CONNECTION
        var allow = true
        var failWrites = false
        var persistedBeforeSend = false
        val docs = mutableMapOf<String, IncidentDraft?>()
        val reads = mutableListOf<String>()
        val sent = mutableListOf<PendingIncident>()
        val events = mutableListOf<String>()

        override suspend fun authorize(
            org: String,
            site: String,
            role: String,
        ): IncidentsResult<Unit> {
            events += "authorize"
            return if (allow) IncidentsResult.Success(Unit)
            else IncidentsResult.Failure(IncidentsFailure.DENIED)
        }

        override suspend fun read(owner: DraftOwner, site: String): IncidentDraft? {
            events += "read"
            reads += owner.userId
            return docs[owner.userId]
        }

        override suspend fun write(owner: DraftOwner, site: String, draft: IncidentDraft?) {
            if (failWrites) error("local")
            docs[owner.userId] = draft
        }

        override fun prepare(form: IncidentForm, site: String, key: String) =
            prepareIncident(form, site, key)

        override suspend fun submit(
            org: String,
            site: String,
            form: IncidentForm,
            pending: PendingIncident,
        ): IncidentsResult<IncidentSaved> {
            events += "submit"
            persistedBeforeSend = docs.values.any { it?.pending == pending }
            sent += pending
            return IncidentsResult.Failure(submitFailure)
        }

        override suspend fun catalog(org: String, page: PageRequest) =
            IncidentsResult.Success(Page<IncidentCatalog>(emptyList()))

        override suspend fun incidents(
            org: String,
            site: String,
            status: IncidentStatus?,
            page: PageRequest,
        ): IncidentsResult<Page<IncidentSummary>> {
            listGate?.let { withContext(NonCancellable) { it.await() } }
            return IncidentsResult.Success(Page(emptyList()))
        }

        override suspend fun assignees(org: String, id: String, page: PageRequest) =
            IncidentsResult.Success(Page<IncidentAssignee>(emptyList()))

        override suspend fun history(org: String, id: String, page: PageRequest) =
            IncidentsResult.Success(Page<IncidentEvent>(emptyList()))

        override suspend fun detail(org: String, id: String): IncidentsResult<IncidentDetail> =
            if (denyDetail) IncidentsResult.Failure(IncidentsFailure.DENIED)
            else
                IncidentsResult.Success(
                    IncidentDetail(
                        IncidentSummary(
                            id,
                            site,
                            site,
                            "Criterio",
                            "Área",
                            IncidentRisk.HIGH,
                            IncidentStatus.RESOLVED,
                            "Problema",
                            "Alice",
                            "2026-11-01T00:00:00Z",
                            3,
                        ),
                        "alice",
                        1,
                        "Criterio vigente",
                        true,
                        null,
                        listOf(
                            CorrectiveAction(
                                site,
                                "alice",
                                "Alice",
                                "Corregir",
                                "2026-11-02T00:00:00Z",
                                "RESOLVED",
                                "Corregido",
                            )
                        ),
                        setOf(IncidentActionType.VERIFY),
                        1,
                        1,
                    )
                )
    }
}
