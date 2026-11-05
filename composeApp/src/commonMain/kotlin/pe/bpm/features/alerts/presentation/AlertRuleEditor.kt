package pe.bpm.features.alerts.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.*
import pe.bpm.features.alerts.domain.*

data class AlertRuleEditorState(
  val form: AlertRuleForm? = null,
  val pending: PendingAlertRule? = null,
  val reviewing: Boolean = false,
  val busy: Boolean = false,
  val failure: AlertsFailure? = null,
  val errors: Map<String, String> = emptyMap(),
  val saveStatus: DraftSaveStatus = DraftSaveStatus.IDLE,
  val recovered: Boolean = false,
  val hasDraft: Boolean = false,
  val staleVersion: Boolean = false,
)

/** Hace durable el cuerpo antes de HTTP y conserva exactamente la operación incierta. */
class AlertRuleEditor
internal constructor(
  private val manage: ManageAlerts,
  private val scope: CoroutineScope,
  private val newId: () -> String,
  private val saved: (AlertRule) -> Unit,
  private val rejected: (AlertsFailure) -> Unit,
) {
  private val mutable = MutableStateFlow(AlertRuleEditorState())
  val state = mutable.asStateFlow()
  private var owner: DraftOwner? = null
  private var role = ""
  private var generation = 0L
  private var held: AlertRuleDraft? = null
  private var writer: DraftAutosave<AlertRuleDraft?>? = null
  private var job: Job? = null
  private val draining = mutableListOf<Job>()

  fun reset() {
    generation++
    job?.cancel()
    job = null
    writer?.finish()?.let { draining += it }
    writer = null
    owner = null
    held = null
    mutable.value = AlertRuleEditorState()
  }

  internal suspend fun restore(user: DraftOwner, role: String) {
    reset()
    owner = user
    this.role = role
    val expected = generation
    draining.toList().forEach { it.join() }
    draining.removeAll { it.isCompleted }
    if (expected != generation) return
    writer =
      DraftAutosave(
        scope,
        { manage.writeDraft(user, it) },
        { if (expected == generation) mutable.value = mutable.value.copy(saveStatus = it) },
      )
    try {
      val draft = manage.readDraft(user)
      currentCoroutineContext().ensureActive()
      if (expected != generation) return
      if (draft != null) {
        var stale = false
        if (draft.form.original != null) {
          when (
            val checked = manage.rule(user.organizationId, draft.form.locationId, draft.form.id)
          ) {
            is AlertsResult.Failure -> {
              if (expected == generation) {
                mutable.value = mutable.value.copy(failure = checked.reason)
                if (checked.reason in setOf(AlertsFailure.SESSION, AlertsFailure.DENIED))
                  rejected(checked.reason)
              }
              return
            }
            is AlertsResult.Success -> {
              currentCoroutineContext().ensureActive()
              if (expected != generation) return
              if (
                checked.value.type != draft.form.type ||
                  checked.value.locationId != draft.form.locationId
              ) {
                rejected(AlertsFailure.DENIED)
                return
              }
              stale =
                draft.pending == null && checked.value.rowVersion != draft.form.original.rowVersion
            }
          }
        }
        mutable.value =
          AlertRuleEditorState(
            draft.form,
            draft.pending,
            recovered = true,
            staleVersion = stale,
            failure = if (stale) AlertsFailure.CONFLICT else null,
          )
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      if (expected == generation) mutable.value = mutable.value.copy(failure = AlertsFailure.LOCAL)
    }
  }

  fun new(type: AlertType = AlertType.CHECKLIST_MISSING, locationId: String? = null) {
    if (owner == null || !AlertsAccess.canConfigure(role) || mutable.value.busy) return
    if (held != null) {
      resume()
      return
    }
    if (mutable.value.form != null) return
    mutable.value =
      AlertRuleEditorState(
        AlertRuleForm(
          newId(),
          type,
          if (type == AlertType.ORGANIZATION_INACTIVE) null else locationId,
        )
      )
    schedule()
  }

  internal fun existing(rule: AlertRule) {
    if (owner == null || !AlertsAccess.canConfigure(role) || mutable.value.busy) return
    if (held != null) {
      resume()
      return
    }
    if (mutable.value.form != null) return
    mutable.value = AlertRuleEditorState(AlertRuleForm.from(rule))
    schedule()
  }

  private fun change(transform: (AlertRuleForm) -> AlertRuleForm) {
    val s = mutable.value
    val f = s.form ?: return
    if (s.busy || s.pending != null) return
    mutable.value =
      s.copy(
        form = transform(f),
        reviewing = false,
        errors = emptyMap(),
        failure = if (s.staleVersion) AlertsFailure.CONFLICT else null,
      )
    schedule()
  }

  fun edit(key: String, value: String) {
    if (
      key !in
        setOf(
          "name",
          "afterLocalTime",
          "minimumTemperatureCelsius",
          "maximumTemperatureCelsius",
          "inactivityDays",
        )
    )
      return
    change { it.copy(values = it.values + (key to value.take(1000))) }
  }

  fun type(type: AlertType) {
    change {
      if (it.original != null) it
      else
        it.copy(
          type = type,
          locationId = if (type == AlertType.ORGANIZATION_INACTIVE) null else it.locationId,
          values = it.values.filterKeys { key -> key == "name" },
        )
    }
  }

  fun location(id: String?) {
    change {
      if (it.original != null || it.type == AlertType.ORGANIZATION_INACTIVE) it
      else it.copy(locationId = id)
    }
  }

  fun selectLocation(location: AlertLocation?) {
    change {
      if (it.original != null || it.type == AlertType.ORGANIZATION_INACTIVE) it
      else it.copy(locationId = location?.id, locationName = location?.name.orEmpty())
    }
  }

  fun active(value: Boolean) {
    change { it.copy(active = value) }
  }

  fun email(value: Boolean) {
    change { it.copy(emailEnabled = value) }
  }

  fun push(value: Boolean) {
    change { it.copy(pushEnabled = value) }
  }

  fun review() {
    val s = mutable.value
    val f = s.form ?: return
    if (s.staleVersion) return
    val e = AlertRuleValidation.errors(f)
    mutable.value = s.copy(errors = e, reviewing = e.isEmpty())
  }

  fun resumeEditing() {
    if (!mutable.value.busy && mutable.value.pending == null)
      mutable.value = mutable.value.copy(reviewing = false)
  }

  private fun draft() =
    mutable.value.form?.let { AlertRuleDraft(it, mutable.value.pending) } ?: held

  private fun schedule() {
    writer?.schedule(draft())
  }

  fun retrySave() {
    schedule()
  }

  suspend fun flush(): Boolean = if (mutable.value.busy) false else writer?.flush(draft()) ?: true

  fun submit() {
    val s = mutable.value
    val f = s.form ?: return
    val user = owner ?: return
    if (s.busy || s.staleVersion || !s.reviewing && s.pending == null) return
    val errors = AlertRuleValidation.errors(f)
    if (errors.isNotEmpty()) {
      mutable.value = s.copy(errors = errors, reviewing = false)
      return
    }
    val p = s.pending ?: manage.prepare(f, newId())
    val expected = generation
    mutable.value = s.copy(pending = p, busy = true, failure = null)
    val snapshot = AlertRuleDraft(f, p)
    job =
      scope.launch {
        if (writer?.flush(snapshot) != true) {
          if (expected == generation)
            mutable.value = mutable.value.copy(busy = false, failure = AlertsFailure.LOCAL)
          return@launch
        }
        when (val a = manage.authorizeRules(user.organizationId, role)) {
          is AlertsResult.Failure -> {
            fail(a.reason, expected)
            return@launch
          }
          is AlertsResult.Success -> Unit
        }
        currentCoroutineContext().ensureActive()
        if (expected != generation) return@launch
        when (val r = manage.submit(user.organizationId, role, f, p)) {
          is AlertsResult.Failure -> fail(r.reason, expected)
          is AlertsResult.Success -> {
            currentCoroutineContext().ensureActive()
            if (expected != generation) return@launch
            if (writer?.flush(null) != true) {
              mutable.value = mutable.value.copy(busy = false, failure = AlertsFailure.LOCAL)
              return@launch
            }
            if (expected != generation) return@launch
            held = null
            mutable.value = AlertRuleEditorState()
            saved(r.value)
          }
        }
      }
  }

  private fun fail(reason: AlertsFailure, expected: Long) {
    if (expected != generation) return
    val definitive =
      reason in
        setOf(
          AlertsFailure.INVALID,
          AlertsFailure.INVALID_PAGE,
          AlertsFailure.CONFLICT,
          AlertsFailure.NOT_FOUND,
        )
    mutable.value =
      mutable.value.copy(
        busy = false,
        failure = reason,
        pending = if (definitive) null else mutable.value.pending,
        reviewing = if (definitive) false else mutable.value.reviewing,
        staleVersion = reason == AlertsFailure.CONFLICT || mutable.value.staleVersion,
      )
    if (definitive) schedule()
    if (reason in setOf(AlertsFailure.SESSION, AlertsFailure.DENIED)) rejected(reason)
  }

  fun close() {
    deactivate {}
  }

  fun deactivate(onDeactivated: () -> Unit) {
    if (mutable.value.busy) return
    val expected = generation
    scope.launch {
      if (flush() && expected == generation) {
        held = draft()
        mutable.value =
          mutable.value.copy(
            form = null,
            pending = null,
            reviewing = false,
            hasDraft = held != null,
          )
        onDeactivated()
      } else if (expected == generation)
        mutable.value = mutable.value.copy(failure = AlertsFailure.LOCAL)
    }
  }

  fun resume() {
    val d = held ?: return
    held = null
    mutable.value = mutable.value.copy(form = d.form, pending = d.pending, hasDraft = false)
  }

  fun discard() {
    if (mutable.value.busy || mutable.value.pending != null) return
    val expected = generation
    scope.launch {
      if (writer?.flush(null) == true && expected == generation) {
        held = null
        mutable.value = AlertRuleEditorState()
      } else if (expected == generation)
        mutable.value = mutable.value.copy(failure = AlertsFailure.LOCAL)
    }
  }
}
