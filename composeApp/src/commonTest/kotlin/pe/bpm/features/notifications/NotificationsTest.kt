package pe.bpm.features.notifications

import kotlin.test.*
import kotlinx.coroutines.test.runTest
import pe.bpm.features.notifications.domain.*

class NotificationsTest {
  private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
  private val hash = "a".repeat(64)

  @Test
  fun dataPushOnlyAcceptsCurrentRecipientOrganizationAndMinimalIds() {
    val payload = mapOf("alertId" to id, "organizationId" to "org", "recipientHash" to hash)
    assertEquals(id, acceptedAlertId(payload, "org", hash))
    assertNull(acceptedAlertId(payload, "other", hash))
    assertNull(acceptedAlertId(payload, "org", "b".repeat(64)))
    assertNull(acceptedAlertId(payload + ("message" to "private text"), "org", hash))
    assertNull(acceptedAlertId(payload + ("alertId" to "../../other"), "org", hash))
    assertNull(acceptedAlertId(payload, null, null))
  }

  @Test
  fun unsupportedProviderNeverRegistersAndFailedBindingNeverActivates() = runTest {
    val repo = Fake()
    val unavailable = UnavailablePushProvider()
    assertIs<NotificationResult.Failure>(
      ManageNotificationDevices(repo, unavailable).enable("user", "org")
    )
    assertEquals(0, repo.calls)
    val provider = Provider()
    val manage = ManageNotificationDevices(repo, provider)
    assertIs<NotificationResult.Failure>(manage.enable("user", "org"))
    assertFalse(provider.bound)
    repo.success = true
    assertIs<NotificationResult.Success<NotificationDevice>>(manage.enable("user", "org"))
    assertTrue(provider.bound)
    manage.clearLocal()
    assertFalse(provider.bound)
  }

  private inner class Provider : PushProvider {
    override val available = true
    override val unavailableReason = ""
    var bound = false

    override suspend fun requestToken(userId: String, organizationId: String) =
      PushTokenResult.Success(PushToken(id, id, "ANDROID", "opaque-token"))

    override fun bind(userId: String, organizationId: String, deviceId: String) {
      bound = true
    }

    override fun clearBinding() {
      bound = false
    }

    override suspend fun deleteToken() {}
  }

  private inner class Fake : NotificationDevicesRepository {
    var calls = 0
    var success = false

    override suspend fun register(
      org: String,
      token: PushToken,
    ): NotificationResult<NotificationDevice> {
      calls++
      return if (success) NotificationResult.Success(NotificationDevice(id, "ANDROID", "ACTIVE"))
      else NotificationResult.Failure(NotificationFailure.CONNECTION)
    }

    override suspend fun deactivate(org: String, id: String): NotificationResult<Unit> =
      NotificationResult.Success(Unit)
  }
}
