package pe.bpm.features.operations.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.operations.domain.*

/**
 * Registro en curso. `id` es el UUID estable del movimiento: se reutiliza en cada reintento de la
 * misma operación y solo se renueva después de un rechazo definitivo.
 */
data class StockForm(
    val lot: StorageLot,
    val id: String,
    val createdAt: String,
    val type: StockMovementType = StockMovementType.CONSUMPTION,
    val quantity: String = "",
    val reason: DiscardReason? = null,
    val note: String = "",
    val uncertain: Boolean = false,
) {
    fun input(site: String) =
        StockMovementInput(
            id,
            site,
            lot.id,
            type,
            quantity.trim().replace(',', '.'),
            reason.takeIf { type == StockMovementType.DISCARD },
            note,
            id,
            createdAt,
        )
}

data class StockState(
    val userId: String? = null,
    val organizationId: String? = null,
    val role: String = "",
    val siteId: String? = null,
    val movements: List<StockMovement> = emptyList(),
    val filter: StockFilter = StockFilter(),
    /** Texto legible del lote filtrado (producto y código). */
    val lotLabel: String = "",
    val page: Int = 1,
    val previous: List<String> = emptyList(),
    val token: String = "",
    val nextToken: String = "",
    val loading: Boolean = false,
    val busy: Boolean = false,
    val failure: OperationsFailure? = null,
    val fields: Map<String, String> = emptyMap(),
    val form: StockForm? = null,
    val saved: StockMovement? = null,
    /** Aviso positivo, p. ej. «Guardado en este dispositivo; se enviará al conectarte». */
    val notice: String? = null,
) {
    val canRecord get() = StockAccess.canRecord(role)
}

/** Historial y registro de consumos y descartes sobre lotes PEPS. */
class StockMovementsViewModel(
    private val stock: ManageStock,
    private val newId: () -> String,
    private val now: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(StockState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        mutable.value = StockState()
    }

    fun open(userId: String, organizationId: String, role: String, siteId: String?) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.role == role && s.siteId == siteId) return
        reset()
        mutable.value = StockState(userId, organizationId, role, siteId)
        load(mutable.value)
    }

    fun refresh() = load(mutable.value.copy(token = "", previous = emptyList(), page = 1))

    fun filterType(type: StockMovementType?) {
        val s = mutable.value
        if (!s.loading && !s.busy) load(s.copy(filter = s.filter.copy(type = type), token = "", previous = emptyList(), page = 1))
    }

    fun clearLot() {
        val s = mutable.value
        if (!s.loading && !s.busy) load(s.copy(filter = s.filter.copy(lotId = null), lotLabel = "", token = "", previous = emptyList(), page = 1))
    }

    fun next() {
        val s = mutable.value
        if (!s.loading && s.nextToken.isNotEmpty()) load(s.copy(token = s.nextToken, previous = s.previous + s.token, page = s.page + 1))
    }

    fun previous() {
        val s = mutable.value
        if (!s.loading && s.previous.isNotEmpty()) load(s.copy(token = s.previous.last(), previous = s.previous.dropLast(1), page = s.page - 1))
    }

    private fun load(target: StockState) {
        val org = target.organizationId ?: return
        val site = target.siteId ?: return
        job?.cancel()
        mutable.value = target.copy(loading = true, failure = null)
        job =
            viewModelScope.launch {
                val result = stock.history(org, site, target.role, target.filter, PageRequest(target.token, size = 25))
                currentCoroutineContext().ensureActive()
                mutable.value =
                    when (result) {
                        is OperationsResult.Success ->
                            mutable.value.copy(loading = false, movements = result.value.items, nextToken = result.value.nextToken)
                        is OperationsResult.Failure ->
                            mutable.value.copy(loading = false, movements = emptyList(), nextToken = "", failure = result.reason)
                    }
            }
    }

    /** Abre el formulario sobre un lote de la lista PEPS y filtra el historial por ese lote. */
    fun start(lot: StorageLot) {
        val s = mutable.value
        if (!s.canRecord || s.busy || s.siteId == null) return
        val label = "${lot.productName} · lote ${lot.batchCode}"
        val form = StockForm(lot, newId(), now())
        mutable.value = s.copy(form = form, saved = null, fields = emptyMap(), failure = null)
        if (s.filter.lotId != lot.id)
            load(mutable.value.copy(filter = s.filter.copy(lotId = lot.id), lotLabel = label, token = "", previous = emptyList(), page = 1))
    }

    fun change(
        type: StockMovementType? = null,
        quantity: String? = null,
        reason: DiscardReason? = null,
        note: String? = null,
    ) {
        val s = mutable.value
        val form = s.form ?: return
        if (s.busy || form.uncertain) return
        val newType = type ?: form.type
        mutable.value =
            s.copy(
                form =
                    form.copy(
                        type = newType,
                        quantity = quantity ?: form.quantity,
                        reason = if (newType == StockMovementType.CONSUMPTION) null else reason ?: form.reason,
                        note = note ?: form.note,
                    ),
                fields = emptyMap(),
                failure = null,
            )
    }

    /** Ante LOT_EXPIRED se propone descartar por vencimiento conservando la cantidad. */
    fun suggestExpiredDiscard() {
        val s = mutable.value
        val form = s.form ?: return
        if (s.busy || form.uncertain) return
        mutable.value =
            s.copy(
                form = form.copy(type = StockMovementType.DISCARD, reason = DiscardReason.EXPIRED, id = newId()),
                fields = emptyMap(),
                failure = null,
            )
    }

    fun closeForm() {
        val s = mutable.value
        if (!s.busy && s.form?.uncertain != true) mutable.value = s.copy(form = null, fields = emptyMap(), failure = null)
    }

    fun consumeSaved() {
        mutable.value = mutable.value.copy(saved = null)
    }

    fun submit() {
        val s = mutable.value
        val org = s.organizationId ?: return
        val site = s.siteId ?: return
        val form = s.form ?: return
        if (s.busy) return
        mutable.value = s.copy(busy = true, failure = null, fields = emptyMap())
        job =
            viewModelScope.launch {
                val result = stock.record(org, s.role, form.input(site), form.lot, now(), form.id)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is OperationsResult.Success -> {
                        mutable.value = mutable.value.copy(busy = false, form = null, saved = result.value)
                        refresh()
                    }
                    // Sin conexión: el movimiento quedó en la Outbox con su UUID estable (éxito local pendiente).
                    is OperationsResult.Failure -> if (result.reason == OperationsFailure.QUEUED) {
                        mutable.value = mutable.value.copy(busy = false, form = null, failure = null,
                            notice = "Guardado en este dispositivo. Se enviará cuando recuperes la conexión.")
                        refresh()
                    } else {
                        val definitive = result.reason.definitive
                        mutable.value =
                            mutable.value.copy(
                                busy = false,
                                failure = result.reason,
                                fields = result.fields,
                                // Tras un rechazo definitivo la operación no se registró: se usa un UUID nuevo.
                                form = mutable.value.form?.copy(id = if (definitive) newId() else form.id, uncertain = !definitive),
                            )
                    }
                }
            }
    }
}
