package pe.bpm.features.alerts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pe.bpm.core.domain.*
import pe.bpm.core.presentation.DataFreshness
import pe.bpm.features.alerts.domain.*

data class AlertsPage<T>(
  val rows: List<T> = emptyList(),
  val query: String = "",
  val token: String = "",
  val next: String = "",
  val previous: List<String> = emptyList(),
) {
  val number: Int
    get() = previous.size + 1
}

enum class AlertAction {
  VIEW,
  ATTEND,
}

data class AlertsState(
  val userId: String? = null,
  val organizationId: String? = null,
  val role: String = "",
  val rulesMode: Boolean = false,
  val loading: Boolean = false,
  val failure: AlertsFailure? = null,
  val filter: AlertFilter = AlertFilter.ALL,
  val inbox: AlertsPage<Alert> = AlertsPage(),
  val rules: AlertsPage<AlertRule> = AlertsPage(),
  val ruleLocationId: String? = null,
  val rulesAuthorized: Boolean = false,
  val inboxLoaded: Boolean = false,
  val rulesLoaded: Boolean = false,
  val detail: Alert? = null,
  val detailLoading: Boolean = false,
  val actionBusy: Boolean = false,
  val actionFailure: AlertsFailure? = null,
  val pendingAction: AlertAction? = null,
  val unreadCount: Int? = null,
  val countLoading: Boolean = false,
  val countFailure: AlertsFailure? = null,
  val savedRevision: Long = 0,
)

