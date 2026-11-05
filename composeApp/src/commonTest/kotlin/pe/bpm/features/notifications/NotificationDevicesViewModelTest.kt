package pe.bpm.features.notifications

import androidx.lifecycle.viewModelScope
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import pe.bpm.features.notifications.domain.*
import pe.bpm.features.notifications.presentation.*

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDevicesViewModelTest {
  private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"

  @Test
  fun previousTokenDeletionFinishesBeforeAnotherAccountCanRegister() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val provider = Provider()
    val repo = Repo()
    val vm = NotificationDevicesViewModel(ManageNotificationDevices(repo, provider))
    try {
      vm.open("alice", "org")
      vm.enable()
      advanceUntilIdle()
      assertEquals("alice", provider.bound)
      provider.deletionGate = CompletableDeferred()
      vm.reset()
      runCurrent()
      vm.open("bob", "org")
      vm.enable()
      runCurrent()
      assertEquals(1, provider.requests)
      assertNull(provider.bound)
      provider.deletionGate!!.complete(Unit)
      advanceUntilIdle()
      assertEquals(2, provider.requests)
      assertEquals("bob", provider.bound)
      assertEquals(listOf("token", "delete-start", "delete-end", "token"), provider.events)
    } finally {
      provider.deletionGate?.complete(Unit)
      vm.reset()
      advanceUntilIdle()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun initialOpenDoesNotDeleteExistingPlatformTokenOrRequestPermission() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val provider = Provider()
    val vm = NotificationDevicesViewModel(ManageNotificationDevices(Repo(), provider))
    try {
      vm.open("alice", "org")
      advanceUntilIdle()
      assertEquals(0, provider.requests)
      assertTrue(provider.events.isEmpty())
      assertEquals(0, provider.clears)
    } finally {
      vm.reset()
      advanceUntilIdle()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  @Test
  fun tokenRotationInvalidatesConfirmedDeviceWithoutRequestingPermissionAgain() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val provider = Provider()
    val vm = NotificationDevicesViewModel(ManageNotificationDevices(Repo(), provider))
    try {
      vm.open("alice", "org")
      vm.enable()
      advanceUntilIdle()
      assertNotNull(vm.state.value.device)
      provider.bound = null
      provider.invalidations.emit(Unit)
      advanceUntilIdle()
      assertNull(vm.state.value.device)
      assertEquals(NotificationFailure.TOKEN_CHANGED, vm.state.value.failure)
      assertEquals(1, provider.requests)
    } finally {
      vm.reset()
      advanceUntilIdle()
      vm.viewModelScope.cancel()
      Dispatchers.resetMain()
    }
  }

  private inner class Provider : PushProvider {
    override val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val available = true
    override val unavailableReason = ""
    var requests = 0
    var clears = 0
    var bound: String? = null
    var deletionGate: CompletableDeferred<Unit>? = null
    val events = mutableListOf<String>()

    override suspend fun requestToken(userId: String, organizationId: String): PushTokenResult {
      requests++
      events += "token"
      return PushTokenResult.Success(PushToken(id, id, "ANDROID", "opaque"))
    }

    override fun bind(userId: String, organizationId: String, deviceId: String) {
      bound = userId
    }

    override fun clearBinding() {
      clears++
      bound = null
    }

    override suspend fun deleteToken() {
      events += "delete-start"
      deletionGate?.await()
      events += "delete-end"
    }
  }

  private inner class Repo : NotificationDevicesRepository {
    override suspend fun register(org: String, token: PushToken) =
      NotificationResult.Success(NotificationDevice(token.id, token.platform, "ACTIVE"))

    override suspend fun deactivate(org: String, id: String): NotificationResult<Unit> =
      NotificationResult.Success(Unit)
  }
}
