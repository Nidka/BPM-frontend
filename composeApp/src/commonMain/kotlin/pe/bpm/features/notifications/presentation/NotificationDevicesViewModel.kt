package pe.bpm.features.notifications.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pe.bpm.features.notifications.domain.*

data class NotificationDevicesState(
  val available: Boolean = false,
  val unavailableReason: String = "",
  val busy: Boolean = false,
  val device: NotificationDevice? = null,
  val failure: NotificationFailure? = null,
)

class NotificationDevicesViewModel(private val manage: ManageNotificationDevices) : ViewModel() {
  private val mutable =
    MutableStateFlow(NotificationDevicesState(manage.available, manage.unavailableReason))
  val state = mutable.asStateFlow()
  private var user: String? = null
  private var org: String? = null
  private var generation = 0L
  private var job: Job? = null
  private var cleanup: Job? = null

  init {
    viewModelScope.launch {
      manage.invalidations.collect {
        if (user != null && org != null && mutable.value.device != null) {
          manage.invalidateOperation()
          job?.cancel()
          mutable.value =
            mutable.value.copy(
              device = null,
              busy = false,
              failure = NotificationFailure.TOKEN_CHANGED,
            )
        }
      }
    }
  }

  fun open(userId: String, organizationId: String) {
    if (user == userId && org == organizationId) return
    if (user != null || org != null) reset()
    user = userId
    org = organizationId
  }

  fun reset() {
    generation++
    job?.cancel()
    manage.clearLocal()
    user = null
    org = null
    mutable.value = NotificationDevicesState(manage.available, manage.unavailableReason)
    // La limpieza nativa no depende de que la API conserve una sesión válida.
    val previous = cleanup
    cleanup =
      viewModelScope.launch {
        previous?.join()
        try {
          manage.deleteLocalToken()
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {}
      }
  }

  fun enable() {
    val u = user ?: return
    val o = org ?: return
    if (mutable.value.busy || !manage.available) return
    val expected = generation
    mutable.value = mutable.value.copy(busy = true, failure = null)
    job =
      viewModelScope.launch {
        cleanup?.join()
        currentCoroutineContext().ensureActive()
        if (expected != generation) return@launch
        val r =
          try {
            manage.enable(u, o)
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            NotificationResult.Failure(NotificationFailure.CONNECTION)
          }
        currentCoroutineContext().ensureActive()
        if (expected != generation) return@launch
        mutable.value =
          when (r) {
            is NotificationResult.Success -> mutable.value.copy(busy = false, device = r.value)
            is NotificationResult.Failure -> mutable.value.copy(busy = false, failure = r.reason)
          }
      }
  }

  fun disable() {
    val o = org ?: return
    if (mutable.value.busy) return
    val expected = generation
    val id = mutable.value.device?.id
    manage.clearLocal()
    mutable.value = mutable.value.copy(busy = true, failure = null)
    job =
      viewModelScope.launch {
        val r =
          try {
            manage.disable(o, id)
          } catch (e: CancellationException) {
            throw e
          } catch (_: Exception) {
            NotificationResult.Failure(NotificationFailure.CONNECTION)
          }
        if (expected != generation) return@launch
        mutable.value =
          mutable.value.copy(
            busy = false,
            device = null,
            failure = (r as? NotificationResult.Failure)?.reason,
          )
      }
  }
}