/** Bandeja personal y reglas independientes; ninguna respuesta antigua restaura otro contexto. */
class AlertsViewModel(private val manage: ManageAlerts, private val newId: () -> String) :
  ViewModel() {
  private val mutable = MutableStateFlow(AlertsState())
  val state = mutable.asStateFlow()
  val editor =
    AlertRuleEditor(
      manage,
      viewModelScope,
      newId,
      {
        mutable.value = mutable.value.copy(savedRevision = mutable.value.savedRevision + 1)
        loadRules()
      },
      { failure(it, true) },
    )
  private var generation = 0L
  private var listRevision = 0L
  private var detailRevision = 0L
  private var listJob: Job? = null
  private var countJob: Job? = null
  private var detailJob: Job? = null
  private var authJob: Job? = null
  private var actionJob: Job? = null
  private val freshness = DataFreshness()

  fun reset() {
    generation++
    listRevision++
    detailRevision++
    listJob?.cancel()
    countJob?.cancel()
    detailJob?.cancel()
    authJob?.cancel()
    actionJob?.cancel()
    editor.reset()
    freshness.reset()
    mutable.value = AlertsState()
  }

  fun open(userId: String, organizationId: String, role: String) {
    val s = mutable.value
    if (s.userId == userId && s.organizationId == organizationId && s.role == role) return
    reset()
    mutable.value = AlertsState(userId, organizationId, role)
    inbox()
    refreshCount()
  }

  private fun reject(reason: AlertsFailure) {
    val s = mutable.value
    reset()
    mutable.value =
      AlertsState(
        s.userId,
        s.organizationId,
        s.role,
        rulesMode = s.rulesMode,
        failure = reason,
        countFailure = reason,
      )
  }

  private suspend fun valid(expected: Long): Boolean {
    currentCoroutineContext().ensureActive()
    return expected == generation
  }

  private fun failure(reason: AlertsFailure, ruleOperation: Boolean = mutable.value.rulesMode) {
    if (reason == AlertsFailure.SESSION) {
      reject(reason)
      return
    }
    if (reason == AlertsFailure.DENIED) {
      if (ruleOperation) {
        editor.reset()
        mutable.value =
          mutable.value.copy(
            loading = false,
            rulesAuthorized = false,
            rules = AlertsPage(),
            failure = reason,
          )
      } else
        mutable.value =
          mutable.value.copy(
            loading = false,
            detailLoading = false,
            detail = null,
            inbox = AlertsPage(),
            actionBusy = false,
            pendingAction = null,
            actionFailure = null,
            failure = if (mutable.value.rulesMode) mutable.value.failure else reason,
          )
    } else
      mutable.value = mutable.value.copy(loading = false, detailLoading = false, failure = reason)
  }

  fun refreshCount() {
    val org = mutable.value.organizationId ?: return
    countJob?.cancel()
    val expected = generation
    mutable.value = mutable.value.copy(countLoading = true, countFailure = null)
    countJob =
      viewModelScope.launch {
        val r = manage.unreadCount(org)
        if (!valid(expected)) return@launch
        when (r) {
          is AlertsResult.Success ->
            mutable.value =
              mutable.value.copy(unreadCount = r.value, countLoading = false, countFailure = null)
          is AlertsResult.Failure -> {
            if (r.reason == AlertsFailure.SESSION) reject(r.reason)
            else
              mutable.value =
                mutable.value.copy(
                  unreadCount = null,
                  countLoading = false,
                  countFailure = r.reason,
                )
          }
        }
      }
  }

  fun inbox() {
    if (mutable.value.organizationId == null) return
    mutable.value =
      mutable.value.copy(
        rulesMode = false,
        detail = null,
        actionFailure = null,
        pendingAction = null,
      )
    loadInbox()
  }

  fun rules(locationId: String? = null) {
    val s = mutable.value
    val org = s.organizationId ?: return
    if (!AlertsAccess.canConfigure(s.role)) {
      failure(AlertsFailure.DENIED)
      return
    }
    mutable.value =
      s.copy(
        rulesMode = true,
        detail = null,
        ruleLocationId = locationId,
        rules = if (s.ruleLocationId == locationId) s.rules else AlertsPage(),
        failure = null,
      )
    if (s.rulesAuthorized) {
      loadRules()
      return
    }
    authJob?.cancel()
    val expected = generation
    mutable.value = mutable.value.copy(loading = true)
    authJob =
      viewModelScope.launch {
        when (val r = manage.authorizeRules(org, s.role)) {
          is AlertsResult.Failure -> {
            if (valid(expected)) failure(r.reason)
          }
          is AlertsResult.Success -> {
            if (!valid(expected)) return@launch
            // La lista confirma acceso vigente antes de revelar una copia administrativa.
            when (val proof = manage.rules(org, locationId, PageRequest(size = 1))) {
              is AlertsResult.Failure -> {
                if (valid(expected)) failure(proof.reason)
                return@launch
              }
              is AlertsResult.Success -> Unit
            }
            if (!valid(expected)) return@launch
            mutable.value = mutable.value.copy(rulesAuthorized = true, loading = false)
            editor.restore(DraftOwner(s.userId!!, org), s.role)
            if (valid(expected)) loadRules()
          }
        }
      }
  }

  fun recoverDraft() {
    val s = mutable.value
    val org = s.organizationId ?: return
    val user = s.userId ?: return
    if (!s.rulesAuthorized || editor.state.value.busy) return
    authJob?.cancel()
    val expected = generation
    authJob =
      viewModelScope.launch {
        when (val r = manage.authorizeRules(org, s.role)) {
          is AlertsResult.Failure -> {
            if (valid(expected)) failure(r.reason, true)
          }
          is AlertsResult.Success -> {
            if (valid(expected)) editor.restore(DraftOwner(user, org), s.role)
          }
        }
      }
  }

  fun query(value: String) {
    val s = mutable.value
    val q = value.take(200)
    mutable.value =
      if (s.rulesMode) s.copy(rules = AlertsPage(query = q))
      else s.copy(inbox = AlertsPage(query = q))
    listJob?.cancel()
    val expected = generation
    listJob =
      viewModelScope.launch {
        delay(300)
        if (valid(expected)) if (mutable.value.rulesMode) loadRules() else loadInbox()
      }
  }

  fun filter(value: AlertFilter) {
    mutable.value =
      mutable.value.copy(filter = value, inbox = AlertsPage(query = mutable.value.inbox.query))
    loadInbox()
  }

  fun ruleLocation(value: String?) {
    if (editor.state.value.form != null) return
    mutable.value =
      mutable.value.copy(
        ruleLocationId = value,
        rules = AlertsPage(query = mutable.value.rules.query),
      )
    loadRules()
  }

  fun next() = move(true)

  fun previous() = move(false)

  private fun <T> moved(p: AlertsPage<T>, forward: Boolean): AlertsPage<T> =
    if (forward && p.next.isNotEmpty()) p.copy(token = p.next, previous = p.previous + p.token)
    else if (!forward && p.previous.isNotEmpty())
      p.copy(token = p.previous.last(), previous = p.previous.dropLast(1))
    else p

  private fun move(forward: Boolean) {
    val s = mutable.value
    if (s.loading) return
    mutable.value =
      if (s.rulesMode) s.copy(rules = moved(s.rules, forward))
      else s.copy(inbox = moved(s.inbox, forward))
    if (s.rulesMode) loadRules() else loadInbox()
  }

  private fun loadInbox() {
    val s = mutable.value
    val org = s.organizationId ?: return
    listJob?.cancel()
    val expected = generation
    val rev = ++listRevision
    mutable.value = s.copy(loading = true, failure = null)
    listJob =
      viewModelScope.launch {
        val r = manage.inbox(org, s.filter, PageRequest(s.inbox.token, s.inbox.query))
        if (!valid(expected) || rev != listRevision) return@launch
        when (r) {
          is AlertsResult.Failure -> failure(r.reason, false)
          is AlertsResult.Success -> {
            mutable.value =
              mutable.value.copy(
                loading = false,
                inboxLoaded = true,
                inbox = s.inbox.copy(rows = r.value.items, next = r.value.nextToken),
              )
            freshness.loaded()
          }
        }
      }
  }

  private fun loadRules() {
    val s = mutable.value
    val org = s.organizationId ?: return
    if (!s.rulesAuthorized) return
    listJob?.cancel()
    val expected = generation
    val rev = ++listRevision
    mutable.value = s.copy(loading = true, failure = null)
    listJob =
      viewModelScope.launch {
        val r = manage.rules(org, s.ruleLocationId, PageRequest(s.rules.token, s.rules.query))
        if (!valid(expected) || rev != listRevision) return@launch
        when (r) {
          is AlertsResult.Failure -> failure(r.reason)
          is AlertsResult.Success -> {
            mutable.value =
              mutable.value.copy(
                loading = false,
                rulesLoaded = true,
                rules = s.rules.copy(rows = r.value.items, next = r.value.nextToken),
              )
            freshness.loaded()
          }
        }
      }
  }

  fun detail(id: String) {
    val s = mutable.value
    val org = s.organizationId ?: return
    if (s.actionBusy) return
    detailJob?.cancel()
    val expected = generation
    val rev = ++detailRevision
    mutable.value =
      s.copy(
        detail = if (s.detail?.id == id) s.detail else null,
        detailLoading = true,
        failure = null,
        actionFailure = null,
        pendingAction = null,
      )
    detailJob =
      viewModelScope.launch {
        val r = manage.detail(org, id)
        if (!valid(expected) || rev != detailRevision) return@launch
        when (r) {
          is AlertsResult.Failure -> {
            if (r.reason == AlertsFailure.NOT_FOUND)
              mutable.value = mutable.value.copy(detail = null)
            failure(r.reason)
          }
          is AlertsResult.Success ->
            mutable.value = mutable.value.copy(detail = r.value, detailLoading = false)
        }
      }
  }

  fun closeDetail() {
    if (mutable.value.actionBusy) return
    detailRevision++
    detailJob?.cancel()
    mutable.value =
      mutable.value.copy(
        detail = null,
        detailLoading = false,
        actionFailure = null,
        pendingAction = null,
        failure = null,
      )
    loadInbox()
  }

  fun markViewed() = act(AlertAction.VIEW)

  fun markAttended() = act(AlertAction.ATTEND)

  fun retryAction() {
    mutable.value.pendingAction?.let(::act)
  }

  private fun act(action: AlertAction) {
    val s = mutable.value
    val org = s.organizationId ?: return
    val a = s.detail ?: return
    if (
      s.actionBusy ||
        a.status == AlertStatus.CANCELLED ||
        action == AlertAction.VIEW && a.viewedAt != null ||
        action == AlertAction.ATTEND && a.attendedAt != null
    )
      return
    val expected = generation
    mutable.value = s.copy(actionBusy = true, actionFailure = null, pendingAction = action)
    actionJob =
      viewModelScope.launch {
        val r = if (action == AlertAction.VIEW) manage.view(org, a.id) else manage.attend(org, a.id)
        if (!valid(expected)) return@launch
        when (r) {
          is AlertsResult.Success -> {
            mutable.value =
              mutable.value.copy(
                detail = r.value,
                actionBusy = false,
                pendingAction = null,
                actionFailure = null,
              )
            refreshCount()
          }
          is AlertsResult.Failure -> {
            if (r.reason in setOf(AlertsFailure.SESSION, AlertsFailure.DENIED)) {
              failure(r.reason, false)
              return@launch
            }
            mutable.value =
              mutable.value.copy(
                actionBusy = false,
                actionFailure = r.reason,
                detail = if (r.reason == AlertsFailure.NOT_FOUND) null else mutable.value.detail,
              )
          }
        }
      }
  }

  fun editRule(rule: AlertRule) {
    val s = mutable.value
    val org = s.organizationId ?: return
    if (!s.rulesAuthorized || s.loading) return
    detailJob?.cancel()
    val expected = generation
    mutable.value = s.copy(loading = true)
    detailJob =
      viewModelScope.launch {
        when (val r = manage.rule(org, rule.locationId, rule.id)) {
          is AlertsResult.Failure -> {
            if (valid(expected)) failure(r.reason)
          }
          is AlertsResult.Success -> {
            if (!valid(expected)) return@launch
            mutable.value = mutable.value.copy(loading = false)
            editor.existing(r.value)
          }
        }
      }
  }

  fun refresh() {
    val s = mutable.value
    if (s.rulesMode) {
      if (s.rulesAuthorized) loadRules() else rules(s.ruleLocationId)
    } else if (s.detail != null) detail(s.detail.id) else loadInbox()
    refreshCount()
  }

  fun refreshIfStale() {
    if (freshness.stale && !mutable.value.loading && editor.state.value.form == null) refresh()
  }
}
