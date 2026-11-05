package pe.bpm.features.operations.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.core.domain.*
import pe.bpm.core.presentation.*
import pe.bpm.features.operations.domain.*

/**
 * Coordina listas, selección paginada y formularios; nunca accede a transporte ni almacenamiento.
 */
class OperationsViewModel(
    private val operations: ManageOperations,
    private val drafts: ManageOperationsDraft,
    private val newId: () -> String,
    private val now: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(OperationsState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var pickerJob: Job? = null
    private var writer: DraftAutosave<OperationsDraft?>? = null
    private val draining = mutableListOf<Job>()
    private val freshness = DataFreshness()

    fun reset() {
        job?.cancel()
        pickerJob?.cancel()
        writer?.finish()?.let { draining += it }
        draining.removeAll { it.isCompleted }
        writer = null
        freshness.reset()
        mutable.value = OperationsState()
    }

    fun open(userId: String, organizationId: String, role: String, siteId: String? = null) {
        val s = mutable.value
        if (
            s.userId == userId &&
                s.organizationId == organizationId &&
                s.role == role &&
                s.siteId == siteId
        )
            return
        val resource =
            if (s.userId == userId && s.organizationId == organizationId && s.role == role)
                s.resource
            else if (role == "WORKER") OperationsResource.EXECUTIONS
            else if (role == "CHEF") OperationsResource.RECEIPTS else OperationsResource.SUPPLIERS
        reset()
        mutable.value =
            OperationsState(
                userId = userId,
                organizationId = organizationId,
                role = role,
                siteId = siteId,
                resource = resource,
            )
        refresh()
    }

    fun resource(value: OperationsResource) {
        val s = mutable.value
        if (
            !OperationsAccess.canRead(s.role, value) ||
                s.busy ||
                s.pending != null ||
                s.form != null
        )
            return
        job?.cancel()
        freshness.reset()
        mutable.value =
            s.copy(
                resource = value,
                rows = emptyList(),
                query = "",
                page = 1,
                previous = emptyList(),
                token = "",
                nextToken = "",
                saved = null,
                detail = null,
                failure = null,
            )
        refresh()
    }

    fun refreshIfStale() {
        if (freshness.stale && mutable.value.form == null && !mutable.value.busy) refresh()
    }

    fun refresh() {
        val s = mutable.value
        val org = s.organizationId ?: return
        if (s.form != null || s.busy) return
        if (s.resource.needsSite && s.siteId == null) {
            mutable.value = s.copy(loading = false, rows = emptyList())
            return
        }
        job?.cancel()
        mutable.value = s.copy(loading = true, failure = null)
        job =
            viewModelScope.launch {
                val result =
                    operations.list(
                        org,
                        s.siteId,
                        s.role,
                        s.resource,
                        PageRequest(s.token, s.query),
                    )
                currentCoroutineContext().ensureActive()
                when (result) {
                    is OperationsResult.Success -> {
                        if (
                            s.role == "WORKER" &&
                                result.value.items
                                    .filterIsInstance<OperationsEntry.ExecutionEntry>()
                                    .any { it.value.responsibleUserId != s.userId }
                        ) {
                            mutable.value =
                                mutable.value.copy(
                                    loading = false,
                                    rows = emptyList(),
                                    failure = OperationsFailure.INVALID_RESPONSE,
                                )
                            return@launch
                        }
                        freshness.loaded()
                        mutable.value =
                            mutable.value.copy(
                                loading = false,
                                rows = result.value.items,
                                nextToken = result.value.nextToken,
                            )
                    }
                    is OperationsResult.Failure -> {
                        if (hideIfAccessRejected(result.reason)) return@launch
                        mutable.value =
                            mutable.value.copy(
                                loading = false,
                                failure = result.reason,
                                fields = result.fields,
                            )
                    }
                }
            }
    }

    fun query(value: String) {
        if (mutable.value.form != null) return
        mutable.value =
            mutable.value.copy(
                query = value.take(160),
                token = "",
                previous = emptyList(),
                page = 1,
            )
        refresh()
    }

    fun next() {
        val s = mutable.value
        if (s.nextToken.isEmpty() || s.loading) return
        mutable.value =
            s.copy(token = s.nextToken, previous = s.previous + s.token, page = s.page + 1)
        refresh()
    }

    fun previous() {
        val s = mutable.value
        if (s.previous.isEmpty() || s.loading) return
        mutable.value =
            s.copy(token = s.previous.last(), previous = s.previous.dropLast(1), page = s.page - 1)
        refresh()
    }

    fun newForm() = openForm()

    private fun openForm(product: Product? = null) {
        val s = mutable.value
        val org = s.organizationId ?: return
        val user = s.userId ?: return
        if (
            s.form != null ||
                s.loading ||
                s.busy ||
                !OperationsAccess.canCreate(s.role, s.resource) ||
                (s.resource.needsSite && s.siteId == null)
        )
            return
        job?.cancel()
        mutable.value = s.copy(loading = true, failure = null, saved = null, detail = null)
        job =
            viewModelScope.launch {
                val authorization = operations.authorize(org, s.siteId, s.role, s.resource)
                currentCoroutineContext().ensureActive()
                when (authorization) {
                    is OperationsResult.Failure -> {
                        if (hideIfAccessRejected(authorization.reason)) return@launch
                        mutable.value =
                            mutable.value.copy(loading = false, failure = authorization.reason)
                        return@launch
                    }
                    is OperationsResult.Success -> Unit
                }
                draining.toList().joinAll()
                currentCoroutineContext().ensureActive()
                val draft =
                    try {
                        drafts.read(DraftOwner(user, org), s.resource, s.siteId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        mutable.value =
                            mutable.value.copy(
                                loading = false,
                                failure = OperationsFailure.LOCAL,
                                localStatus = DraftSaveStatus.FAILED,
                            )
                        return@launch
                    }
                currentCoroutineContext().ensureActive()
                val form =
                    draft?.form
                        ?: if (product == null) OperationsForm(newId(), now())
                        else
                            OperationsForm(
                                newId(),
                                now(),
                                mapOf(
                                    "requiresTemperature" to product.requiresTemperature.toString(),
                                    "minimumTemperature" to
                                        (product.minimumTemperature?.let { DecimalInput.format(it) }
                                            ?: ""),
                                    "maximumTemperature" to
                                        (product.maximumTemperature?.let { DecimalInput.format(it) }
                                            ?: ""),
                                ),
                                policyProductId = product.id,
                                rowVersion = product.rowVersion,
                            )
                var products = emptyList<Product>()
                var plan: CleaningPlan? = null
                if (s.resource == OperationsResource.RECEIPTS && form.items.isNotEmpty()) {
                    when (
                        val found =
                            loadProducts(org, s.role, form.items.map { it.productId }.toSet())
                    ) {
                        is OperationsResult.Failure -> {
                            if (hideIfAccessRejected(found.reason)) return@launch
                            mutable.value =
                                mutable.value.copy(loading = false, failure = found.reason)
                            return@launch
                        }
                        is OperationsResult.Success -> products = found.value
                    }
                }
                if (
                    s.resource == OperationsResource.EXECUTIONS &&
                        form.values["cleaningPlanId"] != null
                ) {
                    when (
                        val found =
                            findPlan(
                                org,
                                s.siteId!!,
                                s.role,
                                form.values.getValue("cleaningPlanId"),
                            )
                    ) {
                        is OperationsResult.Failure -> {
                            if (hideIfAccessRejected(found.reason)) return@launch
                            mutable.value =
                                mutable.value.copy(loading = false, failure = found.reason)
                            return@launch
                        }
                        is OperationsResult.Success -> {
                            plan = found.value
                            if (!plan.active && draft?.pending == null) {
                                mutable.value =
                                    mutable.value.copy(
                                        loading = false,
                                        failure = OperationsFailure.CONFLICT,
                                    )
                                return@launch
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                attachWriter(DraftOwner(user, org), s.resource, s.siteId)
                mutable.value =
                    mutable.value.copy(
                        loading = false,
                        form = form,
                        pending = draft?.pending,
                        dirty = draft != null,
                        review = draft?.pending != null,
                        products = products,
                        selectedPlan = plan,
                        localStatus =
                            if (draft != null) DraftSaveStatus.SAVED else DraftSaveStatus.IDLE,
                    )
            }
    }

    private fun attachWriter(owner: DraftOwner, resource: OperationsResource, site: String?) {
        writer?.finish()?.let { draining += it }
        var next: DraftAutosave<OperationsDraft?>? = null
        next =
            DraftAutosave(viewModelScope, { drafts.write(owner, resource, site, it) }) { status ->
                if (writer === next) mutable.value = mutable.value.copy(localStatus = status)
            }
        writer = next
    }

    private fun draft(s: OperationsState = mutable.value) =
        s.form?.let { OperationsDraft(s.resource, s.siteId, it, s.pending) }

    fun protect() {
        if (mutable.value.saved == null) draft()?.let { writer?.schedule(it) }
    }

    /** La raíz espera esta confirmación antes de navegar o cambiar de empresa. */
    fun leave(onLeft: () -> Unit) {
        val s = mutable.value
        if (s.busy) return
        if (s.form == null || s.saved != null) {
            onLeft()
            return
        }
        val snapshot = draft(s) ?: return
        val w = writer ?: return
        mutable.value = s.copy(busy = true)
        job =
            viewModelScope.launch {
                val protected = w.flush(snapshot)
                currentCoroutineContext().ensureActive()
                mutable.value =
                    mutable.value.copy(
                        busy = false,
                        failure = if (protected) null else OperationsFailure.LOCAL,
                    )
                if (protected) onLeft()
            }
    }

    fun field(key: String, value: String) {
        val s = mutable.value
        if (!s.editable) return
        mutable.value =
            s.copy(
                form = s.form!!.copy(values = s.form.values + (key to value.take(4000))),
                dirty = true,
                fields = emptyMap(),
                failure = null,
                review = false,
            )
        protect()
    }

    fun item(index: Int, change: (ReceiptItemInput) -> ReceiptItemInput) {
        val s = mutable.value
        if (!s.editable) return
        val form = s.form!!
        if (index !in form.items.indices) return
        mutable.value =
            s.copy(
                form =
                    form.copy(
                        items = form.items.mapIndexed { i, v -> if (i == index) change(v) else v }
                    ),
                dirty = true,
                fields = emptyMap(),
                failure = null,
                review = false,
            )
        protect()
    }

    fun removeItem(index: Int) {
        val s = mutable.value
        if (!s.editable) return
        mutable.value =
            s.copy(
                form = s.form!!.copy(items = s.form.items.filterIndexed { i, _ -> i != index }),
                dirty = true,
                review = false,
            )
        protect()
    }

    fun pick(resource: OperationsResource) {
        val s = mutable.value
        if (
            !s.editable ||
                resource !in
                    setOf(
                        OperationsResource.SUPPLIERS,
                        OperationsResource.PRODUCTS,
                        OperationsResource.PLANS,
                    )
        )
            return
        mutable.value =
            s.copy(
                picker = resource,
                pickerQuery = "",
                pickerRows = emptyList(),
                pickerPrevious = emptyList(),
                pickerPage = 1,
                pickerToken = "",
                pickerNext = "",
            )
        loadPicker()
    }

    fun closePicker() {
        pickerJob?.cancel()
        mutable.value =
            mutable.value.copy(picker = null, pickerRows = emptyList(), pickerLoading = false)
    }

    fun pickerQuery(value: String) {
        mutable.value =
            mutable.value.copy(
                pickerQuery = value.take(160),
                pickerToken = "",
                pickerPrevious = emptyList(),
                pickerPage = 1,
            )
        loadPicker()
    }

    fun pickerNext() {
        val s = mutable.value
        if (s.pickerNext.isEmpty() || s.pickerLoading) return
        mutable.value =
            s.copy(
                pickerToken = s.pickerNext,
                pickerPrevious = s.pickerPrevious + s.pickerToken,
                pickerPage = s.pickerPage + 1,
            )
        loadPicker()
    }

    fun pickerPrevious() {
        val s = mutable.value
        if (s.pickerPrevious.isEmpty() || s.pickerLoading) return
        mutable.value =
            s.copy(
                pickerToken = s.pickerPrevious.last(),
                pickerPrevious = s.pickerPrevious.dropLast(1),
                pickerPage = s.pickerPage - 1,
            )
        loadPicker()
    }

    private fun loadPicker() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val r = s.picker ?: return
        pickerJob?.cancel()
        mutable.value = s.copy(pickerLoading = true, failure = null)
        pickerJob =
            viewModelScope.launch {
                val result =
                    operations.list(
                        org,
                        s.siteId,
                        s.role,
                        r,
                        PageRequest(s.pickerToken, s.pickerQuery),
                    )
                currentCoroutineContext().ensureActive()
                when (result) {
                    is OperationsResult.Success ->
                        mutable.value =
                            mutable.value.copy(
                                pickerRows = result.value.items,
                                pickerNext = result.value.nextToken,
                                pickerLoading = false,
                            )
                    is OperationsResult.Failure -> {
                        if (hideIfAccessRejected(result.reason)) return@launch
                        mutable.value =
                            mutable.value.copy(pickerLoading = false, failure = result.reason)
                    }
                }
            }
    }

    fun select(entry: OperationsEntry) {
        val s = mutable.value
        if (!s.editable) return
        when (entry) {
            is OperationsEntry.SupplierEntry ->
                if (s.resource == OperationsResource.RECEIPTS) {
                    field("supplierId", entry.id)
                    field("supplierName", entry.value.name)
                }
            is OperationsEntry.ProductEntry ->
                if (s.resource == OperationsResource.RECEIPTS && s.form!!.items.size < 50) {
                    mutable.value =
                        s.copy(
                            form =
                                s.form.copy(
                                    items = s.form.items + ReceiptItemInput(newId(), entry.id)
                                ),
                            products = (s.products + entry.value).distinctBy { it.id },
                            dirty = true,
                            review = false,
                        )
                    protect()
                }
            is OperationsEntry.PlanEntry ->
                if (s.resource == OperationsResource.EXECUTIONS && entry.value.active) {
                    field("cleaningPlanId", entry.id)
                    field("planName", "${entry.value.area} · ${entry.value.task}")
                    mutable.value = mutable.value.copy(selectedPlan = entry.value)
                }
            else -> Unit
        }
        closePicker()
    }

    fun configure(product: Product) {
        val s = mutable.value
        if (s.form != null || !OperationsAccess.canCreate(s.role, OperationsResource.PRODUCTS))
            return
        resource(OperationsResource.PRODUCTS)
        job?.cancel()
        mutable.value = mutable.value.copy(loading = false)
        openForm(product)
    }

    private fun command(s: OperationsState) = s.form!!.command(s.resource, s.siteId)

    fun review() {
        val s = mutable.value
        if (!s.editable) return
        val errors = validate(s)
        mutable.value =
            s.copy(
                fields = errors,
                review = errors.isEmpty(),
                failure = if (errors.isEmpty()) null else OperationsFailure.INVALID,
            )
        protect()
    }

    fun editReview() {
        if (mutable.value.pending == null) mutable.value = mutable.value.copy(review = false)
    }

    private fun validate(s: OperationsState) = buildMap {
        val command = command(s)
        putAll(operations.errors(command, s.products))
        when (command) {
            is OperationsCommand.Receive ->
                putAll(
                    OperationsRules.timestamps(
                        command.input.receivedAt,
                        command.input.clientCreatedAt,
                        now(),
                    )
                )
            is OperationsCommand.Execute ->
                putAll(
                    OperationsRules.timestamps(
                        command.input.executedAt,
                        command.input.clientCreatedAt,
                        now(),
                    )
                )
            else -> Unit
        }
        if (
            s.resource == OperationsResource.PRODUCTS &&
                s.form!!.values["requiresTemperature"] == "true"
        ) {
            listOf("minimumTemperature", "maximumTemperature").forEach { key ->
                OperationsRules.temperature.error(s.form.values[key].orEmpty())?.let {
                    put(key, it)
                }
            }
        }
    }

    fun save() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val form = s.form ?: return
        val saveWriter = writer ?: return
        if (
            s.busy ||
                s.loading ||
                s.saved != null ||
                s.failure in setOf(OperationsFailure.SESSION, OperationsFailure.DENIED)
        )
            return
        val errors = if (s.pending == null) validate(s) else emptyMap()
        if (errors.isNotEmpty()) {
            mutable.value =
                s.copy(fields = errors, failure = OperationsFailure.INVALID, review = false)
            return
        }
        mutable.value = s.copy(busy = true, failure = null, fields = emptyMap())
        job =
            viewModelScope.launch {
                if (s.pending == null) {
                    val auth = operations.authorize(org, s.siteId, s.role, s.resource)
                    currentCoroutineContext().ensureActive()
                    when (auth) {
                        is OperationsResult.Failure -> {
                            if (hideIfAccessRejected(auth.reason)) return@launch
                            mutable.value = mutable.value.copy(busy = false, failure = auth.reason)
                            return@launch
                        }
                        is OperationsResult.Success -> Unit
                    }
                    if (s.resource == OperationsResource.EXECUTIONS) {
                        when (
                            val found =
                                findPlan(
                                    org,
                                    s.siteId!!,
                                    s.role,
                                    form.values["cleaningPlanId"].orEmpty(),
                                )
                        ) {
                            is OperationsResult.Failure -> {
                                if (hideIfAccessRejected(found.reason)) return@launch
                                mutable.value =
                                    mutable.value.copy(busy = false, failure = found.reason)
                                return@launch
                            }
                            is OperationsResult.Success ->
                                if (!found.value.active) {
                                    mutable.value =
                                        mutable.value.copy(
                                            busy = false,
                                            failure = OperationsFailure.CONFLICT,
                                        )
                                    return@launch
                                }
                        }
                    }
                    if (s.resource == OperationsResource.RECEIPTS) {
                        when (
                            val found =
                                loadProducts(org, s.role, form.items.map { it.productId }.toSet())
                        ) {
                            is OperationsResult.Failure -> {
                                if (hideIfAccessRejected(found.reason)) return@launch
                                mutable.value =
                                    mutable.value.copy(busy = false, failure = found.reason)
                                return@launch
                            }
                            is OperationsResult.Success -> {
                                mutable.value = mutable.value.copy(products = found.value)
                                val fresh = s.copy(products = found.value)
                                val invalid = validate(fresh)
                                if (invalid.isNotEmpty()) {
                                    mutable.value =
                                        mutable.value.copy(
                                            busy = false,
                                            products = found.value,
                                            fields = invalid,
                                            failure = OperationsFailure.INVALID,
                                            review = false,
                                        )
                                    return@launch
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val pending =
                    s.pending
                        ?: operations.prepare(s.role, command(s), form.id, mutable.value.products)
                if (!saveWriter.flush(OperationsDraft(s.resource, s.siteId, form, pending))) {
                    mutable.value =
                        mutable.value.copy(busy = false, failure = OperationsFailure.LOCAL)
                    return@launch
                }
                mutable.value = mutable.value.copy(pending = pending, review = true)
                val result = operations.submit(org, pending)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is OperationsResult.Success -> {
                        val cleared = saveWriter.flush(null)
                        currentCoroutineContext().ensureActive()
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                pending = null,
                                dirty = false,
                                saved = result.value,
                                failure = if (cleared) null else OperationsFailure.LOCAL,
                            )
                    }
                    is OperationsResult.Failure -> {
                        if (hideIfAccessRejected(result.reason)) return@launch
                        val uncertain =
                            result.reason !in
                                setOf(
                                    OperationsFailure.INVALID,
                                    OperationsFailure.DENIED,
                                    OperationsFailure.NOT_FOUND,
                                    OperationsFailure.CONFLICT,
                                )
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                pending = if (uncertain) pending else null,
                                failure = result.reason,
                                fields = result.fields,
                            )
                        saveWriter.flush(
                            OperationsDraft(
                                s.resource,
                                s.siteId,
                                form,
                                if (uncertain) pending else null,
                            )
                        )
                    }
                }
            }
    }

    fun closeForm() {
        val s = mutable.value
        if (s.busy) return
        if (s.saved != null) {
            finishForm()
            return
        }
        val draft = draft(s) ?: return
        val w = writer ?: return
        mutable.value = s.copy(busy = true)
        job =
            viewModelScope.launch {
                val protected = w.flush(draft)
                currentCoroutineContext().ensureActive()
                if (protected) finishForm()
                else
                    mutable.value =
                        mutable.value.copy(busy = false, failure = OperationsFailure.LOCAL)
            }
    }

    private fun finishForm() {
        writer?.finish()?.let { draining += it }
        writer = null
        mutable.value =
            mutable.value.copy(
                form = null,
                pending = null,
                picker = null,
                dirty = false,
                review = false,
                fields = emptyMap(),
                saved = null,
                failure = null,
                detail = null,
                busy = false,
            )
        refresh()
    }

    fun discard() {
        val s = mutable.value
        if (s.busy || s.pending != null) return
        val w = writer ?: return
        mutable.value = s.copy(busy = true)
        job =
            viewModelScope.launch {
                if (w.flush(null)) {
                    currentCoroutineContext().ensureActive()
                    finishForm()
                } else
                    mutable.value =
                        mutable.value.copy(busy = false, failure = OperationsFailure.LOCAL)
            }
    }

    fun detail(entry: OperationsEntry) = loadDetail(entry,null)
    /** Consulta por ID para navegación contextual; el servidor vuelve a autorizar la sede. */
    fun receiptDetail(id:String) = loadDetail(null,id)
    private fun loadDetail(entry: OperationsEntry?, receiptId:String?) {
        val s = mutable.value
        if (s.form != null) return
        val org = s.organizationId ?: return
        job?.cancel()
        mutable.value = s.copy(loading = true, failure = null, detail = null)
        job =
            viewModelScope.launch {
                val result: OperationsResult<OperationsEntry> =
                    when {
                        receiptId!=null -> when(val r=operations.receipt(org,receiptId)) {
                            is OperationsResult.Success -> OperationsResult.Success(OperationsEntry.ReceiptEntry(r.value))
                            is OperationsResult.Failure -> r
                        }
                        entry is OperationsEntry.ReceiptEntry ->
                            when (val r = operations.receipt(org, entry.id)) {
                                is OperationsResult.Success ->
                                    OperationsResult.Success(OperationsEntry.ReceiptEntry(r.value))
                                is OperationsResult.Failure -> r
                            }
                        entry is OperationsEntry.ExecutionEntry ->
                            when (val r = operations.execution(org, entry.id)) {
                                is OperationsResult.Success ->
                                    OperationsResult.Success(
                                        OperationsEntry.ExecutionEntry(r.value)
                                    )
                                is OperationsResult.Failure -> r
                            }
                        entry!=null -> OperationsResult.Success(entry)
                        else -> OperationsResult.Failure(OperationsFailure.INVALID_RESPONSE)
                    }
                currentCoroutineContext().ensureActive()
                when (result) {
                    is OperationsResult.Success -> {
                        val valid =
                            when (val value = result.value) {
                                is OperationsEntry.ReceiptEntry ->
                                    value.value.input.locationId == s.siteId
                                is OperationsEntry.ExecutionEntry ->
                                    value.value.input.locationId == s.siteId &&
                                        (s.role != "WORKER" ||
                                            value.value.responsibleUserId == s.userId)
                                else -> true
                            }
                        mutable.value =
                            mutable.value.copy(
                                loading = false,
                                detail = if (valid) result.value else null,
                                failure = if (valid) null else OperationsFailure.INVALID_RESPONSE,
                            )
                    }
                    is OperationsResult.Failure -> {
                        if (hideIfAccessRejected(result.reason)) return@launch
                        mutable.value = mutable.value.copy(loading = false, failure = result.reason)
                    }
                }
            }
    }

    /** Revocar la vista no elimina el documento privado necesario para comprobar un envío. */
    private suspend fun hideIfAccessRejected(reason: OperationsFailure): Boolean {
        currentCoroutineContext().ensureActive()
        if (reason !in setOf(OperationsFailure.SESSION, OperationsFailure.DENIED)) return false
        val s = mutable.value
        val snapshot = if (s.saved == null) draft(s) else null
        val localWriter = writer
        val current = currentCoroutineContext()[Job]
        if (job !== current) job?.cancel()
        if (pickerJob !== current) pickerJob?.cancel()
        mutable.value =
            OperationsState(
                userId = s.userId,
                organizationId = s.organizationId,
                role = s.role,
                siteId = s.siteId,
                resource = s.resource,
                failure = reason,
                busy = snapshot != null,
                localStatus = if (snapshot != null) DraftSaveStatus.SAVING else DraftSaveStatus.IDLE,
            )
        val protected = snapshot == null || localWriter?.flush(snapshot) == true
        currentCoroutineContext().ensureActive()
        localWriter?.finish()?.let { draining += it }
        writer = null
        mutable.value =
            mutable.value.copy(
                busy = false,
                localStatus = if (protected) DraftSaveStatus.SAVED else DraftSaveStatus.FAILED,
            )
        return true
    }

    fun closeDetail() {
        mutable.value = mutable.value.copy(detail = null)
    }

    private suspend fun loadProducts(
        org: String,
        role: String,
        ids: Set<String>,
    ): OperationsResult<List<Product>> {
        val found = mutableListOf<Product>()
        var token = ""
        val seen = mutableSetOf<String>()
        do {
            val result =
                operations.list(
                    org,
                    null,
                    role,
                    OperationsResource.PRODUCTS,
                    PageRequest(token = token),
                )
            currentCoroutineContext().ensureActive()
            when (result) {
                is OperationsResult.Failure -> return result
                is OperationsResult.Success -> {
                    found +=
                        result.value.items
                            .filterIsInstance<OperationsEntry.ProductEntry>()
                            .map { it.value }
                            .filter { it.id in ids }
                    token = result.value.nextToken
                    if (token.isNotEmpty() && !seen.add(token))
                        return OperationsResult.Failure(OperationsFailure.INVALID_RESPONSE)
                }
            }
        } while (token.isNotEmpty() && !found.map { it.id }.containsAll(ids))
        return if (found.map { it.id }.containsAll(ids)) OperationsResult.Success(found)
        else OperationsResult.Failure(OperationsFailure.NOT_FOUND)
    }

    private suspend fun findPlan(
        org: String,
        site: String,
        role: String,
        id: String,
    ): OperationsResult<CleaningPlan> {
        var token = ""
        val seen = mutableSetOf<String>()
        do {
            when (
                val result =
                    operations.list(
                        org,
                        site,
                        role,
                        OperationsResource.PLANS,
                        PageRequest(token = token),
                    )
            ) {
                is OperationsResult.Failure -> return result
                is OperationsResult.Success -> {
                    currentCoroutineContext().ensureActive()
                    result.value.items
                        .filterIsInstance<OperationsEntry.PlanEntry>()
                        .firstOrNull { it.id == id }
                        ?.let {
                            return OperationsResult.Success(it.value)
                        }
                    token = result.value.nextToken
                    if (token.isNotEmpty() && !seen.add(token))
                        return OperationsResult.Failure(OperationsFailure.INVALID_RESPONSE)
                }
            }
        } while (token.isNotEmpty())
        return OperationsResult.Failure(OperationsFailure.NOT_FOUND)
    }
}
