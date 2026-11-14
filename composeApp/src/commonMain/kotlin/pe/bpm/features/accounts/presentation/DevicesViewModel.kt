package pe.bpm.features.accounts.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pe.bpm.features.accounts.domain.*

data class DevicesState(
    val page: AccountPage<AccountDevice> = AccountPage(),
    val revoking: AccountDevice? = null,
    val busy: Boolean = false,
    val failure: AccountFailure? = null,
    val notice: String? = null,
)

/** Mis dispositivos: solo los propios y activos; revocar es idempotente. */
class DevicesViewModel(private val listDevices: ListMyDevices, private val revokeDevice: RevokeMyDevice) : ViewModel() {
    private val mutable = MutableStateFlow(DevicesState())
    val state = mutable.asStateFlow()
    private var listJob: Job? = null
    private var actionJob: Job? = null

    fun open() { reset(); fetch(AccountPage()) }
    fun reset() { listJob?.cancel(); actionJob?.cancel(); mutable.value = DevicesState() }
    fun refresh() = fetch(mutable.value.page.restart())
    fun next() { mutable.value.page.forward()?.let(::fetch) }
    fun previous() { mutable.value.page.backward()?.let(::fetch) }
    fun retry() = fetch(mutable.value.page.retry())

    private fun fetch(page: AccountPage<AccountDevice>) {
        listJob?.cancel()
        mutable.update { it.copy(page = page.copy(loading = true, failure = null)) }
        listJob = viewModelScope.launch {
            val result = listDevices(page.request)
            currentCoroutineContext().ensureActive()
            mutable.update { s ->
                when (result) {
                    is AccountsResult.Success -> s.copy(page = page.copy(items = result.value.items, nextToken = result.value.nextToken, loading = false, failure = null))
                    is AccountsResult.Failure -> s.copy(page = page.copy(items = emptyList(), nextToken = "", loading = false, failure = result.reason))
                }
            }
        }
    }

    fun askRevoke(device: AccountDevice) { if (!mutable.value.busy) mutable.update { it.copy(revoking = device, failure = null, notice = null) } }
    fun cancelRevoke() { if (!mutable.value.busy) mutable.update { it.copy(revoking = null) } }
    fun confirmRevoke() {
        val s = mutable.value
        val device = s.revoking ?: return
        if (s.busy) return
        mutable.update { it.copy(busy = true, failure = null, notice = null) }
        actionJob = viewModelScope.launch {
            val result = revokeDevice(device.id)
            currentCoroutineContext().ensureActive()
            when (result) {
                is AccountsResult.Success -> { mutable.update { it.copy(busy = false, revoking = null, notice = "Dispositivo revocado. Dejará de recibir avisos.") }; refresh() }
                is AccountsResult.Failure -> { mutable.update { it.copy(busy = false, revoking = null, failure = result.reason) }; if (result.reason == AccountFailure.NOT_FOUND) refresh() }
            }
        }
    }
}
