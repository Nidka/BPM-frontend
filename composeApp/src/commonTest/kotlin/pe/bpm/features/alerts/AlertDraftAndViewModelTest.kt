package pe.bpm.features.alerts

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.core.domain.*
import pe.bpm.core.storage.TestDraftStorage
import pe.bpm.features.alerts.data.*
import pe.bpm.features.alerts.domain.*
import pe.bpm.features.alerts.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class AlertDraftAndViewModelTest {
  private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
  private val alert =
    Alert(
      id,
      AlertType.TRAINING_ASSIGNED,
      "Curso asignado",
      "Revisa tu curso",
      AlertStatus.PENDING,
      "2026-11-01T00:00:00Z",
      null,
      null,
      null,
      "",
      "TRAINING_ASSIGNMENT",
      id,
      true,
    )

  @Test
  fun diskRoundtripSeparatesAccountOrganizationAndOriginAndKeepsPendingExact() = runTest {
    val storage = TestDraftStorage()
    val repo = LocalAlertRuleDraftRepository(storage, "https://api.example")
    val owner = DraftOwner("alice", "org")
    val form =
      AlertRuleForm(
        id,
        AlertType.CHECKLIST_MISSING,
        values = mapOf("name" to "Control de cierre", "afterLocalTime" to "17:30"),
      )
    val draft = AlertRuleDraft(form, prepareAlertRule(form, id))
    repo.write(owner, draft)
    assertEquals(draft, LocalAlertRuleDraftRepository(storage, "https://api.example").read(owner))
    assertNull(repo.read(DraftOwner("bob", "org")))
    assertNull(repo.read(DraftOwner("alice", "other")))
    assertNull(LocalAlertRuleDraftRepository(storage, "https://other.example").read(owner))
  }

  @Test
  fun failedViewKeepsDetailUnreadAndRetryWaitsForServerConfirmation() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val f = Fake()
    val vm = AlertsViewModel(ManageAlerts(f, f, f)) { id }
    try {
      vm.open("alice", "org", "CHEF")
      advanceUntilIdle()
      vm.detail(id)
      advanceUntilIdle()
      assertNull(vm.state.value.unreadCount)
      vm.markViewed()
      runCurrent()
      assertNull(vm.state.value.detail?.viewedAt)
      advanceUntilIdle()
      assertEquals(alert, vm.state.value.detail)
      assertEquals(AlertsFailure.CONNECTION, vm.state.value.actionFailure)
      f.viewResult =
        AlertsResult.Success(
          alert.copy(status = AlertStatus.VIEWED, viewedAt = "2026-11-01T00:01:00Z")
        )
      vm.retryAction()
      advanceUntilIdle()
      assertNotNull(vm.state.value.detail?.viewedAt)
      assertEquals(listOf(id, id), f.views)
    } finally {
      vm.reset()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun rulePendingIsDurableImmutableAndRecoveredWithoutAutomaticSend() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val f = Fake()
    val vm = AlertsViewModel(ManageAlerts(f, f, f)) { id }
    try {
      vm.open("alice", "org", "ADMIN")
      vm.rules()
      advanceUntilIdle()
      vm.editor.new(AlertType.TRAINING_ASSIGNED)
      vm.editor.edit("name", "Capacitación asignada")
      vm.editor.review()
      vm.editor.submit()
      advanceUntilIdle()
      val pending = f.sent.single()
      assertEquals(pending, f.docs[DraftOwner("alice", "org")]?.pending)
      assertTrue(f.persistedBeforeSend)
      vm.editor.edit("name", "No debe reemplazar")
      assertEquals("Capacitación asignada", vm.editor.state.value.form?.values?.get("name"))
      vm.reset()
      vm.open("alice", "org", "ADMIN")
      vm.rules()
      advanceUntilIdle()
      assertEquals(1, f.sent.size)
      assertEquals(pending, vm.editor.state.value.pending)
      vm.editor.submit()
      advanceUntilIdle()
      assertEquals(listOf(pending, pending), f.sent)
    } finally {
      vm.reset()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun deniedRuleAccessNeverRevealsDraftAndLocalFailurePreventsHttp() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val f = Fake()
    val vm = AlertsViewModel(ManageAlerts(f, f, f)) { id }
    try {
      vm.open("alice", "org", "ADMIN")
      vm.rules()
      advanceUntilIdle()
      vm.editor.new(AlertType.TRAINING_ASSIGNED)
      vm.editor.edit("name", "Curso nuevo")
      vm.editor.review()
      f.failWrites = true
      vm.editor.submit()
      advanceUntilIdle()
      assertTrue(f.sent.isEmpty())
      assertEquals(AlertsFailure.LOCAL, vm.editor.state.value.failure)
      f.allow = false
      vm.open("bob", "org", "ADMIN")
      vm.rules()
      advanceUntilIdle()
      assertNull(vm.editor.state.value.form)
      assertEquals(listOf("alice"), f.readUsers)
    } finally {
      vm.reset()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun deniedAcknowledgmentClearsBusyStateAndAllowsLaterAuthorizedDetail() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val f = Fake()
    f.viewResult = AlertsResult.Failure(AlertsFailure.DENIED)
    val vm = AlertsViewModel(ManageAlerts(f, f, f)) { id }
    try {
      vm.open("alice", "org", "CHEF")
      advanceUntilIdle()
      vm.detail(id)
      advanceUntilIdle()
      vm.markViewed()
      advanceUntilIdle()
      assertNull(vm.state.value.detail)
      assertFalse(vm.state.value.actionBusy)
      vm.refresh()
      advanceUntilIdle()
      vm.detail(id)
      advanceUntilIdle()
      assertEquals(alert, vm.state.value.detail)
    } finally {
      vm.reset()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun delayedInboxDenialDoesNotCancelSuperAdminRuleAuthorizationOrDraft() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val f = Fake()
    f.denyInbox = true
    f.inboxDelay = 100
    val vm = AlertsViewModel(ManageAlerts(f, f, f)) { id }
    try {
      vm.open("super", "org", "SUPER_ADMIN")
      vm.rules()
      advanceTimeBy(20)
      runCurrent()
      vm.editor.new(AlertType.TRAINING_ASSIGNED)
      vm.editor.edit("name", "Configuración de empresa")
      advanceUntilIdle()
      assertTrue(vm.state.value.rulesAuthorized)
      assertEquals("Configuración de empresa", vm.editor.state.value.form?.values?.get("name"))
      assertNull(vm.state.value.failure)
    } finally {
      vm.reset()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  private inner class Fake : AlertsRepository, AlertRuleTransport, AlertRuleDraftRepository {
    var allow = true
    var failWrites = false
    var persistedBeforeSend = false
    var denyInbox = false
    var inboxDelay = 0L
    val docs = mutableMapOf<DraftOwner, AlertRuleDraft?>()
    val readUsers = mutableListOf<String>()
    val sent = mutableListOf<PendingAlertRule>()
    val views = mutableListOf<String>()
    var viewResult: AlertsResult<Alert> = AlertsResult.Failure(AlertsFailure.CONNECTION)

    override suspend fun inbox(
      org: String,
      filter: AlertFilter,
      page: PageRequest,
    ): AlertsResult<Page<Alert>> {
      delay(inboxDelay)
      return if (denyInbox) AlertsResult.Failure(AlertsFailure.DENIED)
      else AlertsResult.Success(Page(listOf(alert)))
    }

    override suspend fun unreadCount(org: String): AlertsResult<Int> =
      AlertsResult.Failure(AlertsFailure.CONNECTION)

    override suspend fun detail(org: String, id: String) = AlertsResult.Success(alert)

    override suspend fun view(org: String, id: String): AlertsResult<Alert> {
      views += id
      delay(10)
      return viewResult
    }

    override suspend fun attend(org: String, id: String): AlertsResult<Alert> =
      AlertsResult.Failure(AlertsFailure.CONNECTION)

    override suspend fun authorizeRules(org: String, role: String): AlertsResult<Unit> =
      if (allow) AlertsResult.Success(Unit) else AlertsResult.Failure(AlertsFailure.DENIED)

    override suspend fun rules(org: String, locationId: String?, page: PageRequest) =
      AlertsResult.Success(Page<AlertRule>(emptyList()))

    override suspend fun rule(
      org: String,
      locationId: String?,
      id: String,
    ): AlertsResult<AlertRule> = AlertsResult.Failure(AlertsFailure.NOT_FOUND)

    override fun prepare(form: AlertRuleForm, key: String) = prepareAlertRule(form, key)

    override suspend fun submit(
      org: String,
      form: AlertRuleForm,
      pending: PendingAlertRule,
    ): AlertsResult<AlertRule> {
      persistedBeforeSend = docs.values.any { it?.pending == pending }
      sent += pending
      return AlertsResult.Failure(AlertsFailure.CONNECTION)
    }

    override suspend fun read(owner: DraftOwner): AlertRuleDraft? {
      readUsers += owner.userId
      return docs[owner]
    }

    override suspend fun write(owner: DraftOwner, draft: AlertRuleDraft?) {
      check(!failWrites)
      docs[owner] = draft
    }
  }
}
