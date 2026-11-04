package pe.bpm.features.incidents.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.presentation.*
import pe.bpm.features.incidents.domain.*

data class IncidentEditorState(
    val form: IncidentForm? = null,
    val pending: PendingIncident? = null,
    val reviewing: Boolean = false,
    val busy: Boolean = false,
    val failure: IncidentsFailure? = null,
    val errors: Map<String, String> = emptyMap(),
    val saveStatus: DraftSaveStatus = DraftSaveStatus.IDLE,
    val recovered: Boolean = false,
    val hasDraft: Boolean = false,
)

/** Único escritor del borrador y del POST durable; nunca cambia un cuerpo pendiente. */
class IncidentEditor
internal constructor(
    private val manage: ManageIncidents,
    private val scope: CoroutineScope,
    private val newId: () -> String,
    private val now: () -> String,
    private val saved: (IncidentSaved) -> Unit,
    private val accessRejected: (IncidentsFailure) -> Unit,
) {
    private val mutable = MutableStateFlow(IncidentEditorState())
    val state = mutable.asStateFlow()
    private var owner: DraftOwner? = null
    private var site = ""
    private var role = ""
    private var zone = ""
    private var generation = 0L
    private var held: IncidentForm? = null
    private var writer: DraftAutosave<IncidentDraft?>? = null
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
        mutable.value = IncidentEditorState()
    }

    internal suspend fun restore(owner: DraftOwner, site: String, role: String, zone: String) {
        reset()
        this.owner = owner
        this.site = site
        this.role = role
        this.zone = zone
        val expected = generation
        draining.toList().forEach { it.join() }
        draining.removeAll { it.isCompleted }
        if (expected != generation) return
        writer =
            DraftAutosave(
                scope,
                { manage.writeDraft(owner, site, it) },
                { status ->
                    if (expected == generation)
                        mutable.value = mutable.value.copy(saveStatus = status)
                },
            )
        try {
            val draft = manage.readDraft(owner, site)
            if (expected != generation) return
            if (draft != null) {
                if (
                    (draft.form.kind == "CATALOG" && !IncidentsAccess.canAuthor(role)) ||
                        (draft.form.kind == "REPORT" && !IncidentsAccess.canReport(role))
                ) {
                    accessRejected(IncidentsFailure.DENIED)
                    return
                }
                draft.form.incidentId?.let { incidentId ->
                    when (val checked = manage.detail(owner.organizationId, incidentId)) {
                        is IncidentsResult.Failure -> {
                            if (expected == generation) accessRejected(checked.reason)
                            return
                        }
                        is IncidentsResult.Success -> {
                            currentCoroutineContext().ensureActive()
                            if (expected != generation) return
                            val d = checked.value
                            if (d.summary.locationId != site) {
                                accessRejected(IncidentsFailure.INVALID_RESPONSE)
                                return
                            }
                            if (draft.pending == null) {
                                val action = IncidentActionType.valueOf(draft.form.kind)
                                if (
                                    action !in d.allowedActions ||
                                        (action == IncidentActionType.VERIFY &&
                                            d.actions.any { it.responsibleUserId == owner.userId })
                                ) {
                                    accessRejected(IncidentsFailure.DENIED)
                                    return
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (expected == generation)
                    mutable.value = IncidentEditorState(draft.form, draft.pending, recovered = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (expected == generation)
                mutable.value = mutable.value.copy(failure = IncidentsFailure.LOCAL)
        }
    }

    internal fun new(
        kind: String,
        detail: IncidentDetail? = null,
        catalog: IncidentCatalog? = null,
    ) {
        if (owner == null || mutable.value.busy || mutable.value.pending != null) return
        if (held != null) {
            resume()
            return
        }
        if (kind == "CATALOG" && !IncidentsAccess.canAuthor(role)) return
        if (kind == "REPORT" && !IncidentsAccess.canReport(role)) return
        if (kind !in setOf("CATALOG", "REPORT")) {
            val action = runCatching { IncidentActionType.valueOf(kind) }.getOrNull() ?: return
            if (detail == null || action !in detail.allowedActions) return
            if (
                action == IncidentActionType.VERIFY &&
                    detail.actions.any { it.responsibleUserId == owner?.userId }
            )
                return
        }
        val values =
            when {
                catalog != null ->
                    mapOf(
                        "code" to catalog.code,
                        "name" to catalog.name,
                        "area" to catalog.area,
                        "normativeCriterion" to catalog.normativeCriterion,
                        "riskLevel" to catalog.riskLevel.name,
                        "evidenceRequired" to catalog.evidenceRequired.toString(),
                    )
                kind == "CATALOG" -> mapOf("riskLevel" to "MEDIUM", "evidenceRequired" to "true")
                kind == "ASSIGN" -> mapOf("actionId" to newId())
                else -> emptyMap()
            }
        mutable.value =
            IncidentEditorState(
                IncidentForm(
                    newId(),
                    now(),
                    kind,
                    values,
                    detail?.summary?.id,
                    catalog?.id,
                    detail?.summary?.rowVersion ?: 1,
                    catalog?.versionNumber,
                )
            )
        schedule()
    }

    fun edit(key: String, value: String) {
        val s = mutable.value
        val form = s.form ?: return
        if (s.busy || s.pending != null) return
        var values = form.values + (key to value.take(4000))
        if (key in setOf("dueDate", "dueTime"))
            values =
                values +
                    ("dueAt" to
                        incidentDeadline(
                                values["dueDate"].orEmpty(),
                                values["dueTime"].orEmpty(),
                                zone,
                            )
                            .orEmpty()) +
                    ("dueZone" to zone)
        mutable.value =
            s.copy(
                form = form.copy(values = values),
                reviewing = false,
                errors = s.errors - key,
                failure = null,
            )
        schedule()
    }

    fun selectCatalog(value: IncidentCatalog) {
        edit("catalogId", value.id)
        edit("catalogName", value.name)
    }

    fun selectResponsible(value: IncidentAssignee) {
        edit("responsibleUserId", value.id)
        edit("responsibleName", value.displayName)
    }

    fun review() {
        val s = mutable.value
        val form = s.form ?: return
        val errors = manage.errors(form, now())
        mutable.value = s.copy(errors = errors, reviewing = errors.isEmpty())
    }

    fun resumeEditing() {
        if (mutable.value.pending == null && !mutable.value.busy)
            mutable.value = mutable.value.copy(reviewing = false)
    }

    fun submit() {
        val s = mutable.value
        val form = s.form ?: return
        val user = owner ?: return
        if (s.busy || (!s.reviewing && s.pending == null)) return
        // Un reintento recupera el hecho aunque su plazo ya haya pasado; conserva el cuerpo
        // original.
        if (s.pending == null) {
            val errors = manage.errors(form, now())
            if (errors.isNotEmpty()) {
                mutable.value = s.copy(errors = errors, reviewing = false)
                return
            }
        }
        val pending = s.pending ?: manage.prepare(form, site, newId())
        val expected = generation
        mutable.value = s.copy(pending = pending, busy = true, failure = null)
        val snapshot = draft() ?: return
        val requestSite = site
        val requestRole = role
        job =
            scope.launch {
                if (writer?.flush(snapshot) != true) {
                    if (expected == generation)
                        mutable.value =
                            mutable.value.copy(busy = false, failure = IncidentsFailure.LOCAL)
                    return@launch
                }
                when (val auth = manage.authorize(user.organizationId, requestSite, requestRole)) {
                    is IncidentsResult.Failure -> {
                        fail(auth.reason, expected)
                        return@launch
                    }
                    is IncidentsResult.Success -> Unit
                }
                if (expected != generation) return@launch
                when (
                    val result =
                        manage.submit(user.organizationId, requestSite, form, pending, requestRole)
                ) {
                    is IncidentsResult.Success -> {
                        if (expected != generation) return@launch
                        if (writer?.flush(null) != true) {
                            mutable.value =
                                mutable.value.copy(busy = false, failure = IncidentsFailure.LOCAL)
                            return@launch
                        }
                        if (expected != generation) return@launch
                        held = null
                        mutable.value = IncidentEditorState()
                        saved(result.value)
                    }
                    is IncidentsResult.Failure -> fail(result.reason, expected)
                }
            }
    }

    private fun fail(reason: IncidentsFailure, expected: Long) {
        if (expected != generation) return
        val definitive =
            reason in
                setOf(
                    IncidentsFailure.INVALID,
                    IncidentsFailure.INVALID_PAGE,
                    IncidentsFailure.NOT_FOUND,
                    IncidentsFailure.CONFLICT,
                )
        mutable.value =
            mutable.value.copy(
                busy = false,
                failure = reason,
                pending = if (definitive) null else mutable.value.pending,
                reviewing = if (definitive) false else mutable.value.reviewing,
            )
        if (definitive) schedule()
        if (reason in setOf(IncidentsFailure.SESSION, IncidentsFailure.DENIED))
            accessRejected(reason)
    }

    private fun draft(): IncidentDraft? =
        (mutable.value.form ?: held)?.let { IncidentDraft(site, it, mutable.value.pending) }

    private fun schedule() {
        writer?.schedule(draft())
    }

    suspend fun flush(): Boolean = if (mutable.value.busy) false else writer?.flush(draft()) ?: true

    fun close(onClosed: () -> Unit = {}) {
        if (mutable.value.busy || mutable.value.pending != null) return
        val expected = generation
        scope.launch {
            if (flush() && expected == generation) {
                held = mutable.value.form
                mutable.value =
                    mutable.value.copy(form = null, reviewing = false, hasDraft = held != null)
                onClosed()
            }
        }
    }

    fun resume() {
        held?.let {
            mutable.value = mutable.value.copy(form = it, hasDraft = false)
            held = null
        }
    }

    fun discard() {
        if (mutable.value.busy || mutable.value.pending != null) return
        val expected = generation
        scope.launch {
            if (writer?.flush(null) == true && expected == generation) {
                held = null
                mutable.value = IncidentEditorState()
            }
        }
    }
}
